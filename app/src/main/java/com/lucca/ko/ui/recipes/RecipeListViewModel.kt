package com.lucca.ko.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucca.ko.data.db.relations.RecipeWithDetails
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.ui.koFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecipeListUiState(
    val query: String = "",
    val activeTag: String? = null,
    val favouritesOnly: Boolean = false,
    val recipes: List<RecipeWithDetails> = emptyList(),
    val allTags: List<String> = emptyList(),
    val totalCount: Int = 0,
    val loading: Boolean = true,
) {
    val isFiltered: Boolean get() = query.isNotBlank() || activeTag != null || favouritesOnly
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeListViewModel(private val recipes: RecipeRepository) : ViewModel() {

    private val query = MutableStateFlow("")
    private val activeTag = MutableStateFlow<String?>(null)
    private val favouritesOnly = MutableStateFlow(false)

    val state = combine(
        query.flatMapLatest { recipes.searchLibrary(it) },
        query,
        activeTag,
        favouritesOnly,
        recipes.tags,
    ) { found, q, tag, favesOnly, tags ->
        // Search is a database LIKE on the blob; tag and favourite filters are in memory, which
        // for a library of a few hundred recipes is free and keeps the query simple.
        val filtered = found
            .filter { tag == null || it.tags.any { t -> t.name.equals(tag, ignoreCase = true) } }
            .filter { !favesOnly || it.recipe.isFavourite }
        RecipeListUiState(
            query = q,
            activeTag = tag,
            favouritesOnly = favesOnly,
            recipes = filtered,
            allTags = tags.map { it.name },
            totalCount = found.size,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipeListUiState())

    fun setQuery(value: String) { query.value = value }

    fun toggleTag(tag: String) {
        activeTag.value = if (activeTag.value == tag) null else tag
    }

    fun toggleFavouritesOnly() { favouritesOnly.value = !favouritesOnly.value }

    fun clearFilters() {
        query.value = ""
        activeTag.value = null
        favouritesOnly.value = false
    }

    fun setFavourite(id: Long, favourite: Boolean) =
        viewModelScope.launch { recipes.setFavourite(id, favourite) }

    companion object {
        val Factory = koFactory { RecipeListViewModel(it.recipeRepository) }
    }
}
