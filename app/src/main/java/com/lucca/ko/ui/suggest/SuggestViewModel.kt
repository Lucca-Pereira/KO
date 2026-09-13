package com.lucca.ko.ui.suggest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.data.repo.SuggestionRepository
import com.lucca.ko.data.repo.SuggestionResult
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.ui.koFactory
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SuggestUiState(
    val loading: Boolean = true,
    val result: SuggestionResult? = null,
    val error: String? = null,
    val savingId: String? = null,
    val savedDishId: Long? = null,
)

class SuggestViewModel(
    private val suggestions: SuggestionRepository,
    private val recipes: RecipeRepository,
    private val plan: MealPlanRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SuggestUiState())
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { suggestions.suggestDishes() }
                .onSuccess { r -> _state.update { it.copy(loading = false, result = r) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Something went wrong") } }
        }
    }

    fun pick(mealId: String, date: String, slot: String) {
        _state.update { it.copy(savingId = mealId, error = null) }
        viewModelScope.launch {
            runCatching {
                val detail = recipes.mealDetail(mealId) ?: error("Recipe details unavailable")
                // Two steps now: the recipe lands in the library, the plan gets a reference to
                // it. Importing the same meal twice reuses the existing recipe.
                val recipeId = recipes.importFromMealDb(detail)
                plan.addToPlan(
                    recipeId = recipeId,
                    date = LocalDate.parse(date),
                    slot = runCatching { MealSlot.valueOf(slot) }.getOrDefault(MealSlot.DINNER),
                )
                recipeId
            }.onSuccess { dishId -> _state.update { it.copy(savingId = null, savedDishId = dishId) } }
                .onFailure { e -> _state.update { it.copy(savingId = null, error = e.message ?: "Could not save") } }
        }
    }

    companion object {
        val Factory = koFactory {
            SuggestViewModel(it.suggestionRepository, it.recipeRepository, it.mealPlanRepository)
        }
    }
}
