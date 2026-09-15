package com.lucca.ko.data.repo

import com.lucca.ko.data.db.MacroSource
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.RecipeSource
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.domain.CategoryGuesser
import com.lucca.ko.domain.recipe.IngredientDraft
import com.lucca.ko.domain.recipe.RecipeDraft
import com.lucca.ko.domain.recipe.StepDraft
import com.lucca.ko.domain.recipe.toDraft
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What one round of importing a Claude-written file actually did. */
data class AgentImportSummary(
    val recipesSaved: Int,
    val pantryUpdated: Int,
    val shoppingAdded: Int,
    val planEntriesAdded: Int,
    /** One line per entry that couldn't be applied, e.g. a plan entry for a recipe not in the library. */
    val skipped: List<String>,
) {
    val isEmpty: Boolean
        get() = recipesSaved == 0 && pantryUpdated == 0 && shoppingAdded == 0 && planEntriesAdded == 0
}

/**
 * Reads the small JSON file Claude writes when you ask it for recipes or pantry changes in a
 * chat session, and applies it to the app's own data — additively, never wiping anything the way
 * the full backup restore does.
 *
 * This is the bridge that replaced the in-app Claude API agent: instead of the phone calling
 * Claude directly (and billing per request), you talk to Claude in a session like this one, it
 * hands you this file, and picking "Import" is the confirm step that used to be a review dialog.
 * Editing an existing recipe still snapshots it first, through the same [RevisionRepository] the
 * manual editor uses, so an import that gets something wrong is one tap from undo.
 */
class AgentImportRepository(
    private val recipeRepository: RecipeRepository,
    private val pantryRepository: PantryRepository,
    private val mealPlanRepository: MealPlanRepository,
    private val shoppingRepository: ShoppingRepository,
    private val revisionRepository: RevisionRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun import(text: String): AgentImportSummary {
        val file = json.decodeFromString(AgentImportFile.serializer(), text)
        val skipped = mutableListOf<String>()

        var recipesSaved = 0
        file.recipes.forEach { entry ->
            runCatching { saveRecipe(entry) }
                .onSuccess { recipesSaved++ }
                .onFailure { skipped += "recipe \"${entry.title}\": ${it.message}" }
        }

        var pantryUpdated = 0
        file.pantryUpdates.forEach { entry ->
            runCatching { applyPantryUpdate(entry) }
                .onSuccess { pantryUpdated++ }
                .onFailure { skipped += "pantry \"${entry.name}\": ${it.message}" }
        }

        var shoppingAdded = 0
        file.shoppingItems.forEach { item ->
            runCatching { shoppingRepository.addManualShoppingItem(item) }
                .onSuccess { shoppingAdded++ }
                .onFailure { skipped += "shopping \"$item\": ${it.message}" }
        }

        var planEntriesAdded = 0
        file.mealPlan.forEach { entry ->
            runCatching { addToPlan(entry) }
                .onSuccess { planEntriesAdded++ }
                .onFailure { skipped += "plan \"${entry.recipeTitle}\": ${it.message}" }
        }

        return AgentImportSummary(recipesSaved, pantryUpdated, shoppingAdded, planEntriesAdded, skipped)
    }

    private suspend fun saveRecipe(entry: RecipeImportEntry) {
        require(entry.title.isNotBlank()) { "no title" }
        val existing = entry.recipeId.takeIf { it > 0 }
            ?.let { recipeRepository.observeRecipe(it).first() }
        // Same insurance an accepted chat proposal used to get: snapshot before overwriting.
        existing?.let { revisionRepository.snapshot(entry.recipeId, it, reason = "Claude import") }

        val base = existing?.toDraft()
        val draft = (base ?: RecipeDraft(source = RecipeSource.AI)).copy(
            title = entry.title,
            servingsText = entry.servings.toString(),
            prepText = entry.prepMinutes?.toString().orEmpty(),
            cookText = entry.cookMinutes?.toString().orEmpty(),
            notes = entry.notes.orEmpty(),
            ingredients = entry.ingredients.mapIndexed { i, ing ->
                IngredientDraft(key = -(i + 1L), name = ing.name, amount = ing.amount, optional = ing.optional)
            },
            steps = entry.steps.mapIndexed { i, s ->
                StepDraft(key = -(i + 1L), text = s.text, minutesText = s.minutes?.toString().orEmpty())
            },
            tags = (base?.tags.orEmpty() + entry.tags).distinctBy { it.lowercase() },
            kcalPerServing = entry.kcalPerServing,
            proteinG = entry.proteinG,
            carbsG = entry.carbsG,
            fatG = entry.fatG,
            macroSource = if (entry.kcalPerServing != null) MacroSource.AI else null,
            macroNote = entry.macroNote,
        )
        recipeRepository.saveDraft(draft)
    }

    private suspend fun applyPantryUpdate(entry: PantryUpdateEntry) {
        require(entry.name.isNotBlank()) { "no name" }
        val existing = pantryRepository.snapshot().firstOrNull { it.name.equals(entry.name, ignoreCase = true) }
        val status = entry.status?.let { runCatching { StockStatus.valueOf(it) }.getOrNull() }
            ?: existing?.status ?: StockStatus.IN_STOCK
        pantryRepository.savePantryItem(
            id = existing?.id,
            name = entry.name,
            category = entry.category ?: existing?.category ?: CategoryGuesser.guess(entry.name),
            status = status,
            quantity = entry.quantity ?: existing?.quantity,
            note = entry.note ?: existing?.note,
        )
    }

    private suspend fun addToPlan(entry: MealPlanImportEntry) {
        val date = LocalDate.parse(entry.date)
        val slot = MealSlot.valueOf(entry.slot)
        val matches = recipeRepository.searchLibrary(entry.recipeTitle).first()
        val id = matches.firstOrNull { it.recipe.title.equals(entry.recipeTitle, ignoreCase = true) }?.recipe?.id
            ?: matches.firstOrNull()?.recipe?.id
            ?: error("no recipe called \"${entry.recipeTitle}\" in the library yet")
        mealPlanRepository.addToPlan(id, date, slot, entry.servings ?: 1.0)
    }
}

