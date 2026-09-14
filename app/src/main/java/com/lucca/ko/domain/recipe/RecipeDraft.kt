package com.lucca.ko.domain.recipe

import com.lucca.ko.data.db.MacroSource
import com.lucca.ko.data.db.Recipe
import com.lucca.ko.data.db.RecipeIngredient
import com.lucca.ko.data.db.RecipeSource
import com.lucca.ko.data.db.RecipeStep
import com.lucca.ko.data.db.relations.RecipeWithDetails
import com.lucca.ko.domain.IngredientMatcher
import com.lucca.ko.domain.units.MeasureParser

/**
 * What the editor holds while you are typing: everything about a recipe, as text, before it
 * becomes rows.
 *
 * Pure and unit-tested. The editor is the one place where a bug silently mangles a recipe you
 * spent time on, so the whole draft -> entities conversion lives here rather than in a
 * ViewModel where it cannot be tested.
 */
data class RecipeDraft(
    val id: Long = 0L,
    val title: String = "",
    val sourceUrl: String = "",
    val imageUrl: String? = null,
    val imageLocalPath: String? = null,
    val servingsText: String = "2",
    val prepText: String = "",
    val cookText: String = "",
    val notes: String = "",
    val ingredients: List<IngredientDraft> = emptyList(),
    val steps: List<StepDraft> = emptyList(),
    val tags: List<String> = emptyList(),
    // Carried through untouched so a save never discards what the editor does not show.
    val instructions: String? = null,
    val mealdbId: String? = null,
    val source: RecipeSource = RecipeSource.MANUAL,
    val createdAt: Long = 0L,
    val timesCooked: Int = 0,
    val lastCookedAt: Long? = null,
    val isFavourite: Boolean = false,
    val forkedFromId: Long? = null,
    val kcalPerServing: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val macroSource: MacroSource? = null,
    val macroUpdatedAt: Long? = null,
    val macroNote: String? = null,
) {
    val isNew: Boolean get() = id == 0L

    /** A recipe needs a name and at least one thing in it before saving is worth allowing. */
    val canSave: Boolean get() = title.isNotBlank()

    val servings: Int get() = servingsText.toIntOrNull()?.coerceIn(1, 99) ?: 1
}

/**
 * One ingredient line. [amount] is free text ("200 g", "1 1/2 cups", "a pinch") parsed by
 * [MeasureParser] on save — one field the user can type naturally beats a number box plus a unit
 * dropdown, and the original text is kept either way as `RecipeIngredient.measure`.
 *
 * [key] is a stable identity for Compose and drag-reorder. It is the row id for saved rows and a
 * negative counter for unsaved ones, so the two can never collide.
 */
data class IngredientDraft(
    val key: Long,
    val name: String = "",
    val amount: String = "",
    val optional: Boolean = false,
    val section: String? = null,
    val note: String? = null,
    val pantryItemId: Long? = null,
) {
    val isBlank: Boolean get() = name.isBlank()

    /** What the parser makes of [amount], for the hint under the field. */
    val parsed get() = MeasureParser.parse(amount)
}

data class StepDraft(
    val key: Long,
    val text: String = "",
    val minutesText: String = "",
) {
    val isBlank: Boolean get() = text.isBlank()
    val minutes: Int? get() = minutesText.toIntOrNull()?.takeIf { it > 0 }
}

// ---- Loading ------------------------------------------------------------------------

fun RecipeWithDetails.toDraft(): RecipeDraft = RecipeDraft(
    id = recipe.id,
    title = recipe.title,
    sourceUrl = recipe.sourceUrl.orEmpty(),
    imageUrl = recipe.imageUrl,
    imageLocalPath = recipe.imageLocalPath,
    servingsText = recipe.servings.toString(),
    prepText = recipe.prepMinutes?.toString().orEmpty(),
    cookText = recipe.cookMinutes?.toString().orEmpty(),
    notes = recipe.notes.orEmpty(),
    ingredients = orderedIngredients.map { it.toDraft() },
    // A MealDB import has prose instructions and no step rows yet; split them on first edit so
    // the editor has something to work with. `instructions` itself is never rewritten.
    steps = orderedSteps.takeIf { it.isNotEmpty() }?.map { it.toDraft() }
        ?: InstructionSplitter.split(recipe.instructions)
            .mapIndexed { i, text -> StepDraft(key = -(i + 1L), text = text) },
    tags = tags.map { it.name },
    instructions = recipe.instructions,
    mealdbId = recipe.mealdbId,
    source = recipe.source,
    createdAt = recipe.createdAt,
    timesCooked = recipe.timesCooked,
    lastCookedAt = recipe.lastCookedAt,
    isFavourite = recipe.isFavourite,
    forkedFromId = recipe.forkedFromId,
    kcalPerServing = recipe.kcalPerServing,
    proteinG = recipe.proteinG,
    carbsG = recipe.carbsG,
    fatG = recipe.fatG,
    macroSource = recipe.macroSource,
    macroUpdatedAt = recipe.macroUpdatedAt,
    macroNote = recipe.macroNote,
)

private fun RecipeIngredient.toDraft() = IngredientDraft(
    key = id,
    name = rawName,
    amount = measure.orEmpty(),
    optional = optional,
    section = section,
    note = note,
    pantryItemId = pantryItemId,
)

private fun RecipeStep.toDraft() = StepDraft(
    key = id,
    text = text,
    minutesText = minutes?.toString().orEmpty(),
)

// ---- Saving -------------------------------------------------------------------------

fun RecipeDraft.toRecipe(now: Long): Recipe = Recipe(
    id = id,
    title = title.trim(),
    sourceUrl = sourceUrl.trim().ifEmpty { null },
    imageUrl = imageUrl,
    mealdbId = mealdbId,
    instructions = instructions,
    createdAt = if (createdAt == 0L) now else createdAt,
    imageLocalPath = imageLocalPath,
    servings = servings,
    prepMinutes = prepText.toIntOrNull()?.takeIf { it > 0 },
    cookMinutes = cookText.toIntOrNull()?.takeIf { it > 0 },
    notes = notes.trim().ifEmpty { null },
    isFavourite = isFavourite,
    updatedAt = now,
    lastCookedAt = lastCookedAt,
    timesCooked = timesCooked,
    source = source,
    forkedFromId = forkedFromId,
    kcalPerServing = kcalPerServing,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    macroSource = macroSource,
    macroUpdatedAt = macroUpdatedAt,
    macroNote = macroNote,
)

/** Blank lines are dropped rather than saved as empty rows. */
fun RecipeDraft.toIngredients(dishId: Long): List<RecipeIngredient> =
    ingredients.filterNot { it.isBlank }.mapIndexed { index, draft ->
        val parsed = MeasureParser.parse(draft.amount)
        RecipeIngredient(
            dishId = dishId,
            rawName = draft.name.trim(),
            normalizedName = IngredientMatcher.normalize(draft.name),
            measure = draft.amount.trim().ifEmpty { null },
            pantryItemId = draft.pantryItemId,
            quantity = parsed?.quantity,
            unit = parsed?.unit,
            sortOrder = index,
            optional = draft.optional,
            section = draft.section?.trim()?.ifEmpty { null },
            note = draft.note?.trim()?.ifEmpty { null },
        )
    }

fun RecipeDraft.toSteps(dishId: Long): List<RecipeStep> =
    steps.filterNot { it.isBlank }.mapIndexed { index, draft ->
        RecipeStep(
            dishId = dishId,
            position = index,
            text = draft.text.trim(),
            minutes = draft.minutes,
        )
    }
