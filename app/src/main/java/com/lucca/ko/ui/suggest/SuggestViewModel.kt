package com.lucca.ko.ui.suggest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucca.ko.data.KitchenRepository
import com.lucca.ko.data.SuggestionResult
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

class SuggestViewModel(private val repo: KitchenRepository) : ViewModel() {

    private val _state = MutableStateFlow(SuggestUiState())
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.suggestDishes() }
                .onSuccess { r -> _state.update { it.copy(loading = false, result = r) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Something went wrong") } }
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
        val Factory = koFactory { SuggestViewModel(it.repository) }
    }
}