/*
 * The file shape Claude is asked to write. Every field beyond the required ones is optional, so a
 * file that only sets pantry updates, say, doesn't need an empty `"recipes": []` to be valid.
 */

@Serializable
data class AgentImportFile(
    val recipes: List<RecipeImportEntry> = emptyList(),
    val pantryUpdates: List<PantryUpdateEntry> = emptyList(),
    val shoppingItems: List<String> = emptyList(),
    val mealPlan: List<MealPlanImportEntry> = emptyList(),
)

@Serializable
data class RecipeImportEntry(
    /** Omit or leave 0 to create a new recipe; an existing id replaces that recipe. */
    val recipeId: Long = 0,
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
    val ingredients: List<RecipeImportIngredient> = emptyList(),
    val steps: List<RecipeImportStep> = emptyList(),
)

@Serializable
data class RecipeImportIngredient(val name: String, val amount: String = "", val optional: Boolean = false)

@Serializable
data class RecipeImportStep(val text: String, val minutes: Int? = null)

/** Matched to an existing pantry item by name (case-insensitive); creates one if there's no match. */
@Serializable
data class PantryUpdateEntry(
    val name: String,
    /** IN_STOCK, LOW or OUT. Omit to leave an existing item's status alone. */
    val status: String? = null,
    val category: String? = null,
    val quantity: String? = null,
    val note: String? = null,
)

/** [recipeTitle] must match a recipe already in the library — from this same file's `recipes`, or an earlier one. */
@Serializable
data class MealPlanImportEntry(
    val recipeTitle: String,
    val date: String,
    val slot: String,
    val servings: Double? = null,
)
