package com.lucca.ko

import com.lucca.ko.data.db.Recipe
import com.lucca.ko.data.db.RecipeIngredient
import com.lucca.ko.data.db.RecipeSource
import com.lucca.ko.data.db.RecipeStep
import com.lucca.ko.data.db.Tag
import com.lucca.ko.data.db.relations.RecipeWithDetails
import com.lucca.ko.domain.recipe.IngredientDraft
import com.lucca.ko.domain.recipe.RecipeDraft
import com.lucca.ko.domain.recipe.StepDraft
import com.lucca.ko.domain.recipe.toDraft
import com.lucca.ko.domain.recipe.toIngredients
import com.lucca.ko.domain.recipe.toRecipe
import com.lucca.ko.domain.recipe.toSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor is the one place where a bug quietly mangles a recipe the user spent time on, so
 * the whole draft <-> entity conversion is pure and tested here rather than living in a
 * ViewModel.
 */
class RecipeDraftTest {

    private val now = 1_700_000_000_000L

    // ---- Loading -------------------------------------------------------------------

    private fun details(
        recipe: Recipe,
        ingredients: List<RecipeIngredient> = emptyList(),
        steps: List<RecipeStep> = emptyList(),
        tags: List<Tag> = emptyList(),
    ) = RecipeWithDetails(recipe, ingredients, steps, tags)

    @Test
    fun `loads every field the editor shows`() {
        val draft = details(
            Recipe(
                id = 7,
                title = "Tortilla",
                servings = 4,
                prepMinutes = 10,
                cookMinutes = 25,
                notes = "Use waxy potatoes",
                sourceUrl = "https://example.com/t",
            ),
            ingredients = listOf(
                RecipeIngredient(id = 2, dishId = 7, rawName = "egg", normalizedName = "egg", measure = "6", sortOrder = 1),
                RecipeIngredient(id = 1, dishId = 7, rawName = "potato", normalizedName = "potato", measure = "500 g", sortOrder = 0),
            ),
            tags = listOf(Tag(1, "Spanish", "spanish")),
        ).toDraft()

        assertEquals(7L, draft.id)
        assertEquals("Tortilla", draft.title)
        assertEquals("4", draft.servingsText)
        assertEquals("10", draft.prepText)
        assertEquals("25", draft.cookText)
        assertEquals("Use waxy potatoes", draft.notes)
        assertEquals("https://example.com/t", draft.sourceUrl)
        assertEquals(listOf("Spanish"), draft.tags)
        // Ingredients arrive in the order the user arranged them, not by id.
        assertEquals(listOf("potato", "egg"), draft.ingredients.map { it.name })
        assertEquals("500 g", draft.ingredients.first().amount)
        assertFalse(draft.isNew)
    }

    @Test
    fun `seeds steps from prose when a recipe has none yet`() {
        // A MealDB import has instructions and no step rows until it is first edited.
        val draft = details(
            Recipe(
                id = 1,
                title = "Imported",
                instructions = "Chop the onions finely. Fry them until golden brown. " +
                    "Season well and serve immediately.",
                source = RecipeSource.MEALDB,
            ),
        ).toDraft()

        assertEquals(3, draft.steps.size)
        assertEquals("Chop the onions finely.", draft.steps.first().text)
        // Keys must be negative so they cannot collide with real row ids.
        assertTrue(draft.steps.all { it.key < 0 })
    }

    @Test
    fun `prefers real steps over prose once they exist`() {
        val draft = details(
            Recipe(id = 1, title = "Edited", instructions = "One. Two. Three."),
            steps = listOf(
                RecipeStep(id = 20, dishId = 1, position = 1, text = "Second"),
                RecipeStep(id = 10, dishId = 1, position = 0, text = "First"),
            ),
        ).toDraft()

        assertEquals(listOf("First", "Second"), draft.steps.map { it.text })
    }

    // ---- Saving --------------------------------------------------------------------

    @Test
    fun `trims and nulls out empty optional fields`() {
        val recipe = RecipeDraft(title = "  Soup  ", sourceUrl = "   ", notes = "  ").toRecipe(now)
        assertEquals("Soup", recipe.title)
        assertNull(recipe.sourceUrl)
        assertNull(recipe.notes)
    }

