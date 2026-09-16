package com.lucca.ko

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lucca.ko.data.db.KoDatabase
import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.RecipeSource
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.data.repo.AgentImportRepository
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.data.repo.PantryRepository
import com.lucca.ko.data.repo.RecipeMerge
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.data.repo.RevisionRepository
import com.lucca.ko.data.repo.ShoppingRepository
import com.lucca.ko.domain.recipe.IngredientDraft
import com.lucca.ko.domain.recipe.RecipeDraft
import com.lucca.ko.domain.recipe.toDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The file Claude writes when asked for recipes or pantry changes, applied against real SQLite.
 *
 * What's worth pinning: the import is additive (never wipes anything, unlike the full backup
 * restore), an edit to an existing recipe is undoable exactly like a manual edit, and a plan
 * entry for a recipe that doesn't exist yet is skipped rather than crashing the whole import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentImportRepositoryTest {

    private lateinit var db: KoDatabase
    private lateinit var recipes: RecipeRepository
    private lateinit var revisions: RevisionRepository
    private lateinit var importer: AgentImportRepository

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
        revisions = RevisionRepository(db.revisionDao())
        importer = AgentImportRepository(
            recipeRepository = recipes,
            pantryRepository = PantryRepository(db.pantryDao(), db.shoppingDao()),
            mealPlanRepository = MealPlanRepository(db.mealPlanDao(), db.recipeDao()),
            shoppingRepository = ShoppingRepository(db.shoppingDao(), db.pantryDao()),
            recipeMerge = RecipeMerge(recipes, revisions),
        )
    }

    @After
    fun tearDown() = db.close()

    // ---- Recipes -------------------------------------------------------------------

    @Test
    fun `importing a new recipe saves it from Claude's own knowledge`() = runTest {
        val summary = importer.import(
            """
            {"recipes":[{"title":"Stew","servings":4,
             "ingredients":[{"name":"beef","amount":"500 g"}],
             "steps":[{"text":"Cook it slowly."}]}]}
            """.trimIndent(),
        )

        assertEquals(1, summary.recipesSaved)
        val recipe = db.recipeDao().getAllRecipes().single()
        assertEquals("Stew", recipe.title)
        assertEquals(RecipeSource.AI, recipe.source)
        assertEquals(listOf("beef"), db.recipeDao().ingredientsFor(recipe.id).map { it.rawName })
    }

    @Test
    fun `importing with a recipeId edits it and snapshots for undo`() = runTest {
        val id = recipes.saveDraft(
            RecipeDraft(
                title = "Creamy pasta",
                ingredients = listOf(IngredientDraft(key = -1, name = "double cream", amount = "150 ml")),
            ),
        )
        assertTrue("nothing to undo yet", !revisions.hasUndo(id))

        importer.import(
            """{"recipes":[{"recipeId":$id,"title":"Creamy pasta","servings":2,
                "ingredients":[{"name":"oat milk","amount":"150 ml"}],"steps":[]}]}""",
        )

        assertEquals(
            listOf("oat milk"),
            recipes.observeRecipe(id).first()!!.orderedIngredients.map { it.rawName },
        )
        assertTrue("an edited recipe must be undoable", revisions.hasUndo(id))

        revisions.undoLastChange(id, recipes)
        assertEquals(
            listOf("double cream"),
            recipes.observeRecipe(id).first()!!.orderedIngredients.map { it.rawName },
        )
    }

    @Test
    fun `importing clears stale macros when ingredients change but keeps new ones when given`() = runTest {
        val id = recipes.saveDraft(RecipeDraft(title = "Curry"))
        recipes.saveDraft(recipes.observeRecipe(id).first()!!.toDraft().copy(kcalPerServing = 500.0))

        importer.import(
            """{"recipes":[{"recipeId":$id,"title":"Curry","kcalPerServing":420,
                "proteinG":30,"carbsG":40,"fatG":15,"ingredients":[],"steps":[]}]}""",
        )

        assertEquals(420.0, recipes.recipeById(id)?.kcalPerServing)
    }

    // ---- Pantry ----------------------------------------------------------------------

    @Test
    fun `a pantry update creates an item that doesn't exist yet`() = runTest {
        val summary = importer.import("""{"pantryUpdates":[{"name":"Flour","status":"IN_STOCK"}]}""")

        assertEquals(1, summary.pantryUpdated)
        assertEquals("Flour", db.pantryDao().getAll().single().name)
    }

    @Test
    fun `a pantry update matches an existing item by name rather than duplicating it`() = runTest {
        db.pantryDao().upsert(
            PantryItem(name = "Onion", normalizedName = "onion", status = StockStatus.IN_STOCK),
        )

        importer.import("""{"pantryUpdates":[{"name":"onion","status":"OUT"}]}""")

        assertEquals(1, db.pantryDao().getAll().size)
        assertEquals(StockStatus.OUT, db.pantryDao().getAll().single().status)
    }

    // ---- Shopping ----------------------------------------------------------------------

    @Test
    fun `shopping items are added as-is`() = runTest {
        val summary = importer.import("""{"shoppingItems":["flour","sugar"]}""")

        assertEquals(2, summary.shoppingAdded)
        assertEquals(setOf("flour", "sugar"), db.shoppingDao().getAll().map { it.name }.toSet())
    }

    // ---- Meal plan -------------------------------------------------------------------

    @Test
    fun `a plan entry matches an existing recipe by title`() = runTest {
        val id = recipes.saveDraft(RecipeDraft(title = "Chicken Teriyaki"))

        val summary = importer.import(
            """{"mealPlan":[{"recipeTitle":"Chicken Teriyaki","date":"2026-09-16","slot":"DINNER"}]}""",
        )

        assertEquals(1, summary.planEntriesAdded)
        assertEquals(id, db.mealPlanDao().getAll().single().dishId)
    }

    @Test
    fun `a plan entry for a recipe that doesn't exist is skipped, not fatal`() = runTest {
        val summary = importer.import(
            """{"mealPlan":[{"recipeTitle":"Never saved","date":"2026-09-16","slot":"DINNER"}]}""",
        )

        assertEquals(0, summary.planEntriesAdded)
        assertEquals(1, summary.skipped.size)
        assertTrue(db.mealPlanDao().getAll().isEmpty())
    }

    // ---- Everything together -----------------------------------------------------------

    @Test
    fun `one file can carry recipes, pantry, shopping and plan changes together`() = runTest {
        val summary = importer.import(
            """
            {"recipes":[{"title":"Omelette","ingredients":[{"name":"eggs","amount":"3"}],"steps":[]}],
             "pantryUpdates":[{"name":"Eggs","status":"OUT"}],
             "shoppingItems":["eggs"],
             "mealPlan":[{"recipeTitle":"Omelette","date":"2026-09-16","slot":"BREAKFAST"}]}
            """.trimIndent(),
        )

        assertEquals(1, summary.recipesSaved)
        assertEquals(1, summary.pantryUpdated)
        assertEquals(1, summary.shoppingAdded)
        assertEquals(1, summary.planEntriesAdded)
        assertTrue(summary.skipped.isEmpty())
    }
}
