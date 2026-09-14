package com.lucca.ko.ui.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.repo.DuplicateGroup
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.ui.common.EmptyState
import com.lucca.ko.ui.common.LoadingBox
import com.lucca.ko.ui.koFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DuplicatesUiState(
    val loading: Boolean = true,
    val groups: List<DuplicateGroup> = emptyList(),
    val merging: Boolean = false,
)

class DuplicatesViewModel(private val recipes: RecipeRepository) : ViewModel() {

    private val _state = MutableStateFlow(DuplicatesUiState())
    val state = _state.asStateFlow()

    init { scan() }

    fun scan() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val groups = runCatching { recipes.findDuplicates() }.getOrDefault(emptyList())
            _state.update { it.copy(loading = false, groups = groups) }
        }
    }

    fun merge(keepId: Long, dropIds: List<Long>) {
        _state.update { it.copy(merging = true) }
        viewModelScope.launch {
            runCatching { recipes.mergeRecipes(keepId, dropIds) }
            _state.update { it.copy(merging = false) }
            scan()
        }
    }

    companion object {
        val Factory = koFactory { DuplicatesViewModel(it.recipeRepository) }
    }
}

/**
 * Recipes sharing a title, side by side, so you can decide.
 *
 * MealDB duplicates were collapsed automatically by the 2 -> 3 migration, because a shared
 * `mealdbId` means they are provably the same meal. Titles prove nothing — two homemade "Pasta"
 * recipes may be different dinners — so nothing here merges without an explicit choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    onBack: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
    vm: DuplicatesViewModel = viewModel(factory = DuplicatesViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find duplicates") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))

            state.groups.isEmpty() -> EmptyState(
                title = "No duplicates",
                subtitle = "No two recipes in your library share a name.",
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.groups, key = { it.normalizedTitle }) { group ->
                    DuplicateGroupCard(
                        group = group,
                        enabled = !state.merging,
                        onOpenRecipe = onOpenRecipe,
                        onMerge = { keepId ->
                            vm.merge(keepId, group.recipes.map { it.id }.filterNot { it == keepId })
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    enabled: Boolean,
    onOpenRecipe: (Long) -> Unit,
    onMerge: (Long) -> Unit,
) {
    var keepId by remember(group.normalizedTitle) { mutableStateOf(group.recipes.first().id) }
    var confirming by remember { mutableStateOf(false) }

    if (confirming) {
        val keeper = group.recipes.first { it.id == keepId }
        val losing = group.recipes.size - 1
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Merge into \"${keeper.title}\"?") },
            text = {
                Text(
                    "$losing other cop${if (losing == 1) "y" else "ies"} will be deleted. " +
                        "Any planned meals pointing at them move to this one, and their tags " +
                        "are kept. Ingredients and steps are not merged — the copy you keep is " +
                        "the one that survives, exactly as it is.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onMerge(keepId) }) { Text("Merge") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                group.recipes.first().title.ifBlank { group.normalizedTitle },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${group.recipes.size} copies — pick the one to keep",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            group.recipes.forEach { recipe ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = keepId == recipe.id,
                        onClick = { keepId = recipe.id },
                        enabled = enabled,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(recipe.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            buildList {
                                add(recipe.source.name.lowercase())
                                add("added ${formatDate(recipe.createdAt)}")
                                if (recipe.timesCooked > 0) add("cooked ${recipe.timesCooked}×")
                            }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onOpenRecipe(recipe.id) }) { Text("View") }
                }
            }

            TextButton(
                onClick = { confirming = true },
                enabled = enabled,
                modifier = Modifier.align(Alignment.End),
            ) { Text("Merge") }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun formatDate(epochMillis: Long): String = runCatching {
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateFormat)
}.getOrDefault("—")