    @Test
    fun `a new recipe gets its createdAt set, an existing one keeps it`() {
        assertEquals(now, RecipeDraft(title = "New").toRecipe(now).createdAt)
        assertEquals(
            500L,
            RecipeDraft(id = 3, title = "Old", createdAt = 500L).toRecipe(now).createdAt,
        )
        // updatedAt always moves.
        assertEquals(now, RecipeDraft(id = 3, title = "Old", createdAt = 500L).toRecipe(now).updatedAt)
    }

    @Test
    fun `servings falls back to one rather than zero`() {
        assertEquals(1, RecipeDraft(title = "x", servingsText = "").toRecipe(now).servings)
        assertEquals(1, RecipeDraft(title = "x", servingsText = "0").toRecipe(now).servings)
        assertEquals(6, RecipeDraft(title = "x", servingsText = "6").toRecipe(now).servings)
        assertEquals(99, RecipeDraft(title = "x", servingsText = "500").toRecipe(now).servings)
    }

    @Test
    fun `zero minutes is stored as unknown, not as zero`() {
        val recipe = RecipeDraft(title = "x", prepText = "0", cookText = "20").toRecipe(now)
        assertNull(recipe.prepMinutes)
        assertEquals(20, recipe.cookMinutes)
    }

    @Test
    fun `parses ingredient amounts and keeps the original text`() {
        val ingredients = RecipeDraft(
            title = "x",
            ingredients = listOf(
                IngredientDraft(key = -1, name = "Flour", amount = "1 1/2 cups"),
                IngredientDraft(key = -2, name = "Salt", amount = "a pinch"),
                IngredientDraft(key = -3, name = "Love", amount = "to taste"),
            ),
        ).toIngredients(dishId = 4)

        assertEquals(1.5, ingredients[0].quantity)
        assertEquals("cup", ingredients[0].unit)
        assertEquals("1 1/2 cups", ingredients[0].measure)

        assertNull(ingredients[1].quantity)
        assertEquals("pinch", ingredients[1].unit)

        // "to taste" is a direction, not an amount — but the text survives.
        assertNull(ingredients[2].quantity)
        assertNull(ingredients[2].unit)
        assertEquals("to taste", ingredients[2].measure)
    }

    @Test
    fun `drops blank ingredient lines and renumbers the rest`() {
        val ingredients = RecipeDraft(
            title = "x",
            ingredients = listOf(
                IngredientDraft(key = -1, name = "Onion"),
                IngredientDraft(key = -2, name = "   "),
                IngredientDraft(key = -3, name = "Garlic"),
            ),
        ).toIngredients(dishId = 4)

        assertEquals(listOf("Onion", "Garlic"), ingredients.map { it.rawName })
        assertEquals(listOf(0, 1), ingredients.map { it.sortOrder })
        assertTrue(ingredients.all { it.dishId == 4L })
    }

    @Test
    fun `normalises ingredient names for pantry matching`() {
        val ingredients = RecipeDraft(
            title = "x",
            ingredients = listOf(IngredientDraft(key = -1, name = "Orégano")),
        ).toIngredients(dishId = 1)
        assertEquals("oregano", ingredients.single().normalizedName)
    }

    @Test
    fun `drops blank steps and renumbers positions`() {
        val steps = RecipeDraft(
            title = "x",
            steps = listOf(
                StepDraft(key = -1, text = "Preheat"),
                StepDraft(key = -2, text = ""),
                StepDraft(key = -3, text = "Bake", minutesText = "30"),
            ),
        ).toSteps(dishId = 9)

        assertEquals(listOf("Preheat", "Bake"), steps.map { it.text })
        assertEquals(listOf(0, 1), steps.map { it.position })
        assertEquals(30, steps[1].minutes)
        assertNull(steps[0].minutes)
    }

    @Test
    fun `keeps fields the editor never shows`() {
        // Saving must not quietly discard macros, the MealDB link or the cooked history.
        val draft = RecipeDraft(
            id = 5,
            title = "Kept",
            mealdbId = "52772",
            instructions = "original prose",
            timesCooked = 4,
            kcalPerServing = 520.0,
            isFavourite = true,
        )
        val recipe = draft.toRecipe(now)
        assertEquals("52772", recipe.mealdbId)
        assertEquals("original prose", recipe.instructions)
        assertEquals(4, recipe.timesCooked)
        assertEquals(520.0, recipe.kcalPerServing)
        assertTrue(recipe.isFavourite)
    }

    @Test
    fun `a recipe cannot be saved without a name`() {
        assertFalse(RecipeDraft().canSave)
        assertFalse(RecipeDraft(title = "   ").canSave)
        assertTrue(RecipeDraft(title = "Beans").canSave)
    }
}
