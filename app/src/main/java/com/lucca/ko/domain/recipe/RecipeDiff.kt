package com.lucca.ko.domain.recipe

/**
 * What an AI proposal would actually change.
 *
 * The model returns a whole replacement recipe rather than a list of edits — small models rewrite
 * a short recipe reliably and do not reliably emit surgical operations against ids they cannot
 * see. That puts the burden here: without a diff, accepting a proposal is a leap of faith, and
 * the interesting failure ("made it dairy-free" but left the butter in) is exactly the one you
 * would not notice.
 *
 * Pure and unit-tested.
 */
object RecipeDiff {

    enum class Change { ADDED, REMOVED, CHANGED, UNCHANGED }

    data class Line(
        val change: Change,
        val text: String,
        /** What it was before, for a [Change.CHANGED] line. */
        val before: String? = null,
    )

    data class Result(
        val title: Line?,
        val servings: Line?,
        val ingredients: List<Line>,
        val steps: List<Line>,
    ) {
        val hasChanges: Boolean
            get() = title != null ||
                servings != null ||
                ingredients.any { it.change != Change.UNCHANGED } ||
                steps.any { it.change != Change.UNCHANGED }

        val addedCount: Int get() = ingredients.count { it.change == Change.ADDED }
        val removedCount: Int get() = ingredients.count { it.change == Change.REMOVED }
        val changedCount: Int get() = ingredients.count { it.change == Change.CHANGED }
    }

    /** One ingredient, as the diff sees it: a name and an amount. */
    data class IngredientLine(val name: String, val amount: String) {
        fun render(): String = listOf(amount.trim(), name.trim()).filter { it.isNotEmpty() }.joinToString(" ")

        val key: String get() = name.trim().lowercase()
    }

    fun compare(
        beforeTitle: String,
        afterTitle: String,
        beforeServings: Int,
        afterServings: Int,
        beforeIngredients: List<IngredientLine>,
        afterIngredients: List<IngredientLine>,
        beforeSteps: List<String>,
        afterSteps: List<String>,
    ): Result = Result(
        title = if (beforeTitle.trim() != afterTitle.trim()) {
            Line(Change.CHANGED, afterTitle.trim(), beforeTitle.trim())
        } else {
            null
        },
        servings = if (beforeServings != afterServings) {
            Line(Change.CHANGED, afterServings.toString(), beforeServings.toString())
        } else {
            null
        },
        ingredients = diffIngredients(beforeIngredients, afterIngredients),
        steps = diffSteps(beforeSteps, afterSteps),
    )

    /**
     * Ingredients are matched by name, not by position.
     *
     * A model that reorders the list while changing one amount would otherwise look like it
     * rewrote everything, which buries the actual change.
     */
    private fun diffIngredients(
        before: List<IngredientLine>,
        after: List<IngredientLine>,
    ): List<Line> {
        val beforeByKey = before.associateBy { it.key }
        val afterKeys = after.map { it.key }.toSet()

        val lines = after.map { line ->
            val old = beforeByKey[line.key]
            when {
                old == null -> Line(Change.ADDED, line.render())
                old.amount.trim().equals(line.amount.trim(), ignoreCase = true) ->
                    Line(Change.UNCHANGED, line.render())
                else -> Line(Change.CHANGED, line.render(), old.render())
            }
        }

        // Removals are listed after the surviving lines: what is gone matters, but what the
        // recipe now *is* should read top to bottom without gaps in it.
        val removed = before.filterNot { it.key in afterKeys }
            .map { Line(Change.REMOVED, it.render()) }

        return lines + removed
    }

    /**
     * Steps are matched by position, not by text.
     *
     * Method steps have no stable identity — "fry the onions" can legitimately appear twice, and
     * rewording step three is a change to step three rather than a deletion and an insertion.
     */
    private fun diffSteps(before: List<String>, after: List<String>): List<Line> {
        val lines = after.mapIndexed { index, text ->
            val old = before.getOrNull(index)
            when {
                old == null -> Line(Change.ADDED, text.trim())
                old.trim().equals(text.trim(), ignoreCase = true) -> Line(Change.UNCHANGED, text.trim())
                else -> Line(Change.CHANGED, text.trim(), old.trim())
            }
        }
        val removed = before.drop(after.size).map { Line(Change.REMOVED, it.trim()) }
        return lines + removed
    }
}
