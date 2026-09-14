package com.lucca.ko.ui.suggest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lucca.ko.data.remote.MealSummary
import com.lucca.ko.ui.common.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestScreen(
    date: String,
    slot: String,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    vm: SuggestViewModel = viewModel(factory = SuggestViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.savedDishId) { state.savedDishId?.let(onSaved) }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Recipe ideas") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::load, enabled = !state.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Asking the recipe bot…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                state.error != null -> EmptyState(title = "Couldn't get ideas", subtitle = state.error)

                else -> {
                    val note = state.result?.note
                    val ideas = state.result?.ideas.orEmpty()
                    val meals = state.result?.meals.orEmpty()
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (note != null) {
                            item {
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(16.dp),
                                )
                            }
                        }
                        if (ideas.isNotEmpty()) {
                            item {
                                Text(
                                    "The bot suggested",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                                )
                            }
                            items(ideas, key = { "idea-" + it.title }) { idea ->
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    Text(idea.title, style = MaterialTheme.typography.bodyLarge)
                                    if (idea.why.isNotBlank()) {
                                        Text(
                                            idea.why,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontStyle = FontStyle.Italic,
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Text(
                                "Tap a recipe to add it",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                            )
                        }

                        if (meals.isEmpty()) {
                            item {
                                EmptyState(
                                    title = "No matching recipes",
                                    subtitle = "Add a few pantry items or try the manual search.",
                                )
                            }
                        } else {
                            items(meals, key = { it.id }) { meal ->
                                MealRow(meal, saving = state.savingId == meal.id) {
                                    vm.pick(meal.id, date, slot)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MealRow(meal: MealSummary, saving: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !saving, onClick = onClick).padding(16.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = meal.thumbUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
        )
        Text(meal.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (saving) CircularProgressIndicator(Modifier.size(20.dp))
    }
}
