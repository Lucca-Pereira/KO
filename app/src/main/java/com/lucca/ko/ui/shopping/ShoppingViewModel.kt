package com.lucca.ko.ui.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lucca.ko.data.KitchenRepository
import com.lucca.ko.data.db.ShoppingListItem
import com.lucca.ko.ui.koApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShoppingUiState(
    val sections: List<ShoppingSection> = emptyList(),
    val checked: List<ShoppingListItem> = emptyList(),
    val loading: Boolean = true,
)

data class ShoppingSection(val category: String, val items: List<ShoppingListItem>)

class ShoppingViewModel(private val repo: KitchenRepository) : ViewModel() {

    val state = repo.shoppingItems.map { items ->
        val (checked, pending) = items.partition { it.checked }
        val sections = pending
            .groupBy { it.category.ifBlank { "Other" } }
            .toSortedMap()
            .map { (cat, list) -> ShoppingSection(cat, list.sortedBy { it.name.lowercase() }) }
        ShoppingUiState(
            sections = sections,
            checked = checked.sortedBy { it.name.lowercase() },
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShoppingUiState())

    fun add(name: String) = viewModelScope.launch { repo.addManualShoppingItem(name) }

    fun toggle(item: ShoppingListItem) =
        viewModelScope.launch { repo.setShoppingChecked(item, !item.checked) }

    fun delete(id: Long) = viewModelScope.launch { repo.deleteShoppingItem(id) }

    fun clearChecked() = viewModelScope.launch { repo.clearCheckedShopping() }

    companion object {
        val Factory = viewModelFactory {
            initializer { ShoppingViewModel(koApp.container.repository) }
        }
    }
}
