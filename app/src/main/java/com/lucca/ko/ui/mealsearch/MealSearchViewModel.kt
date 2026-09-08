package com.lucca.ko.ui.mealsearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lucca.ko.data.KitchenRepository
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.remote.MealSummary
import com.lucca.ko.ui.koApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class MealSearchUiState(
    val loading: Boolean = false,
    val searched: Boolean = false,
    val results: List<MealSummary> = emptyList(),
    val error: String? = null,
    val savingId: String? = null,
    val savedDishId: Long? = null,
)

class MealSearchViewModel(private val repo: KitchenRepository) : ViewModel() {

    private val _state = MutableStateFlow(MealSearchUiState())
    val state = _state.asStateFlow()

    fun search(query: String) {
        if (query.isBlank()) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.searchMeals(query) }
                .onSuccess { list -> _state.update { it.copy(loading = false, searched = true, results = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, searched = true, error = e.message ?: "Network error") } }
        }
    }

    fun pick(mealId: String, date: String, slot: String) {
        _state.update { it.copy(savingId = mealId, error = null) }
        viewModelScope.launch {
            runCatching {
                val detail = repo.mealDetail(mealId) ?: error("Recipe details unavailable")
                repo.saveMealFromDetail(
                    detail = detail,
                    date = LocalDate.parse(date),
                    slot = runCatching { MealSlot.valueOf(slot) }.getOrDefault(MealSlot.DINNER),
                )
            }.onSuccess { dishId -> _state.update { it.copy(savingId = null, savedDishId = dishId) } }
                .onFailure { e -> _state.update { it.copy(savingId = null, error = e.message ?: "Could not save") } }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { MealSearchViewModel(koApp.container.repository) }
        }
    }
}
