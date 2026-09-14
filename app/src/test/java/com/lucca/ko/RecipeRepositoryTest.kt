package com.lucca.ko

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lucca.ko.data.db.KoDatabase
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.Recipe
import com.lucca.ko.data.db.RecipeSource
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.remote.MealDbClient
import com.lucca.ko.data.remote.MealDbIngredient
import com.lucca.ko.data.remote.MealDetail
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.domain.recipe.IngredientDraft
import com.lucca.ko.domain.recipe.RecipeDraft
import com.lucca.ko.domain.recipe.StepDraft
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository behaviour against a real in-memory SQLite database rather than fake DAOs.
 *
 * The things worth verifying here *are* database behaviours — `ON DELETE SET NULL`, the unique
 * index on `mealdbId`, wholesale ingredient replacement — and a fake DAO would only test the
 * fake. Room's in-memory builder under Robolectric costs a few milliseconds per test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeRepositoryTest {

    private lateinit var db: KoDatabase
    private lateinit var recipes: RecipeRepository
    private lateinit var plan: MealPlanRepository

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
            mealDb = MealDbClient(OkHttpClient()),
        )
        plan = MealPlanRepository(db.mealPlanDao(), db.recipeDao())
    }

    @After
    fun tearDown() = db.close()

    private fun detail(id: String, title: String) = MealDetail(
        id = id,
        title = title,
        category = null,
        area = null,
        instructions = "Mix it. Cook it. Eat it.",
        thumbUrl = null,
        sourceUrl = "https://example.com/$id",
        youtubeUrl = null,
        ingredients = listOf(
            MealDbIngredient("chicken", "2 lbs"),
            MealDbIngredient("soy sauce", "1/2 cup"),
        ),
    )

    // ---- Library ---------------------------------------------------------------------

    @Test
    fun `importing the same meal twice reuses the recipe`() = runTest {
        val first = recipes.importFromMealDb(detail("52772", "Teriyaki"))
        val second = recipes.importFromMealDb(detail("52772", "Teriyaki"))

        assertEquals(first, second)
        assertEquals(1, db.recipeDao().getAllRecipes().size)
        // And it does not duplicate the ingredients either.
        assertEquals(2, db.recipeDao().ingredientsFor(first).size)
    }

    @Test
    fun `re-importing does not overwrite edits`() = runTest {
        val id = recipes.importFromMealDb(detail("52772", "Teriyaki"))
        recipes.saveDraft(
            RecipeDraft(
                id = id,
                title = "My better teriyaki",
                mealdbId = "52772",
                ingredients = listOf(IngredientDraft(key = -1, name = "chicken thigh")),
            ),
        )

        recipes.importFromMealDb(detail("52772", "Teriyaki"))

        assertEquals("My better teriyaki", recipes.recipeById(id)?.title)
        assertEquals(listOf("chicken thigh"), db.recipeDao().ingredientsFor(id).map { it.rawName })
    }

    @Test
    fun `import parses the measures it is given`() = runTest {
        val id = recipes.importFromMealDb(detail("52772", "Teriyaki"))
        val soy = db.recipeDao().ingredientsFor(id).first { it.rawName == "soy sauce" }
        assertEquals(0.5, soy.quantity)
        assertEquals("cup", soy.unit)
        assertEquals("1/2 cup", soy.measure)
    }

    // ---- Editing ---------------------------------------------------------------------

    @Test
    fun `saving a draft replaces ingredients and steps wholesale`() = runTest {
        val id = recipes.saveDraft(
            RecipeDraft(
                title = "Stew",
                ingredients = listOf(
                    IngredientDraft(key = -1, name = "onion"),
                    IngredientDraft(key = -2, name = "carrot"),
                ),
                steps = listOf(StepDraft(key = -3, text = "Chop everything up")),
            ),
        )
        assertEquals(2, db.recipeDao().ingredientsFor(id).size)

        recipes.saveDraft(
            RecipeDraft(
                id = id,
                title = "Stew",
                ingredients = listOf(IngredientDraft(key = -1, name = "leek")),
                steps = emptyList(),
            ),
        )

        assertEquals(listOf("leek"), db.recipeDao().ingredientsFor(id).map { it.rawName })
        assertTrue(db.recipeDao().stepsFor(id).isEmpty())
    }

    @Test
    fun `saving keeps the ingredient order the user arranged`() = runTest {
        val id = recipes.saveDraft(
            RecipeDraft(
                title = "Ordered",
                ingredients = listOf(
                    IngredientDraft(key = -1, name = "first"),
                    IngredientDraft(key = -2, name = "second"),
                    IngredientDraft(key = -3, name = "third"),
                ),
            ),
        )
        assertEquals(
            listOf("first", "second", "third"),
            db.recipeDao().ingredientsFor(id).map { it.rawName },
        )
    }

    @Test
    fun `the search blob covers title, ingredients and tags`() = runTest {
        val id = recipes.saveDraft(
            RecipeDraft(
                title = "Tortilla",
                ingredients = listOf(IngredientDraft(key = -1, name = "Patatas")),
                tags = listOf("Spanish"),
            ),
        )
        val blob = recipes.recipeById(id)?.searchBlob.orEmpty()
        assertTrue(blob.contains("tortilla"))
        assertTrue(blob.contains("patatas"))
        assertTrue(blob.contains("spanish"))

        assertEquals(1, recipes.searchLibrary("patatas").first().size)
        assertEquals(0, recipes.searchLibrary("mushroom").first().size)
        // A blank query is "everything", not "nothing".
        assertEquals(1, recipes.searchLibrary("").first().size)
    }

    @Test
    fun `tags are reused rather than duplicated, and orphans are swept`() = runTest {
        val a = recipes.saveDraft(RecipeDraft(title = "A", tags = listOf("Quick")))
        recipes.saveDraft(RecipeDraft(title = "B", tags = listOf("quick")))

        // "Quick" and "quick" normalise to one tag row.
        assertEquals(1, db.tagDao().getAllTags().size)

        recipes.saveDraft(RecipeDraft(id = a, title = "A", tags = emptyList()))
        assertEquals(1, db.tagDao().getAllTags().size)

        recipes.setTags(db.recipeDao().getAllRecipes().first { it.id != a }.id, emptyList())
        assertTrue(db.tagDao().getAllTags().isEmpty())
    }

    @Test
    fun `renaming a recipe refreshes the plan's fallback title`() = runTest {
        val id = recipes.saveDraft(RecipeDraft(title = "Old name"))
        plan.addToPlan(id, LocalDate.parse("2026-09-16"), MealSlot.DINNER)

        recipes.saveDraft(RecipeDraft(id = id, title = "New name"))

        assertEquals("New name", db.mealPlanDao().getAll().single().titleSnapshot)
    }

    // ---- The point of the whole phase -------------------------------------------------

    @Test
    fun `one recipe can be planned on many days`() = runTest {
        val id = recipes.importFromMealDb(detail("52772", "Teriyaki"))
        plan.addToPlan(id, LocalDate.parse("2026-09-14"), MealSlot.DINNER)
        plan.addToPlan(id, LocalDate.parse("2026-09-16"), MealSlot.LUNCH)
        plan.addToPlan(id, LocalDate.parse("2026-09-18"), MealSlot.DINNER)

        assertEquals(3, db.mealPlanDao().getAll().size)
        assertEquals(1, db.recipeDao().getAllRecipes().size)
        assertEquals(3, recipes.planCountFor(id))
    }

    @Test
    fun `removing a planned meal leaves the recipe alone`() = runTest {
        val id = recipes.importFromMealDb(detail("52772", "Teriyaki"))
        val entryId = plan.addToPlan(id, LocalDate.parse("2026-09-14"), MealSlot.DINNER)

        plan.removePlanEntry(entryId)

        assertNotNull("the recipe must survive its last plan entry", recipes.recipeById(id))
        assertEquals(2, db.recipeDao().ingredientsFor(id).size)
    }

    @Test
    fun `deleting a recipe leaves the plan entry readable`() = runTest {
        val id = recipes.saveDraft(RecipeDraft(title = "Beef Wellington"))
        plan.addToPlan(id, LocalDate.parse("2026-09-14"), MealSlot.DINNER)

        recipes.deleteRecipe(id)

        val entry = db.mealPlanDao().getAll().single()
        assertNull("ON DELETE SET NULL, not CASCADE", entry.dishId)
        assertEquals("Beef Wellington", entry.titleSnapshot)
    }

    // ---- Duplicates -------------------------------------------------------------------

    @Test
    fun `finds recipes that share a normalised title`() = runTest {
        recipes.saveDraft(RecipeDraft(title = "Pasta"))
        recipes.saveDraft(RecipeDraft(title = "pasta"))
        recipes.saveDraft(RecipeDraft(title = "Risotto"))

        val groups = recipes.findDuplicates()
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().recipes.size)
    }

    @Test
    fun `merging repoints the plan, unions tags and deletes the loser`() = runTest {
        val keep = recipes.saveDraft(RecipeDraft(title = "Pasta", tags = listOf("Quick")))
        val drop = recipes.saveDraft(RecipeDraft(title = "Pasta", tags = listOf("Vegetarian")))
        plan.addToPlan(drop, LocalDate.parse("2026-09-14"), MealSlot.DINNER)
        plan.addToPlan(keep, LocalDate.parse("2026-09-15"), MealSlot.DINNER)

        recipes.mergeRecipes(keepId = keep, dropIds = listOf(drop))

        assertNull(recipes.recipeById(drop))
        assertEquals(2, db.mealPlanDao().getAll().size)
        assertTrue(db.mealPlanDao().getAll().all { it.dishId == keep })
        assertEquals(
            setOf("Quick", "Vegetarian"),
            db.tagDao().tagsFor(keep).map { it.name }.toSet(),
        )
        assertTrue(recipes.findDuplicates().isEmpty())
    }

    // ---- Pantry interplay -------------------------------------------------------------

    @Test
    fun `adding missing ingredients to shopping skips what the pantry has`() = runTest {
        db.pantryDao().upsert(
            PantryItem(name = "Chicken", normalizedName = "chicken", status = StockStatus.IN_STOCK),
        )
        val id = recipes.importFromMealDb(detail("52772", "Teriyaki"))

        recipes.addMissingIngredientsToShopping(id)

        val shopping = db.shoppingDao().getAll().map { it.normalizedName }
        assertEquals(listOf("soy sauce"), shopping)
    }

    @Test
    fun `marking an unknown ingredient as run out teaches the pantry and links it`() = runTest {
        val id = recipes.importFromMealDb(detail("52772", "Teriyaki"))
        val chicken = db.recipeDao().ingredientsFor(id).first { it.rawName == "chicken" }

        recipes.markIngredientRanOut(chicken.id)

        val pantryItem = db.pantryDao().byNormalized("chicken")
        assertNotNull(pantryItem)
        assertEquals(StockStatus.OUT, pantryItem!!.status)
        // The link is remembered, so the next tap resolves without re-matching.
        assertEquals(pantryItem.id, db.recipeDao().ingredientById(chicken.id)?.pantryItemId)
        // And running out puts it on the shopping list.
        assertEquals(listOf("chicken"), db.shoppingDao().getAll().map { it.normalizedName })
    }

    @Test
    fun `a recipe row carries its source`() = runTest {
        val imported = recipes.importFromMealDb(detail("52772", "Teriyaki"))
        val manual = recipes.saveDraft(RecipeDraft(title = "Mine"))
        assertEquals(RecipeSource.MEALDB, recipes.recipeById(imported)?.source)
        assertEquals(RecipeSource.MANUAL, recipes.recipeById(manual)?.source)
    }

    @Test
    fun `cooking a planned meal bumps the recipe's history`() = runTest {
        val id = recipes.saveDraft(RecipeDraft(title = "Chilli"))
        val entryId = plan.addToPlan(id, LocalDate.parse("2026-09-14"), MealSlot.DINNER)

        plan.setCooked(entryId, true)

        val recipe: Recipe = recipes.recipeById(id)!!
        assertEquals(1, recipe.timesCooked)
        assertNotNull(recipe.lastCookedAt)
    }
}
