package com.lucca.ko.ui.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.db.relations.RecipeWithDetails
import com.lucca.ko.data.images.RecipeImages
import com.lucca.ko.ui.common.EmptyState
import com.lucca.ko.ui.common.LoadingBox
import com.lucca.ko.ui.common.RecipeMetaLine
import com.lucca.ko.ui.common.RecipeThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeListScreen(
    onOpenRecipe: (Long) -> Unit,
    onNewRecipe: () -> Unit,
    onSearchMealDb: () -> Unit,
    onFindDuplicates: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: RecipeListViewModel = viewModel(factory = RecipeListViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recipes") },
                actions = {
                    IconButton(onClick = vm::toggleFavouritesOnly) {
                        Icon(
                            if (state.favouritesOnly) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (state.favouritesOnly) {
                                "Show all recipes"
                            } else {
                                "Show favourites only"
                            },
                            tint = if (state.favouritesOnly) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Search TheMealDB") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            onClick = { menuOpen = false; onSearchMealDb() },
                        )
                        DropdownMenuItem(
                            text = { Text("Find duplicates") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                            onClick = { menuOpen = false; onFindDuplicates() },
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            onClick = { menuOpen = false; onOpenSettings() },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewRecipe) {
                Icon(Icons.Filled.Add, contentDescription = "New recipe")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search title, ingredient or tag") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (state.allTags.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.allTags.forEach { tag ->
                        FilterChip(
                            selected = state.activeTag == tag,
                            onClick = { vm.toggleTag(tag) },
                            label = { Text(tag) },
                        )
                    }
                }
            }

            when {
                state.loading -> LoadingBox()

                state.recipes.isEmpty() && state.isFiltered -> Column {
                    EmptyState(
                        title = "Nothing matches",
                        subtitle = "Try a different search, or clear the filters.",
                    )
                    TextButton(
                        onClick = vm::clearFilters,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("Clear filters") }
                }

                state.recipes.isEmpty() -> EmptyState(
                    title = "No recipes yet",
                    subtitle = "Add one by hand with +, or search TheMealDB from the menu. " +
                        "Recipes you save stay here, ready to plan on any day.",
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
                ) {
                    items(state.recipes, key = { it.recipe.id }) { item ->
                        RecipeRow(
                            item = item,
                            onClick = { onOpenRecipe(item.recipe.id) },
                            onToggleFavourite = {
                                vm.setFavourite(item.recipe.id, !item.recipe.isFavourite)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeRow(
    item: RecipeWithDetails,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val recipe = item.recipe
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RecipeThumbnail(
            // A photo the user took wins over whatever the source supplied.
            model = RecipeImages.asFile(recipe.imageLocalPath) ?: recipe.imageUrl,
            size = 56,
        )
        Column(Modifier.weight(1f)) {
            Text(
                recipe.title.ifBlank { "Untitled recipe" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            RecipeMetaLine(
                totalMinutes = listOfNotNull(recipe.prepMinutes, recipe.cookMinutes)
                    .takeIf { it.isNotEmpty() }?.sum(),
                servings = recipe.servings,
                timesCooked = recipe.timesCooked,
            )
            if (item.tags.isNotEmpty()) {
                Text(
                    item.tags.joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = onToggleFavourite) {
            Icon(
                if (recipe.isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (recipe.isFavourite) "Remove from favourites" else "Add to favourites",
                tint = if (recipe.isFavourite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
