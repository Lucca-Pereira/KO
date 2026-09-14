package com.lucca.ko

import com.lucca.ko.domain.recipe.RecipeDiff
import com.lucca.ko.domain.recipe.RecipeDiff.Change
import com.lucca.ko.domain.recipe.RecipeDiff.IngredientLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diff is the safeguard on AI edits.
 *
 * A 7B asked to make a recipe dairy-free will sometimes swap the cream and leave the butter, and
 * that is invisible in a one-line summary. If the diff is wrong, the review step is theatre.
 */
class RecipeDiffTest {

    private fun diff(
        beforeIngredients: List<Pair<String, String>> = emptyList(),
        afterIngredients: List<Pair<String, String>> = emptyList(),
        beforeSteps: List<String> = emptyList(),
        afterSteps: List<String> = emptyList(),
        beforeTitle: String = "Pasta",
        afterTitle: String = "Pasta",
        beforeServings: Int = 2,
        afterServings: Int = 2,
    ) = RecipeDiff.compare(
        beforeTitle = beforeTitle,
        afterTitle = afterTitle,
        beforeServings = beforeServings,
        afterServings = afterServings,
        beforeIngredients = beforeIngredients.map { IngredientLine(it.first, it.second) },
        afterIngredients = afterIngredients.map { IngredientLine(it.first, it.second) },
        beforeSteps = beforeSteps,
        afterSteps = afterSteps,
    )

    @Test
    fun `an identical recipe has no changes`() {
        val result = diff(
            beforeIngredients = listOf("cream" to "150 ml", "pasta" to "200 g"),
            afterIngredients = listOf("cream" to "150 ml", "pasta" to "200 g"),
            beforeSteps = listOf("Boil the pasta."),
            afterSteps = listOf("Boil the pasta."),
        )
        assertFalse(result.hasChanges)
        assertNull(result.title)
        assertNull(result.servings)
        assertTrue(result.ingredients.all { it.change == Change.UNCHANGED })
    }

    @Test
    fun `spots a swapped ingredient as one removal and one addition`() {
        val result = diff(
            beforeIngredients = listOf("double cream" to "150 ml", "pasta" to "200 g"),
            afterIngredients = listOf("oat milk" to "150 ml", "pasta" to "200 g"),
        )
        assertTrue(result.hasChanges)
        assertEquals(1, result.addedCount)
        assertEquals(1, result.removedCount)
        assertEquals("150 ml oat milk", result.ingredients.first { it.change == Change.ADDED }.text)
        assertEquals(
            "150 ml double cream",
            result.ingredients.first { it.change == Change.REMOVED }.text,
        )
    }

    @Test
    fun `spots a changed amount and keeps the old one for display`() {
        val result = diff(
            beforeIngredients = listOf("pasta" to "200 g"),
            afterIngredients = listOf("pasta" to "400 g"),
        )
        val line = result.ingredients.single()
        assertEquals(Change.CHANGED, line.change)
        assertEquals("400 g pasta", line.text)
        assertEquals("200 g pasta", line.before)
    }

    @Test
    fun `reordering an ingredient list is not a change`() {
        // Matching by name rather than position: a model that shuffles the list while changing
        // one amount should not look like it rewrote everything.
        val result = diff(
            beforeIngredients = listOf("pasta" to "200 g", "cream" to "150 ml", "salt" to "1 tsp"),
            afterIngredients = listOf("salt" to "1 tsp", "pasta" to "200 g", "cream" to "150 ml"),
        )
        assertFalse(result.hasChanges)
    }

    @Test
    fun `catches the dairy-free case that leaves the butter in`() {
        // The exact failure the review step exists for: cream swapped, butter untouched.
        val result = diff(
            beforeIngredients = listOf(
                "double cream" to "150 ml",
                "butter" to "20 g",
                "parmesan" to "30 g",
            ),
            afterIngredients = listOf(
                "oat milk" to "150 ml",
                "butter" to "20 g",
                "dairy-free parmesan" to "30 g",
            ),
        )
        val surviving = result.ingredients
            .filter { it.change == Change.UNCHANGED }
            .map { it.text }
        assertTrue("butter must show as unchanged, not silently vanish", "20 g butter" in surviving)
        assertEquals(2, result.addedCount)
        assertEquals(2, result.removedCount)
    }

    @Test
    fun `ingredient names are matched case-insensitively`() {
        val result = diff(
            beforeIngredients = listOf("Pasta" to "200 g"),
            afterIngredients = listOf("pasta" to "200 g"),
        )
        assertFalse(result.hasChanges)
    }

    @Test
    fun `steps are matched by position, so a reword is a change not a swap`() {
        val result = diff(
            beforeSteps = listOf("Boil the pasta.", "Fry the mushrooms."),
            afterSteps = listOf("Boil the pasta in salted water.", "Fry the mushrooms."),
        )
        assertEquals(Change.CHANGED, result.steps[0].change)
        assertEquals("Boil the pasta.", result.steps[0].before)
        assertEquals(Change.UNCHANGED, result.steps[1].change)
    }

    @Test
    fun `a shorter method reports the dropped steps`() {
        val result = diff(
            beforeSteps = listOf("One.", "Two.", "Three."),
            afterSteps = listOf("One.", "Two."),
        )
        assertEquals(1, result.steps.count { it.change == Change.REMOVED })
        assertEquals("Three.", result.steps.last().text)
    }

    @Test
    fun `a repeated step is not mistaken for a duplicate`() {
        // "Fry the onions" can legitimately appear twice; position-matching handles that.
        val result = diff(
            beforeSteps = listOf("Fry the onions.", "Add stock.", "Fry the onions."),
            afterSteps = listOf("Fry the onions.", "Add stock.", "Fry the onions."),
        )
        assertFalse(result.hasChanges)
    }

    @Test
    fun `title and servings changes are reported separately`() {
        val result = diff(
            beforeTitle = "Pasta",
            afterTitle = "Vegan pasta",
            beforeServings = 2,
            afterServings = 4,
        )
        assertEquals("Vegan pasta", result.title?.text)
        assertEquals("Pasta", result.title?.before)
        assertEquals("4", result.servings?.text)
        assertEquals("2", result.servings?.before)
        assertTrue(result.hasChanges)
    }

    @Test
    fun `an ingredient with no amount renders as just its name`() {
        val result = diff(afterIngredients = listOf("salt" to ""))
        assertEquals("salt", result.ingredients.single().text)
    }

    @Test
    fun `an empty proposal against an empty recipe is not a change`() {
        assertFalse(diff().hasChanges)
    }
}
