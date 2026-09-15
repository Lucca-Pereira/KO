package com.lucca.ko.ui.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.images.RecipeImages
import com.lucca.ko.ui.common.EmptyState
import com.lucca.ko.ui.common.KoTopBar
import com.lucca.ko.ui.common.LoadingBox
import com.lucca.ko.ui.common.RecipeMetaLine
import com.lucca.ko.ui.common.RecipeThumbnail
import com.lucca.ko.ui.koFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.repo.MealPlanRepository
import java.time.LocalDate
import kotlinx.coroutines.launch

/** Adds the picked recipe to a day. Separate from the list VM, which is shared with the library. */
class RecipePickerViewModel(private val plan: MealPlanRepository) : ViewModel() {
    fun add(recipeId: Long, date: String, slot: String, onAdded: () -> Unit) =
        viewModelScope.launch {
            plan.addToPlan(
                recipeId = recipeId,
                date = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now()),
                slot = runCatching { MealSlot.valueOf(slot) }.getOrDefault(MealSlot.DINNER),
            )
            onAdded()
        }

    companion object {
        val Factory = koFactory { RecipePickerViewModel(it.mealPlanRepository) }
    }
}

/**
 * Pick a recipe already in the library to plan on a given day.
 *
 * This is the payoff for decoupling recipes from the plan: the same recipe can be planned on as
 * many days as you like, and only a reference is stored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipePickerScreen(
    date: String,
    slot: String,
    dayLabel: String,
    onBack: () -> Unit,
    onAdded: () -> Unit,
    vm: RecipeListViewModel = viewModel(factory = RecipeListViewModel.Factory),
    addVm: RecipePickerViewModel = viewModel(factory = RecipePickerViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            KoTopBar(
                title = "Add from library",
                subtitle = dayLabel,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search your recipes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )

            when {
                state.loading -> LoadingBox()

                state.recipes.isEmpty() && state.query.isNotBlank() -> EmptyState(
                    title = "Nothing matches",
                    subtitle = "Try a different search.",
                )

                state.recipes.isEmpty() -> EmptyState(
                    title = "Your library is empty",
                    subtitle = "Search TheMealDB or write a recipe by hand, and it'll show up here.",
                )

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.recipes, key = { it.recipe.id }) { item ->
                        val recipe = item.recipe
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { addVm.add(recipe.id, date, slot, onAdded) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RecipeThumbnail(
                                model = RecipeImages.asFile(recipe.imageLocalPath) ?: recipe.imageUrl,
                                size = 48,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    recipe.title.ifBlank { "Untitled recipe" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                RecipeMetaLine(
                                    totalMinutes = listOfNotNull(recipe.prepMinutes, recipe.cookMinutes)
                                        .takeIf { it.isNotEmpty() }?.sum(),
                                    servings = recipe.servings,
                                    timesCooked = recipe.timesCooked,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
