package com.lucca.ko

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lucca.ko.data.db.ChatRole
import com.lucca.ko.data.db.KoDatabase
import com.lucca.ko.data.db.ProposalStatus
import com.lucca.ko.data.db.RecipeChatMessage
import com.lucca.ko.data.remote.MealDbClient
import com.lucca.ko.data.remote.nas.NasClient
import com.lucca.ko.data.remote.nas.NasStatusMonitor
import com.lucca.ko.data.repo.RecipeChatRepository
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.domain.recipe.IngredientDraft
import com.lucca.ko.domain.recipe.RecipeDraft
import com.lucca.ko.domain.recipe.StepDraft
import com.lucca.ko.domain.recipe.toDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Accepting and undoing an AI edit, against real SQLite.
 *
 * The undo path is the safety net for a model that gets a change half right, so it is the part
 * worth pinning: a snapshot has to be taken before the change, and restoring it has to put every
 * ingredient and step back exactly as they were.
 *
 * No network: proposals are written straight into the message row, which is what the streaming
 * layer does anyway once a `proposal` event arrives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeChatRepositoryTest {

    private lateinit var db: KoDatabase
    private lateinit var recipes: RecipeRepository
    private lateinit var chat: RecipeChatRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KoDatabase::class.java,
        ).allowMainThreadQueries().build()

        val http = OkHttpClient()
        val nas = NasClient(http, http, { "http://127.0.0.1:1/" }, { "" })

        recipes = RecipeRepository(
            recipeDao = db.recipeDao(),
            tagDao = db.tagDao(),
            pantryDao = db.pantryDao(),
            shoppingDao = db.shoppingDao(),
            mealPlanDao = db.mealPlanDao(),
            mealDb = MealDbClient(http),
        )
        chat = RecipeChatRepository(
            chatDao = db.chatDao(),
            recipeDao = db.recipeDao(),
            pantryDao = db.pantryDao(),
            recipes = recipes,
            nas = nas,
            nasStatus = NasStatusMonitor(nas, CoroutineScope(UnconfinedTestDispatcher())),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedRecipe(): Long = recipes.saveDraft(
        RecipeDraft(
            title = "Creamy mushroom pasta",
            servingsText = "2",
            ingredients = listOf(
                IngredientDraft(key = -1, name = "tagliatelle", amount = "200 g"),
                IngredientDraft(key = -2, name = "double cream", amount = "150 ml"),
                IngredientDraft(key = -3, name = "butter", amount = "20 g"),
            ),
            steps = listOf(
                StepDraft(key = -4, text = "Boil the pasta."),
                StepDraft(key = -5, text = "Fry the mushrooms in butter."),
            ),
            tags = listOf("Quick"),
        ),
    )

    /** The shape the server sends on a `proposal` event. */
    private fun proposalJson(
        title: String = "Creamy mushroom pasta",
        servings: Int = 2,
        ingredients: List<Pair<String, String>> = listOf(
            "tagliatelle" to "200 g",
            "oat milk" to "150 ml",
            "olive oil" to "20 g",
        ),
        steps: List<String> = listOf("Boil the pasta.", "Fry the mushrooms in olive oil."),
    ): String {
        val ing = ingredients.joinToString(",") {
            """{"name":"${it.first}","amount":"${it.second}","optional":false}"""
        }
        val st = steps.joinToString(",") { """{"text":"$it"}""" }
        return """{"title":"$title","servings":$servings,"ingredients":[$ing],"steps":[$st],"tags":[]}"""
    }

    private suspend fun addProposal(dishId: Long, json: String = proposalJson()): Long {
        db.chatDao().insertMessage(
            RecipeChatMessage(dishId = dishId, role = ChatRole.USER, content = "make it dairy-free"),
        )
        return db.chatDao().insertMessage(
            RecipeChatMessage(
                dishId = dishId,
                role = ChatRole.ASSISTANT,
                content = "Swap the cream for oat milk.",
                proposalJson = json,
                proposalSummary = "Made it dairy-free",
                proposalStatus = ProposalStatus.PENDING,
            ),
        )
    }

    // ---- Accepting -------------------------------------------------------------------

    @Test
    fun `accepting a proposal rewrites the recipe`() = runTest {
        val id = seedRecipe()
        val messageId = addProposal(id)

        chat.acceptProposal(messageId)

        val details = recipes.observeRecipe(id).first()!!
        assertEquals(
            listOf("tagliatelle", "oat milk", "olive oil"),
            details.orderedIngredients.map { it.rawName },
        )
        assertEquals("Fry the mushrooms in olive oil.", details.orderedSteps.last().text)
    }

    @Test
    fun `accepting parses the proposed amounts, it does not just store text`() = runTest {
        val id = seedRecipe()
        chat.acceptProposal(addProposal(id))

        val oatMilk = recipes.observeRecipe(id).first()!!
            .orderedIngredients.first { it.rawName == "oat milk" }
        assertEquals(150.0, oatMilk.quantity)
        assertEquals("ml", oatMilk.unit)
        assertEquals("150 ml", oatMilk.measure)
    }

    @Test
    fun `accepting marks the message so the chat shows what happened`() = runTest {
        val id = seedRecipe()
        val messageId = addProposal(id)
        chat.acceptProposal(messageId)
        assertEquals(ProposalStatus.ACCEPTED, db.chatDao().messageById(messageId)?.proposalStatus)
    }

    @Test
    fun `accepting clears stale macros rather than keeping a number for the old ingredients`() =
        runTest {
            val id = seedRecipe()
            recipes.saveDraft(
                recipes.observeRecipe(id).first()!!.toDraft().copy(kcalPerServing = 620.0),
            )
            assertEquals(620.0, recipes.recipeById(id)?.kcalPerServing)

            chat.acceptProposal(addProposal(id))

            // Swapping cream for oat milk changes the calories; keeping 620 would be a number
            // the UI would happily display and that would simply be wrong.
            assertNull(recipes.recipeById(id)?.kcalPerServing)
        }

    @Test
    fun `accepting keeps the tags the recipe already had`() = runTest {
        val id = seedRecipe()
        chat.acceptProposal(addProposal(id))
        assertTrue(recipes.observeRecipe(id).first()!!.tags.any { it.name == "Quick" })
    }

    @Test
    fun `rejecting changes nothing but the message`() = runTest {
        val id = seedRecipe()
        val messageId = addProposal(id)

        chat.rejectProposal(messageId)

        assertEquals(ProposalStatus.REJECTED, db.chatDao().messageById(messageId)?.proposalStatus)
        assertEquals(
            listOf("tagliatelle", "double cream", "butter"),
            recipes.observeRecipe(id).first()!!.orderedIngredients.map { it.rawName },
        )
        assertFalse("a rejected proposal must leave no snapshot behind", chat.hasUndo(id))
    }

    // ---- Undo ------------------------------------------------------------------------

    @Test
    fun `undo puts every ingredient and step back`() = runTest {
        val id = seedRecipe()
        chat.acceptProposal(addProposal(id))
        assertTrue(chat.hasUndo(id))

        assertTrue(chat.undoLastChange(id))

        val details = recipes.observeRecipe(id).first()!!
        assertEquals(
            listOf("tagliatelle", "double cream", "butter"),
            details.orderedIngredients.map { it.rawName },
        )
        assertEquals(
            listOf("Boil the pasta.", "Fry the mushrooms in butter."),
            details.orderedSteps.map { it.text },
        )
        assertEquals("150 ml", details.orderedIngredients[1].measure)
    }

    @Test
    fun `undo consumes the snapshot, so it is not offered twice`() = runTest {
        val id = seedRecipe()
        chat.acceptProposal(addProposal(id))

        assertTrue(chat.undoLastChange(id))
        assertFalse(chat.hasUndo(id))
        assertFalse(chat.undoLastChange(id))
    }

    @Test
    fun `two accepted edits undo one at a time, most recent first`() = runTest {
        val id = seedRecipe()
        chat.acceptProposal(addProposal(id))
        chat.acceptProposal(
            addProposal(
                id,
                proposalJson(title = "Vegan pasta", ingredients = listOf("tagliatelle" to "300 g")),
            ),
        )
        assertEquals("Vegan pasta", recipes.recipeById(id)?.title)

        chat.undoLastChange(id)
        // Back to the dairy-free version, not all the way to the original.
        assertEquals("Creamy mushroom pasta", recipes.recipeById(id)?.title)
        assertEquals(
            listOf("tagliatelle", "oat milk", "olive oil"),
            recipes.observeRecipe(id).first()!!.orderedIngredients.map { it.rawName },
        )

        chat.undoLastChange(id)
        assertEquals(
            listOf("tagliatelle", "double cream", "butter"),
            recipes.observeRecipe(id).first()!!.orderedIngredients.map { it.rawName },
        )
    }

    @Test
    fun `undo on a recipe that was never changed does nothing`() = runTest {
        val id = seedRecipe()
        assertFalse(chat.hasUndo(id))
        assertFalse(chat.undoLastChange(id))
    }

    @Test
    fun `the revision stack is trimmed rather than growing forever`() = runTest {
        val id = seedRecipe()
        // A snapshot is a whole serialized recipe; unbounded, this becomes the largest thing
        // in the database.
        repeat(25) { chat.acceptProposal(addProposal(id)) }
        assertEquals(20, chat.observeRevisions(id).first().size)
    }

    // ---- Chat log ---------------------------------------------------------------------

    @Test
    fun `messages come back in the order they were said`() = runTest {
        val id = seedRecipe()
        addProposal(id)
        val messages = chat.observeMessages(id).first()
        assertEquals(2, messages.size)
        assertEquals(ChatRole.USER, messages.first().role)
        assertEquals(ChatRole.ASSISTANT, messages.last().role)
    }

    @Test
    fun `deleting a recipe takes its conversation and snapshots with it`() = runTest {
        val id = seedRecipe()
        chat.acceptProposal(addProposal(id))

        recipes.deleteRecipe(id)

        assertTrue(chat.observeMessages(id).first().isEmpty())
        assertTrue(chat.observeRevisions(id).first().isEmpty())
    }

    @Test
    fun `clearing the chat leaves the recipe and its undo history alone`() = runTest {
        val id = seedRecipe()
        chat.acceptProposal(addProposal(id))

        chat.clearChat(id)

        assertTrue(chat.observeMessages(id).first().isEmpty())
        assertTrue("undo must survive clearing the conversation", chat.hasUndo(id))
    }
}
