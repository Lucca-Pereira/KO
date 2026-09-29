package com.lucca.ko

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lucca.ko.data.db.KoDatabase
import com.lucca.ko.data.db.LogSlot
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.NutritionEntry
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.data.prefs.ProfileRepository
import com.lucca.ko.data.prefs.SyncSettingsRepository
import com.lucca.ko.data.remote.sync.BodyMetricWire
import com.lucca.ko.data.remote.sync.KoSyncClient
import com.lucca.ko.data.remote.sync.MealPlanEntryWire
import com.lucca.ko.data.remote.sync.NutritionEntryWire
import com.lucca.ko.data.remote.sync.PantryItemWire
import com.lucca.ko.data.remote.sync.RecipeIngredientWire
import com.lucca.ko.data.remote.sync.RecipeWire
import com.lucca.ko.data.remote.sync.ShoppingItemWire
import com.lucca.ko.data.remote.sync.SupplementLogWire
import com.lucca.ko.data.remote.sync.SyncResponse
import com.lucca.ko.data.repo.BodyRepository
import com.lucca.ko.data.repo.GymSyncRepository
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.data.repo.PantryRepository
import com.lucca.ko.data.repo.RecipeMerge
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.data.repo.RevisionRepository
import com.lucca.ko.data.repo.ShoppingRepository
import com.lucca.ko.data.repo.SupplementRepository
import com.lucca.ko.data.repo.SyncOutcome
import com.lucca.ko.data.repo.SyncRepository
import com.lucca.ko.domain.IngredientMatcher
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private lateinit var bodyRepository: BodyRepository
    private lateinit var supplementRepository: SupplementRepository
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
        bodyRepository = BodyRepository(db.bodyDao(), ProfileRepository(ApplicationProvider.getApplicationContext()))
        supplementRepository = SupplementRepository(db.supplementDao(), db.nutritionDao())
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
            gymSyncRepository = GymSyncRepository(db.nutritionDao(), db.supplementDao(), db.bodyDao()),
            bodyRepository = bodyRepository,
            recipeMerge = recipeMerge,
            syncSettingsRepository = syncSettings,
            koSyncClient = KoSyncClient(OkHttpClient()),
        )
    }

    @After
    fun tearDown() {
        db.close()
        runCatching { server.shutdown() } // some tests shut it down themselves mid-test
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
    fun `a connection failure returns Failed instead of crashing the caller`() = runTest {
        // The actual bug this regression-tests: a plain connection-level failure (server down,
        // wrong port, cleartext blocked) used to propagate as a raw exception out of sync() and
        // crash whatever called it — a manual "Sync now" tap had no other safety net. Shutting
        // the fake server down first means the connection itself fails, not just the request.
        syncSettings.setNasUrl(server.url("/").toString())
        syncSettings.setToken("test-token")
        server.shutdown()

        val outcome = syncRepository.sync()
        assertTrue(outcome is SyncOutcome.Failed)
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
    fun `every sync lists what the phone still has, minus ticked-off shopping`() = runTest {
        // This list is how deletions reach the NAS — anything missing from it that the NAS knows
        // the phone has already seen gets dropped there, so Claude stops seeing it.
        configure()
        pantryRepository.savePantryItem(
            id = null, name = "Onion", category = "Produce", status = StockStatus.IN_STOCK,
            quantity = null, note = null,
        )
        shoppingRepository.addManualShoppingItem("flour")
        shoppingRepository.addManualShoppingItem("sugar")
        val sugar = shoppingRepository.shoppingByNormalized(IngredientMatcher.normalize("sugar"))!!
        shoppingRepository.setShoppingChecked(sugar, true)
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyResponse()))

        syncRepository.sync() as SyncOutcome.Success

        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val present = body.getValue("present").jsonObject
        fun ids(key: String) = present.getValue(key).jsonArray.map { it.jsonPrimitive.content }

        // Two, not one: ticking sugar off puts it back in the pantry as in stock.
        val pantryIds = pantryRepository.snapshot().map { it.remoteId }
        assertEquals(2, pantryIds.size)
        assertEquals(pantryIds.toSet(), ids("pantryItems").toSet())
        val flour = shoppingRepository.shoppingByNormalized(IngredientMatcher.normalize("flour"))!!
        assertEquals(listOf(flour.remoteId), ids("shoppingItems"))
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

    // ---- Gym ---------------------------------------------------------------------------

    private fun pull(response: SyncResponse) =
        server.enqueue(MockResponse().setResponseCode(200).setBody(json.encodeToString(SyncResponse.serializer(), response)))

    @Test
    fun `a diary line is pushed with the supplement list and targets`() = runTest {
        configure()
        supplementRepository.seedDefaultsIfEmpty()
        db.nutritionDao().insert(NutritionEntry(date = "2026-09-29", label = "Oats", kcal = 300.0))
        pull(SyncResponse(serverTime = 1_000_000))

        val outcome = syncRepository.sync() as SyncOutcome.Success
        assertEquals(1, outcome.pushed)

        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val pushed = body.getValue("push").jsonObject.getValue("nutritionEntries").jsonArray.single().jsonObject
        assertEquals("Oats", pushed.getValue("label").jsonPrimitive.content)
        val names = body.getValue("supplements").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(setOf("Creatine", "Whey protein"), names.toSet())
        val present = body.getValue("present").jsonObject.getValue("nutritionEntries").jsonArray
        assertEquals(pushed.getValue("remoteId"), present.single())
        assertNotNull(db.nutritionDao().getAll().single().syncedAt)
    }

    @Test
    fun `a diary line Claude logged is created, and its tombstone deletes it`() = runTest {
        configure()
        val line = NutritionEntryWire(
            remoteId = "claude-1", date = "2026-09-29", slot = "BREAKFAST", label = "3 eggs",
            kcal = 234.0, proteinG = 19.0, updatedAt = 900_000,
        )
        pull(SyncResponse(serverTime = 1_000_000, nutritionEntries = listOf(line)))
        syncRepository.sync() as SyncOutcome.Success

        val created = db.nutritionDao().getAll().single()
        assertEquals("3 eggs", created.label)
        assertEquals(LogSlot.BREAKFAST, created.slot)
        // Arrived stamped: the next sync must not push it straight back.
        assertTrue(db.nutritionDao().pendingPush().isEmpty())

        pull(SyncResponse(serverTime = 2_000_000, nutritionEntries = listOf(line.copy(deleted = true, updatedAt = 1_500_000))))
        syncRepository.sync() as SyncOutcome.Success
        assertTrue(db.nutritionDao().getAll().isEmpty())
    }

    @Test
    fun `a supplement Claude ticked replaces the local diary line rather than doubling it`() = runTest {
        configure()
        supplementRepository.seedDefaultsIfEmpty()
        val whey = db.supplementDao().byName("Whey protein")!!
        supplementRepository.logDose(whey, LocalDate.parse("2026-09-29")) // ticked on the phone
        pull(SyncResponse(serverTime = 1_000_000)) // first sync pushes the phone's tick
        syncRepository.sync() as SyncOutcome.Success
        server.takeRequest()

        pull(
            SyncResponse(
                serverTime = 2_000_000,
                supplementLogs = listOf(
                    SupplementLogWire("claude-log", "2026-09-29", "Whey protein", doses = 2.0, updatedAt = 1_500_000),
                ),
                nutritionEntries = listOf(
                    NutritionEntryWire(
                        remoteId = "claude-line", date = "2026-09-29", slot = "SUPPLEMENT",
                        sourceType = "SUPPLEMENT", supplementName = "Whey protein", label = "Whey protein",
                        servings = 2.0, kcal = 224.0, proteinG = 48.0, updatedAt = 1_500_000,
                    ),
                ),
            ),
        )
        val outcome = syncRepository.sync() as SyncOutcome.Success
        assertTrue(outcome.skipped.toString(), outcome.skipped.isEmpty())

        val lines = db.nutritionDao().entriesOn("2026-09-29")
        assertEquals(listOf(48.0), lines.map { it.proteinG })
        assertEquals("claude-log", db.supplementDao().entryFor("2026-09-29", whey.id)!!.remoteId)
    }

    @Test
    fun `a weigh-in Claude logged lands by date and isn't pushed back`() = runTest {
        configure()
        pull(
            SyncResponse(
                serverTime = 1_000_000,
                bodyMetrics = listOf(BodyMetricWire(date = "2026-09-29", weightKg = 78.4, updatedAt = 900_000)),
            ),
        )
        syncRepository.sync() as SyncOutcome.Success

        assertEquals(78.4, db.bodyDao().onDate("2026-09-29")!!.weightKg!!, 0.0)
        assertTrue(db.bodyDao().pendingPush().isEmpty())
    }
}