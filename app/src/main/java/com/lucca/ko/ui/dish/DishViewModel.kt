package com.lucca.ko.ui.dish

import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import com.lucca.ko.data.KitchenRepository
import com.lucca.ko.data.db.Dish
import com.lucca.ko.domain.Availability
import com.lucca.ko.domain.PantryResolver
import com.lucca.ko.domain.ResolvedIngredient
import com.lucca.ko.ui.koFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DishUiState(
    val loading: Boolean = true,
    val dish: Dish? = null,
    val ingredients: List<ResolvedIngredient> = emptyList(),
    val missingCount: Int = 0,
)

class DishViewModel(
    private val repo: KitchenRepository,
    private val dishId: Long,
) : ViewModel() {

    val state = combine(repo.dishWithIngredients(dishId), repo.pantry) { dishWith, pantry ->
        if (dishWith == null) {
            DishUiState(loading = false)
        } else {
            val resolved = dishWith.ingredients.map { ing ->
                val (match, availability) = PantryResolver.resolve(
                    ing.normalizedName, ing.pantryItemId, pantry,
                )
                ResolvedIngredient(
                    dishIngredientId = ing.id,
                    rawName = ing.rawName,
                    normalizedName = ing.normalizedName,
                    measure = ing.measure,
                    availability = availability,
                    pantryItem = match,
                )
            }
            DishUiState(
                loading = false,
                dish = dishWith.dish,
                ingredients = resolved,
                missingCount = resolved.count { it.availability == Availability.MISSING },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DishUiState())

    fun markRanOut(ingredientId: Long) = viewModelScope.launch { repo.markIngredientRanOut(ingredientId) }
    fun markHave(ingredientId: Long) = viewModelScope.launch { repo.markIngredientHave(ingredientId) }
    fun addAllMissingToShopping() = viewModelScope.launch { repo.addMissingIngredientsToShopping(dishId) }

    companion object {
        val Factory = koFactory { container ->
            DishViewModel(container.repository, createSavedStateHandle().get<Long>("dishId") ?: 0L)
        }
    }
}
