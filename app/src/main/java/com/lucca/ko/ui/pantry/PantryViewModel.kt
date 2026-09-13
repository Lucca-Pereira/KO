package com.lucca.ko.ui.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucca.ko.data.KitchenRepository
import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.ui.koFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PantryUiState(
    val query: String = "",
    val sections: List<PantrySection> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
)

data class PantrySection(val category: String, val items: List<PantryItem>)

class PantryViewModel(private val repo: KitchenRepository) : ViewModel() {

    private val query = MutableStateFlow("")

    val state = combine(repo.pantry, query) { items, q ->
        val filtered = if (q.isBlank()) items
        else items.filter { it.name.contains(q.trim(), ignoreCase = true) }
        val sections = filtered
            .groupBy { it.category.ifBlank { "Other" } }
            .toSortedMap()
            .map { (cat, list) -> PantrySection(cat, list.sortedBy { it.name.lowercase() }) }
        PantryUiState(query = q, sections = sections, total = items.size, loading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PantryUiState())

    fun setQuery(value: String) { query.value = value }

    fun cycleStatus(item: PantryItem) = viewModelScope.launch { repo.cyclePantryStatus(item) }

    fun setStatus(item: PantryItem, status: StockStatus) =
        viewModelScope.launch { repo.setPantryStatus(item, status) }

    fun delete(id: Long) = viewModelScope.launch { repo.deletePantryItem(id) }

    fun save(
        id: Long?,
        name: String,
        category: String,
        status: StockStatus,
        quantity: String?,
        note: String?,
    ) = viewModelScope.launch {
        repo.savePantryItem(id, name, category, status, quantity, note)
    }

    companion object {
        val Factory = koFactory { PantryViewModel(it.repository) }
    }
}
