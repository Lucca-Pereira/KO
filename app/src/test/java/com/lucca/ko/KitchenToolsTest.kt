package com.lucca.ko

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lucca.ko.data.db.KoDatabase
import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.RecipeSource
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.data.remote.claude.ClaudeBlock
import com.lucca.ko.data.remote.claude.KitchenTools
import com.lucca.ko.data.remote.claude.ToolOutcome
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.data.repo.PantryRepository
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.data.repo.RevisionRepository
import com.lucca.ko.data.repo.ShoppingRepository
import com.lucca.ko.domain.recipe.IngredientDraft
import com.lucca.ko.domain.recipe.RecipeDraft
import com.lucca.ko.domain.recipe.StepDraft
import com.lucca.ko.domain.recipe.toDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the agent's tools actually do, against real SQLite.
 *
 * The property worth pinning is the split between [ToolOutcome.Immediate] and
 * [ToolOutcome.NeedsConfirmation]: a read (`get_pantry`) must never touch the database, and a
 * write (`save_recipe` and friends) must not happen until [KitchenTools.applyConfirmed] is
 * called — that gap is what lets the app show the user a review dialog in between.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KitchenToolsTest {

    private lateinit var db: KoDatabase
    private lateinit var recipes: RecipeRepository
    private lateinit var revisions: RevisionRepository
    private lateinit var tools: KitchenTools

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KoDatabase::class.java,
        ).allowMainThreadQueries().build()

        recipes = RecipeRepository(
            recipeDao = db.recipeDao(),
            tagDao = db.tagDao(),
            pantryDao = db.pantryDao(),
            shoppingDao = db.shoppingDao(),
            mealPlanDao = db.mealPlanDao(),
        )
        revisions = RevisionRepository(db.chatDao())
        tools = KitchenTools(
            pantryRepository = PantryRepository(db.pantryDao(), db.shoppingDao()),
            recipeRepository = recipes,
            mealPlanRepository = MealPlanRepository(db.mealPlanDao(), db.recipeDao()),
            shoppingRepository = ShoppingRepository(db.shoppingDao(), db.pantryDao()),
            revisionRepository = revisions,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun toolUse(name: String, inputJson: String) =
        ClaudeBlock.ToolUse(id = "call_1", name = name, input = Json.parseToJsonElement(inputJson))

    // ---- The confirmation gap ----------------------------------------------------------

    @Test
    fun `get_pantry runs immediately and touches nothing`() = runTest {
        db.pantryDao().upsert(PantryItem(name = "Onion", normalizedName = "onion", status = StockStatus.LOW))

        val outcome = tools.run(toolUse(KitchenTools.GET_PANTRY, "{}"))

        assertTrue(outcome is ToolOutcome.Immediate)
        assertTrue((outcome as ToolOutcome.Immediate).resultJson.contains("Onion"))
    }

    @Test
    fun `save_recipe needs confirmation rather than writing immediately`() = runTest {
        val outcome = tools.run(
            toolUse(KitchenTools.SAVE_RECIPE, """{"title":"Stew","ingredients":[],"steps":[]}"""),
        )

        assertTrue(outcome is ToolOutcome.NeedsConfirmation)
        assertEquals(0, db.recipeDao().getAllRecipes().size)
    }

    // ---- save_recipe ---------------------------------------------------------------------

    @Test
    fun `applyConfirmed save_recipe creates a new recipe from its own knowledge`() = runTest {
        val json = """
            {"title":"Stew","servings":4,
             "ingredients":[{"name":"beef","amount":"500 g"}],
             "steps":[{"text":"Cook it slowly."}]}
        """.trimIndent()

        tools.applyConfirmed(KitchenTools.SAVE_RECIPE, json)

        val recipe = db.recipeDao().getAllRecipes().single()
        assertEquals("Stew", recipe.title)
        assertEquals(RecipeSource.AI, recipe.source)
        assertEquals(listOf("beef"), db.recipeDao().ingredientsFor(recipe.id).map { it.rawName })
    }

    @Test
    fun `applyConfirmed save_recipe with a recipeId edits it and snapshots for undo`() = runTest {
        val id = recipes.saveDraft(
            RecipeDraft(
                title = "Creamy pasta",
                ingredients = listOf(IngredientDraft(key = -1, name = "double cream", amount = "150 ml")),
                steps = listOf(StepDraft(key = -2, text = "Boil the pasta.")),
            ),
        )
        assertFalse(revisions.hasUndo(id))

        val json = """
            {"recipeId":$id,"title":"Creamy pasta","servings":2,
             "ingredients":[{"name":"oat milk","amount":"150 ml"}],
             "steps":[{"text":"Boil the pasta."}]}
        """.trimIndent()
        tools.applyConfirmed(KitchenTools.SAVE_RECIPE, json)

        assertEquals(
            listOf("oat milk"),
            recipes.observeRecipe(id).first()!!.orderedIngredients.map { it.rawName },
        )
        assertTrue("an edit to an existing recipe must be undoable", revisions.hasUndo(id))

        revisions.undoLastChange(id, recipes)
        assertEquals(
            listOf("double cream"),
            recipes.observeRecipe(id).first()!!.orderedIngredients.map { it.rawName },
        )
    }

    @Test
    fun `applyConfirmed save_recipe clears stale macros`() = runTest {
        val id = recipes.saveDraft(RecipeDraft(title = "Curry", servingsText = "2"))
        recipes.saveDraft(recipes.observeRecipe(id).first()!!.toDraft().copy(kcalPerServing = 500.0))
        assertEquals(500.0, recipes.recipeById(id)?.kcalPerServing)

        tools.applyConfirmed(
            KitchenTools.SAVE_RECIPE,
            """{"recipeId":$id,"title":"Curry","servings":2,"ingredients":[],"steps":[]}""",
        )

        assertEquals(null, recipes.recipeById(id)?.kcalPerServing)
    }

    @Test
    fun `diffForSaveRecipe reports what would actually change`() = runTest {
        val id = recipes.saveDraft(
            RecipeDraft(
                title = "Pasta",
                ingredients = listOf(IngredientDraft(key = -1, name = "cream", amount = "150 ml")),
            ),
        )
        val diff = tools.diffForSaveRecipe(
            """{"recipeId":$id,"title":"Pasta","servings":2,"ingredients":[{"name":"oat milk","amount":"150 ml"}],"steps":[]}""",
        )
        assertTrue(diff.hasChanges)
        assertEquals(1, diff.addedCount)
        assertEquals(1, diff.removedCount)
    }

    // ---- add_to_meal_plan ------------------------------------------------------------------

    @Test
    fun `applyConfirmed add_to_meal_plan matches an existing recipe by title`() = runTest {
        val id = recipes.saveDraft(RecipeDraft(title = "Chicken Teriyaki"))

        tools.applyConfirmed(
            KitchenTools.ADD_TO_MEAL_PLAN,
            """{"title":"Chicken Teriyaki","date":"2026-09-16","slot":"DINNER"}""",
        )

        val entry = db.mealPlanDao().getAll().single()
        assertEquals(id, entry.dishId)
        assertEquals("2026-09-16", entry.date)
    }

    @Test
    fun `applyConfirmed add_to_meal_plan with no matching recipe does nothing`() = runTest {
        tools.applyConfirmed(
            KitchenTools.ADD_TO_MEAL_PLAN,
            """{"title":"Never saved","date":"2026-09-16","slot":"DINNER"}""",
        )
        assertTrue(db.mealPlanDao().getAll().isEmpty())
    }

    // ---- add_to_shopping_list --------------------------------------------------------------

    @Test
    fun `applyConfirmed add_to_shopping_list adds every item`() = runTest {
        tools.applyConfirmed(KitchenTools.ADD_TO_SHOPPING_LIST, """{"items":["flour","sugar"]}""")

        assertEquals(setOf("flour", "sugar"), db.shoppingDao().getAll().map { it.name }.toSet())
    }
}
