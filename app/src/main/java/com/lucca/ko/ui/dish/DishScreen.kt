package com.lucca.ko.ui.dish

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lucca.ko.domain.Availability
import com.lucca.ko.domain.ResolvedIngredient
import com.lucca.ko.ui.common.Dot
import com.lucca.ko.ui.common.EmptyState
import com.lucca.ko.ui.common.LoadingBox
import com.lucca.ko.ui.common.availabilityColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DishScreen(
    onBack: () -> Unit,
    vm: DishViewModel = viewModel(factory = DishViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showInstructions by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.dish?.title ?: "Dish", maxLines = 1) },
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
            state.dish == null -> EmptyState(
                title = "Dish not found",
                modifier = Modifier.padding(padding),
            )
            else -> {
                val dish = state.dish!!
                LazyColumn(
                    modifier = Modifier.padding(padding),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    if (!dish.imageUrl.isNullOrBlank()) {
                        item {
                            AsyncImage(
                                model = dish.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(200.dp),
                            )
                        }
                    }

                    item {
                        Column(Modifier.padding(16.dp)) {
                            if (!dish.sourceUrl.isNullOrBlank()) {
                                AssistChip(
                                    onClick = { openUrl(context, dish.sourceUrl) },
                                    label = { Text("Open recipe") },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                                    },
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Ingredients",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (state.missingCount == 0) "All in stock"
                                    else "${state.missingCount} missing",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (state.missingCount == 0)
                                        availabilityColor(Availability.HAVE)
                                    else availabilityColor(Availability.MISSING),
                                )
                            }
                        }
                    }

                    items(state.ingredients, key = { it.dishIngredientId }) { ing ->
                        IngredientRow(
                            ing = ing,
                            onRanOut = { vm.markRanOut(ing.dishIngredientId) },
                            onHave = { vm.markHave(ing.dishIngredientId) },
                        )
                    }

                    if (state.missingCount > 0) {
                        item {
                            Button(
                                onClick = vm::addAllMissingToShopping,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            ) { Text("Add ${state.missingCount} missing to shopping list") }
                        }
                    }

                    val instructions = dish.instructions
                    if (!instructions.isNullOrBlank()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().clickable { showInstructions = !showInstructions }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Instructions",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        if (showInstructions) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                        contentDescription = null,
                                    )
                                }
                                AnimatedVisibility(visible = showInstructions) {
                                    Text(
                                        text = instructions.orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(bottom = 16.dp),
                                    )
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
private fun IngredientRow(
    ing: ResolvedIngredient,
    onRanOut: () -> Unit,
    onHave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Dot(availabilityColor(ing.availability))
        Column(Modifier.weight(1f)) {
            Text(
                ing.rawName.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (!ing.measure.isNullOrBlank()) {
                Text(
                    ing.measure,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (ing.availability == Availability.MISSING) {
            TextButton(onClick = onHave) { Text("Have it") }
        } else {
            TextButton(onClick = onRanOut) { Text("Ran out") }
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    val uri: Uri = runCatching { url.toUri() }.getOrNull() ?: return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
    }
}
