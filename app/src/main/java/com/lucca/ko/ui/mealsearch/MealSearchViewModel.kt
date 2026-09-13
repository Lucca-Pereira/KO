package com.lucca.ko.ui.mealsearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.remote.MealSummary
import com.lucca.ko.ui.koFactory
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MealSearchUiState(
    val loading: Boolean = false,
    val searched: Boolean = false,
    val results: List<MealSummary> = emptyList(),
    val error: String? = null,
    val savingId: String? = null,
    val savedDishId: Long? = null,
)

class MealSearchViewModel(
    private val recipes: RecipeRepository,
    private val plan: MealPlanRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MealSearchUiState())
    val state = _state.asStateFlow()

    fun search(query: String) {
        if (query.isBlank()) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { recipes.searchMeals(query) }
                .onSuccess { list -> _state.update { it.copy(loading = false, searched = true, results = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, searched = true, error = e.message ?: "Network error") } }
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
        val Factory = koFactory { MealSearchViewModel(it.recipeRepository, it.mealPlanRepository) }
    }
}
