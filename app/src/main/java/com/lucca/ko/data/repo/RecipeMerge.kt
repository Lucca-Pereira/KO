package com.lucca.ko.data.repo

import com.lucca.ko.data.db.MacroSource
import com.lucca.ko.data.db.RecipeSource
import com.lucca.ko.domain.recipe.IngredientDraft
import com.lucca.ko.domain.recipe.RecipeDraft
import com.lucca.ko.domain.recipe.StepDraft
import com.lucca.ko.domain.recipe.toDraft
import kotlinx.coroutines.flow.first

/** One ingredient/step line as text, independent of where it came from (a file or the NAS). */
data class RecipeFieldIngredient(val name: String, val amount: String = "", val optional: Boolean = false)
data class RecipeFieldStep(val text: String, val minutes: Int? = null)

/** Everything about a recipe an external write (file import or sync pull) can set. */
data class RecipeFields(
    val title: String,
    val servings: Int = 2,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    val kcalPerServing: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val macroNote: String? = null,
    val ingredients: List<RecipeFieldIngredient> = emptyList(),
    val steps: List<RecipeFieldStep> = emptyList(),
)

/**
 * The recipe write shared by [AgentImportRepository]'s file-import path and [SyncRepository]'s
 * NAS pull: build a draft from [RecipeFields], snapshot the old version first if one exists (same
 * undo safety net the manual editor gets), and save. The two callers differ only in how they find
 * "existing" — a local id from the file, a `remoteId` lookup for sync — which is why that lookup
 * stays with each caller rather than living here.
 */
class RecipeMerge(
    private val recipeRepository: RecipeRepository,
    private val revisionRepository: RevisionRepository,
) {
    suspend fun apply(existingId: Long?, fields: RecipeFields, source: RecipeSource, reason: String): Long {
        val existing = existingId?.let { recipeRepository.observeRecipe(it).first() }
        if (existingId != null && existing != null) {
            revisionRepository.snapshot(existingId, existing, reason = reason)
        }

        val base = existing?.toDraft()
        val draft = (base ?: RecipeDraft(source = source)).copy(
            title = fields.title,
            servingsText = fields.servings.toString(),
            prepText = fields.prepMinutes?.toString().orEmpty(),
            cookText = fields.cookMinutes?.toString().orEmpty(),
            notes = fields.notes.orEmpty(),
            ingredients = fields.ingredients.mapIndexed { i, ing ->
                IngredientDraft(key = -(i + 1L), name = ing.name, amount = ing.amount, optional = ing.optional)
            },
            steps = fields.steps.mapIndexed { i, s ->
                StepDraft(key = -(i + 1L), text = s.text, minutesText = s.minutes?.toString().orEmpty())
            },
            tags = (base?.tags.orEmpty() + fields.tags).distinctBy { it.lowercase() },
            kcalPerServing = fields.kcalPerServing,
            proteinG = fields.proteinG,
            carbsG = fields.carbsG,
            fatG = fields.fatG,
            macroSource = if (fields.kcalPerServing != null) MacroSource.AI else null,
            macroNote = fields.macroNote,
        )
        return recipeRepository.saveDraft(draft)
    }
}
