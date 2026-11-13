package com.lucca.ko.ui.today

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
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.domain.Availability
import com.lucca.ko.ui.common.KoTopBar
import com.lucca.ko.ui.common.LoadingBox
import com.lucca.ko.ui.common.availabilityColor
import com.lucca.ko.ui.plan.label
import kotlin.math.roundToInt

/**
 * The start screen: what today needs from you, in one place — the meals planned for this weekday
 * and whether the kitchen can make them, calories so far, and one line each for the lists.
 * Everything on it is a shortcut to the section that owns it; nothing is edited here except the
 * "cooked" tick.
 */
@Composable
fun HomeScreen(
    onOpenRecipe: (Long) -> Unit,
    onOpenMeals: () -> Unit,
    onOpenGroceries: () -> Unit,
    onOpenPantry: () -> Unit,
    onOpenGym: () -> Unit,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { KoTopBar(title = "Today", subtitle = state.weekday) }) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionLabel("Meals") }
            if (state.meals.isEmpty()) {
                item {
                    HomeCard(onClick = onOpenMeals) {
                        Text("Nothing planned for ${state.weekday}", fontWeight = FontWeight.Medium)
                        Text(
                            "Open Meals to plan the week.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(state.meals, key = { it.entryId }) { meal ->
                    MealCard(
                        meal = meal,
                        onOpen = { meal.recipeId?.let(onOpenRecipe) },
                        onToggleCooked = { vm.setCooked(meal.entryId, !meal.cooked) },
                        onAddMissing = { meal.recipeId?.let(vm::addMissingToGroceries) },
                    )
                }
            }

            item { SectionLabel("Calories") }
            item { CaloriesCard(state, onClick = onOpenGym) }

            item { SectionLabel("Kitchen") }
            item {
                SummaryRow(
                    icon = Icons.Filled.ShoppingCart,
                    title = "Groceries",
                    detail = if (state.toBuy == 0) "Nothing to buy" else "${state.toBuy} to buy",
                    onClick = onOpenGroceries,
                )
            }
            item {
                SummaryRow(
                    icon = Icons.Filled.Kitchen,
                    title = "Pantry",
                    detail = pantryDetail(state.pantryLow, state.pantryOut),
                    onClick = onOpenPantry,
                )
            }
            if (state.supplementsTotal > 0) {
                item {
                    SummaryRow(
                        icon = Icons.Filled.FitnessCenter,
                        title = "Supplements",
                        detail = "${state.supplementsTaken} of ${state.supplementsTotal} taken",
                        onClick = onOpenGym,
                    )
                }
            }
        }
    }
}

private fun pantryDetail(low: Int, out: Int): String = when {
    low == 0 && out == 0 -> "All stocked"
    out == 0 -> "$low low"
    low == 0 -> "$out out"
    else -> "$low low · $out out"
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun HomeCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}

@Composable
private fun MealCard(
    meal: TodayMeal,
    onOpen: () -> Unit,
    onToggleCooked: () -> Unit,
    onAddMissing: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (meal.recipeId != null) Modifier.clickable(onClick = onOpen) else Modifier),
    ) {
        Row(Modifier.padding(start = 2.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = meal.cooked, onCheckedChange = { onToggleCooked() })
            Column(Modifier.weight(1f)) {
                Text(
                    meal.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (meal.cooked) TextDecoration.LineThrough else null,
                )
                Text(
                    listOfNotNull(
                        meal.slot.label(),
                        meal.totalMinutes?.let { "$it min" },
                        formatServings(meal.servings) + if (meal.servings == 1.0) " serving" else " servings",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (meal.ingredientCount > 0 && !meal.cooked) {
                    val ready = meal.missingCount == 0
                    Text(
                        if (ready) "Everything in stock" else "${meal.missingCount} of ${meal.ingredientCount} ingredients missing",
                        style = MaterialTheme.typography.bodySmall,
                        color = availabilityColor(if (ready) Availability.HAVE else Availability.MISSING),
                    )
                }
            }
            if (meal.missingCount > 0 && !meal.cooked) {
                TextButton(onClick = onAddMissing) { Text("Add to list") }
            }
        }
    }
}

@Composable
private fun CaloriesCard(state: HomeUiState, onClick: () -> Unit) {
    HomeCard(onClick = onClick) {
        val target = state.kcalTarget
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "%,d".format(state.kcalEaten.roundToInt()),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                if (target != null) "of %,d kcal".format(target.roundToInt()) else "kcal eaten",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        if (target != null && target > 0) {
            LinearProgressIndicator(
                progress = { (state.kcalEaten / target).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        Text(
            if (target == null && state.kcalEaten == 0.0) {
                "Set up your details in Gym to get a daily target."
            } else {
                "Protein ${state.proteinG.roundToInt()} g · Carbs ${state.carbsG.roundToInt()} g · " +
                    "Fat ${state.fatG.roundToInt()} g"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null)
            Text(title, Modifier.weight(1f))
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatServings(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
