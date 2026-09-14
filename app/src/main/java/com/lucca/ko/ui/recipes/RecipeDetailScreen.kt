package com.lucca.ko.ui.recipes

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
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
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.images.RecipeImages
import com.lucca.ko.domain.Availability
import com.lucca.ko.domain.ResolvedIngredient
import com.lucca.ko.ui.common.Dot
import com.lucca.ko.ui.common.EmptyState
import com.lucca.ko.ui.common.LoadingBox
import com.lucca.ko.ui.common.RecipeMetaLine
import com.lucca.ko.ui.common.availabilityColor
import com.lucca.ko.ui.plan.label
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    vm: RecipeDetailViewModel = viewModel(factory = RecipeDetailViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var planning by remember { mutableStateOf(false) }
    var justPlanned by remember { mutableStateOf<String?>(null) }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(deleted) { if (deleted) onBack() }
    // The plan count is only read once per load, so refresh it when we come back from planning.
    LaunchedEffect(Unit) { vm.refreshPlanCount() }

    val recipe = state.recipe

    if (confirmDelete && recipe != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this recipe?") },
            text = {
                Text(
                    buildString {
                        append("\"${recipe.title}\" will be removed from your library. ")
                        when (state.planCount) {
                            0 -> append("It isn't on your meal plan.")
                            1 -> append(
                                "One planned meal uses it — that day will keep the name but " +
                                    "won't open anything.",
                            )
                            else -> append(
                                "${state.planCount} planned meals use it — those days will keep " +
                                    "the name but won't open anything.",
                            )
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    if (planning && recipe != null) {
        PlanTargetSheet(
            onDismiss = { planning = false },
            onConfirm = { date, slot ->
                vm.addToPlan(date, slot)
                planning = false
                justPlanned = "Added to ${slot.label().lowercase()} on ${date.dayOfMonth} " +
                    date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            },
        )
    }

    LaunchedEffect(justPlanned) {
        if (justPlanned != null) {
            snackbarHost.showSnackbar(justPlanned!!)
            justPlanned = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(recipe?.title ?: "Recipe", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (recipe != null) {
                        IconButton(onClick = vm::toggleFavourite) {
                            Icon(
                                if (recipe.isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (recipe.isFavourite) {
                                    "Remove from favourites"
                                } else {
                                    "Add to favourites"
                                },
                                tint = if (recipe.isFavourite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(onClick = { onEdit(recipe.id) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit recipe")
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete recipe")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (recipe != null) {
                ExtendedFloatingActionButton(
                    onClick = { planning = true },
                    icon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
                    text = { Text("Add to plan") },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))

            recipe == null -> EmptyState(
                title = "Recipe not found",
                subtitle = "It may have been deleted.",
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                val image = RecipeImages.asFile(recipe.imageLocalPath) ?: recipe.imageUrl
                if (image != null && (image !is String || image.isNotBlank())) {
                    item {
                        AsyncImage(
                            model = image,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                        )
                    }
                }

                item {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RecipeMetaLine(
                            totalMinutes = listOfNotNull(recipe.prepMinutes, recipe.cookMinutes)
                                .takeIf { it.isNotEmpty() }?.sum(),
                            servings = recipe.servings,
                            timesCooked = recipe.timesCooked,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.tags.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.tags.take(4).forEach { tag ->
                                    SuggestionChip(onClick = {}, label = { Text(tag.name) })
                                }
                            }
                        }
                        if (!recipe.sourceUrl.isNullOrBlank()) {
                            AssistChip(
                                onClick = { openUrl(context, recipe.sourceUrl) },
                                label = { Text("Open original") },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                            )
                        }
                        if (!recipe.notes.isNullOrBlank()) {
                            Text(recipe.notes, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Ingredients",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.ingredients.isNotEmpty()) {
                            Text(
                                if (state.missingCount == 0) {
                                    "All in stock"
                                } else {
                                    "${state.missingCount} missing"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = availabilityColor(
                                    if (state.missingCount == 0) Availability.HAVE else Availability.MISSING,
                                ),
                            )
                        }
                    }
                }

                if (state.ingredients.isEmpty()) {
                    item {
                        Text(
                            "No ingredients yet — tap the pencil to add some.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    items(state.ingredients, key = { it.dishIngredientId }) { ing ->
                        IngredientRow(
                            ing = ing,
                            onRanOut = { vm.markRanOut(ing.dishIngredientId) },
                            onHave = { vm.markHave(ing.dishIngredientId) },
                        )
                    }
                }

                if (state.missingCount > 0) {
                    item {
                        Button(
                            onClick = vm::addAllMissingToShopping,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) { Text("Add ${state.missingCount} missing to shopping list") }
                    }
                }

                val steps = state.steps
                val fallback = state.fallbackSteps
                if (steps.isNotEmpty() || fallback.isNotEmpty()) {
                    item {
                        Text(
                            "Method",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (steps.isNotEmpty()) {
                    items(steps, key = { it.id }) { step ->
                        StepRow(
                            number = step.position + 1,
                            text = step.text,
                            minutes = step.minutes,
                        )
                    }
                } else {
                    // Prose from a MealDB import, shown as numbered steps without committing
                    // the split to the database until the recipe is actually edited.
                    fallback.forEachIndexed { index, text ->
                        item(key = "fallback-$index") {
                            StepRow(number = index + 1, text = text, minutes = null)
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

@Composable
private fun StepRow(number: Int, text: String, minutes: Int?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "$number.",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (minutes != null) {
                Text(
                    com.lucca.ko.ui.common.formatMinutes(minutes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Pick a day and a slot to plan this recipe on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanTargetSheet(
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, MealSlot) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var date by remember { mutableStateOf(LocalDate.now()) }
    var slot by remember { mutableStateOf(MealSlot.DINNER) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add to plan", style = MaterialTheme.typography.titleLarge)

            // The next seven days covers almost every case; anything further out is easier to
            // do from the plan itself, where you can see the week.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (0..6L).forEach { offset ->
                    val candidate = LocalDate.now().plusDays(offset)
                    FilterChip(
                        selected = date == candidate,
                        onClick = { date = candidate },
                        label = {
                            Text(
                                when (offset) {
                                    0L -> "Today"
                                    1L -> "Tomorrow"
                                    else -> candidate.dayOfWeek
                                        .getDisplayName(TextStyle.SHORT, Locale.getDefault())
                                },
                            )
                        },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MealSlot.entries.forEach { s ->
                    FilterChip(
                        selected = slot == s,
                        onClick = { slot = s },
                        label = { Text(s.label()) },
                    )
                }
            }

            Button(
                onClick = { onConfirm(date, slot) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add") }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    val uri: Uri = runCatching { url.toUri() }.getOrNull() ?: return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
    }
}
