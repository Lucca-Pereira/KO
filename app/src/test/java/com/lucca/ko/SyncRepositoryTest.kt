package com.lucca.ko

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lucca.ko.data.db.KoDatabase
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.data.prefs.SyncSettingsRepository
import com.lucca.ko.data.remote.sync.KoSyncClient
import com.lucca.ko.data.remote.sync.MealPlanEntryWire
import com.lucca.ko.data.remote.sync.PantryItemWire
import com.lucca.ko.data.remote.sync.RecipeIngredientWire
import com.lucca.ko.data.remote.sync.RecipeWire
import com.lucca.ko.data.remote.sync.ShoppingItemWire
import com.lucca.ko.data.remote.sync.SyncResponse
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.data.repo.PantryRepository
import com.lucca.ko.data.repo.RecipeMerge
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.data.repo.RevisionRepository
import com.lucca.ko.data.repo.ShoppingRepository
import com.lucca.ko.data.repo.SyncOutcome
import com.lucca.ko.data.repo.SyncRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Push-then-pull against a fake NAS ([MockWebServer] standing in for `server/ko_sync`).
 *
 * What's worth pinning here isn't the wire format itself (that's `server/tests/test_api.py`'s
 * job) — it's that the phone-side idempotency and name-matching safety nets actually fire: a
 * retried push after a failed attempt reuses the same `remoteId` rather than minting a new one,
 * and a pulled pantry/shopping row whose name already exists locally under a different (or no)
 * `remoteId` merges onto that row instead of crashing on the unique index.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncRepositoryTest {

    private lateinit var db: KoDatabase
    private lateinit var server: MockWebServer
    private lateinit var recipeRepository: RecipeRepository
    private lateinit var pantryRepository: PantryRepository
    private lateinit var shoppingRepository: ShoppingRepository
    private lateinit var mealPlanRepository: MealPlanRepository
    private lateinit var syncSettings: SyncSettingsRepository
    private lateinit var syncRepository: SyncRepository
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KoDatabase::class.java,
        ).allowMainThreadQueries().build()

        recipeRepository = RecipeRepository(db.recipeDao(), db.tagDao(), db.pantryDao(), db.shoppingDao(), db.mealPlanDao())
        pantryRepository = PantryRepository(db.pantryDao(), db.shoppingDao())
        shoppingRepository = ShoppingRepository(db.shoppingDao(), db.pantryDao())
        mealPlanRepository = MealPlanRepository(db.mealPlanDao(), db.recipeDao())
        val revisionRepository = RevisionRepository(db.revisionDao())
        val recipeMerge = RecipeMerge(recipeRepository, revisionRepository)

        server = MockWebServer()
        server.start()

        syncSettings = SyncSettingsRepository(ApplicationProvider.getApplicationContext())
        // Its DataStore file lives on Robolectric's shared app files dir, which outlives any one
        // test — without this, a value set by an earlier test leaks into "not configured" here.
        runBlocking {
            syncSettings.setNasUrl(null)
            syncSettings.setToken(null)
            syncSettings.setLastSyncedAt(0)
        }

        syncRepository = SyncRepository(
            recipeRepository = recipeRepository,
            pantryRepository = pantryRepository,
            shoppingRepository = shoppingRepository,
            mealPlanRepository = mealPlanRepository,
            recipeMerge = recipeMerge,
            syncSettingsRepository = syncSettings,
            koSyncClient = KoSyncClient(OkHttpClient()),
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private suspend fun configure() {
        syncSettings.setNasUrl(server.url("/").toString())
        syncSettings.setToken("test-token")
    }

    private fun emptyResponse(serverTime: Long = 1_000_000) =
        json.encodeToString(SyncResponse.serializer(), SyncResponse(serverTime = serverTime))

    @Test
    fun `sync is a no-op when the NAS isn't configured`() = runTest {
        val outcome = syncRepository.sync()
        assertEquals(SyncOutcome.NotConfigured, outcome)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a new pantry item is pushed and stamped synced`() = runTest {
        configure()
        pantryRepository.savePantryItem(
            id = null, name = "Onion", category = "Produce", status = StockStatus.IN_STOCK,
            quantity = null, note = null,
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyResponse()))

        val outcome = syncRepository.sync() as SyncOutcome.Success
        assertEquals(1, outcome.pushed)

        val request = server.takeRequest()
        assertTrue(request.path?.endsWith("/v1/sync") == true)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("\"name\":\"Onion\""))

        val stored = pantryRepository.snapshot().single()
        assertNotNull(stored.remoteId)
        assertNotNull(stored.syncedAt)
    }

    @Test
    fun `a retried push after a failed attempt reuses the same remoteId`() = runTest {
        configure()
        pantryRepository.savePantryItem(
            id = null, name = "Garlic", category = "Produce", status = StockStatus.IN_STOCK,
            quantity = null, note = null,
        )

        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val failed = syncRepository.sync()
        assertTrue(failed is SyncOutcome.Failed)
        val remoteIdAfterFailure = pantryRepository.snapshot().single().remoteId
        assertNotNull(remoteIdAfterFailure)

        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyResponse()))
        val succeeded = syncRepository.sync() as SyncOutcome.Success
        assertEquals(1, succeeded.pushed)

        assertEquals(2, server.requestCount)
        server.takeRequest() // the failed attempt
        val secondRequestBody = server.takeRequest().body.readUtf8() // the retry
        assertTrue(secondRequestBody.contains(remoteIdAfterFailure!!))
        assertEquals(remoteIdAfterFailure, pantryRepository.snapshot().single().remoteId)
        assertEquals(1, pantryRepository.snapshot().size) // no duplicate row from the retry
    }

    @Test
    fun `a pulled recipe with no local match is created`() = runTest {
        configure()
        val wire = RecipeWire(
            remoteId = "r-1",
            title = "Chicken Teriyaki",
            ingredients = listOf(RecipeIngredientWire("chicken thigh", "400 g")),
            updatedAt = 1000,
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                json.encodeToString(SyncResponse.serializer(), SyncResponse(serverTime = 2000, recipes = listOf(wire))),
            ),
        )

        val outcome = syncRepository.sync() as SyncOutcome.Success
        assertEquals(1, outcome.pulled)

        val details = recipeRepository.searchLibrary("").first().first { it.recipe.title == "Chicken Teriyaki" }
        assertEquals("r-1", details.recipe.remoteId)
        assertEquals(1, details.orderedIngredients.size)
    }

    @Test
    fun `a pulled pantry row matching an existing remoteId updates that row`() = runTest {
        configure()
        val localId = pantryRepository.savePantryItem(
            id = null, name = "Onion", category = "Produce", status = StockStatus.IN_STOCK,
            quantity = null, note = null,
        )!!
        val existing = pantryRepository.byId(localId)!!
        // Mark it already-synced (syncedAt >= updatedAt) so it's not itself in this round's push
        // set — otherwise the "skip pull entries we just pushed" optimization would suppress the
        // very update this test is checking for.
        pantryRepository.stampSync(localId, "p-remote", existing.updatedAt, existing.updatedAt + 1)

        val wire = PantryItemWire(remoteId = "p-remote", name = "Onion", status = "LOW", updatedAt = 5000)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                json.encodeToString(SyncResponse.serializer(), SyncResponse(serverTime = 6000, pantryItems = listOf(wire))),
            ),
        )

        syncRepository.sync()

        assertEquals(1, pantryRepository.snapshot().size)
        val updated = pantryRepository.byId(localId)!!
        assertEquals(StockStatus.LOW, updated.status)
        assertEquals("p-remote", updated.remoteId)
    }

    @Test
    fun `a pulled pantry row whose name already exists locally merges instead of crashing`() = runTest {
        configure()
        pantryRepository.savePantryItem(
            id = null, name = "Onion", category = "Produce", status = StockStatus.IN_STOCK,
            quantity = null, note = null,
        )

        // A different remoteId, same name — as if this pantry item was created independently on
        // both the phone and (via MCP) the NAS before either side had ever synced.
        val wire = PantryItemWire(remoteId = "server-side-onion", name = "Onion", status = "OUT", updatedAt = 9000)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                json.encodeToString(SyncResponse.serializer(), SyncResponse(serverTime = 9500, pantryItems = listOf(wire))),
            ),
        )

        syncRepository.sync()

        // Must not throw on the normalizedName unique index, and must not duplicate the row.
        assertEquals(1, pantryRepository.snapshot().size)
        val merged = pantryRepository.snapshot().single()
        assertEquals("server-side-onion", merged.remoteId)
        assertEquals(StockStatus.OUT, merged.status)
    }

    @Test
    fun `a pulled shopping row whose name already exists locally merges instead of crashing`() = runTest {
        configure()
        shoppingRepository.addManualShoppingItem("Flour")

        val wire = ShoppingItemWire(remoteId = "server-side-flour", name = "Flour")
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                json.encodeToString(SyncResponse.serializer(), SyncResponse(serverTime = 100, shoppingItems = listOf(wire))),
            ),
        )

        syncRepository.sync()

        val all = shoppingRepository.shoppingItems.first()
        assertEquals(1, all.size)
        assertEquals("server-side-flour", all.single().remoteId)
    }

    @Test
    fun `a plan entry pulled for a recipe not yet in the library is skipped, not crashed`() = runTest {
        configure()
        val response = SyncResponse(
            serverTime = 10,
            mealPlanEntries = listOf(
                MealPlanEntryWire(
                    remoteId = "m-1",
                    recipeTitle = "Nonexistent Recipe",
                    date = LocalDate.now().toString(),
                    slot = MealSlot.DINNER.name,
                ),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(json.encodeToString(SyncResponse.serializer(), response)))

        val outcome = syncRepository.sync() as SyncOutcome.Success
        assertEquals(0, outcome.pulled)
        assertEquals(1, outcome.skipped.size)
    }
}
