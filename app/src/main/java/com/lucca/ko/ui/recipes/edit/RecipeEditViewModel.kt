package com.lucca.ko.ui.recipes.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.data.repo.RevisionRepository
import com.lucca.ko.domain.recipe.IngredientDraft
import com.lucca.ko.domain.recipe.RecipeDraft
import com.lucca.ko.domain.recipe.StepDraft
import com.lucca.ko.domain.recipe.toDraft
import com.lucca.ko.ui.koFactory
import com.lucca.ko.ui.nav.RecipeEditRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecipeEditUiState(
    val loading: Boolean = true,
    val draft: RecipeDraft = RecipeDraft(),
    val saving: Boolean = false,
    val savedId: Long? = null,
    val error: String? = null,
    val knownTags: List<String> = emptyList(),
) {
    val isNew: Boolean get() = draft.isNew
    val canSave: Boolean get() = draft.canSave && !saving
}

class RecipeEditViewModel(
    private val recipes: RecipeRepository,
    private val revisions: RevisionRepository,
    private val recipeId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(RecipeEditUiState())
    val state = _state.asStateFlow()

    /**
     * Keys for rows that do not exist in the database yet. Negative so they can never collide
     * with a real row id, which is what lets Compose keep focus in the right field while you
     * add and remove lines.
     */
    private var nextTempKey = -1L

    private fun tempKey(): Long = nextTempKey--

    init {
        viewModelScope.launch {
            val known = runCatching { recipes.tags.first().map { it.name } }.getOrDefault(emptyList())
            if (recipeId == 0L) {
                _state.update {
                    it.copy(
                        loading = false,
                        knownTags = known,
                        // One empty line of each, so the form is usable without hunting for "+".
                        draft = RecipeDraft(
                            ingredients = listOf(IngredientDraft(key = tempKey())),
                            steps = listOf(StepDraft(key = tempKey())),
                        ),
                    )
                }
            } else {
                val details = recipes.observeRecipe(recipeId).first()
                if (details == null) {
                    _state.update { it.copy(loading = false, error = "That recipe no longer exists.") }
                } else {
                    val draft = details.toDraft()
                    // A MealDB import arrives with prose split into steps whose keys are already
                    // negative; keep the counter clear of them.
                    nextTempKey = minOf(nextTempKey, (draft.steps.minOfOrNull { it.key } ?: 0L) - 1)
                    _state.update { it.copy(loading = false, draft = draft, knownTags = known) }
                }
            }
        }
    }

    private fun edit(block: (RecipeDraft) -> RecipeDraft) {
        _state.update { it.copy(draft = block(it.draft), error = null) }
    }

    // ---- Metadata ------------------------------------------------------------------

    fun setTitle(value: String) = edit { it.copy(title = value) }
    fun setSourceUrl(value: String) = edit { it.copy(sourceUrl = value) }
    fun setServings(value: String) = edit { it.copy(servingsText = value.filter(Char::isDigit)) }
    fun setPrep(value: String) = edit { it.copy(prepText = value.filter(Char::isDigit)) }
    fun setCook(value: String) = edit { it.copy(cookText = value.filter(Char::isDigit)) }
    fun setNotes(value: String) = edit { it.copy(notes = value) }
    fun setPhoto(path: String?) = edit { it.copy(imageLocalPath = path) }

    // ---- Ingredients ---------------------------------------------------------------

    fun addIngredient() = edit { it.copy(ingredients = it.ingredients + IngredientDraft(tempKey())) }

    fun updateIngredient(key: Long, block: (IngredientDraft) -> IngredientDraft) = edit { draft ->
        draft.copy(ingredients = draft.ingredients.map { if (it.key == key) block(it) else it })
    }

    fun removeIngredient(key: Long) = edit { draft ->
        draft.copy(ingredients = draft.ingredients.filterNot { it.key == key })
    }

    fun moveIngredient(key: Long, by: Int) = edit { draft ->
        draft.copy(ingredients = draft.ingredients.moved(by) { it.key == key })
    }

    // ---- Steps ---------------------------------------------------------------------

    fun addStep() = edit { it.copy(steps = it.steps + StepDraft(tempKey())) }

    fun updateStep(key: Long, block: (StepDraft) -> StepDraft) = edit { draft ->
        draft.copy(steps = draft.steps.map { if (it.key == key) block(it) else it })
    }

    fun removeStep(key: Long) = edit { draft ->
        draft.copy(steps = draft.steps.filterNot { it.key == key })
    }

    fun moveStep(key: Long, by: Int) = edit { draft ->
        draft.copy(steps = draft.steps.moved(by) { it.key == key })
    }

    // ---- Tags ----------------------------------------------------------------------

    fun addTag(name: String) = edit { draft ->
        val clean = name.trim()
        if (clean.isEmpty() || draft.tags.any { it.equals(clean, ignoreCase = true) }) {
            draft
        } else {
            draft.copy(tags = draft.tags + clean)
        }
    }

    fun removeTag(name: String) = edit { draft ->
        draft.copy(tags = draft.tags.filterNot { it.equals(name, ignoreCase = true) })
    }

    // ---- Saving --------------------------------------------------------------------

    fun save() {
        val draft = _state.value.draft
        if (!draft.canSave) {
            _state.update { it.copy(error = "Give the recipe a name first.") }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                // Snapshot before overwriting, so a save you regret is one tap from undo — the
                // same safety net an accepted AI edit gets.
                if (!draft.isNew) {
                    recipes.observeRecipe(draft.id).first()?.let {
                        revisions.snapshot(draft.id, it, reason = "manual edit")
                    }
                }
                recipes.saveDraft(draft)
            }
                .onSuccess { id -> _state.update { it.copy(saving = false, savedId = id) } }
                .onFailure { e ->
                    _state.update {
                        it.copy(saving = false, error = e.message ?: "Couldn't save the recipe.")
                    }
                }
        }
    }

    companion object {
        val Factory = koFactory { container ->
            RecipeEditViewModel(
                recipes = container.recipeRepository,
                revisions = container.revisionRepository,
                recipeId = createSavedStateHandle().recipeId(),
            )
        }

        private fun SavedStateHandle.recipeId(): Long =
            runCatching { toRoute<RecipeEditRoute>().recipeId }.getOrDefault(0L)
    }
}

/** Moves the single element matching [predicate] by [by] places, clamped to the list. */
private inline fun <T> List<T>.moved(by: Int, predicate: (T) -> Boolean): List<T> {
    val from = indexOfFirst(predicate)
    if (from < 0) return this
    val to = (from + by).coerceIn(0, lastIndex)
    if (to == from) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
