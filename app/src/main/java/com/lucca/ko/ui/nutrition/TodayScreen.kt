package com.lucca.ko.ui.nutrition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.db.LogSlot
import com.lucca.ko.data.db.NutritionEntry
import com.lucca.ko.data.repo.SupplementStatus
import com.lucca.ko.ui.common.CalorieRing
import com.lucca.ko.ui.common.EmptyState
import com.lucca.ko.ui.common.LoadingBox
import com.lucca.ko.ui.common.MacroBars
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun TodayScreen(
    onSetUpProfile: () -> Unit,
    onLogFood: (LogSlot) -> Unit,
    vm: TodayViewModel = viewModel(factory = TodayViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    if (state.loading) {
        LoadingBox()
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DayPicker(state, vm::previousDay, vm::nextDay, vm::today) }

        if (!state.profileComplete) {
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Set up your targets", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Your height, weight and goal are all it takes to work out calories " +
                                "and macros. Until then this is just a list of what you ate.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onSetUpProfile) { Text("Set it up") }
                    }
                }
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CalorieRing(
                    consumed = state.totals.kcal,
                    target = state.targets?.kcal ?: 0.0,
                )
                MacroBars(
                    proteinG = state.totals.proteinG,
                    carbsG = state.totals.carbsG,
                    fatG = state.totals.fatG,
                    proteinTarget = state.targets?.proteinG ?: 0.0,
                    carbsTarget = state.targets?.carbsG ?: 0.0,
                    fatTarget = state.targets?.fatG ?: 0.0,
                )
                if (state.targetExplanation.isNotBlank()) {
                    Text(
                        state.targetExplanation,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (state.supplements.isNotEmpty()) {
            item {
                Text(
                    "Supplements",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(state.supplements, key = { "supp-${it.supplement.id}" }) { status ->
                SupplementRow(status) { vm.toggleSupplement(status) }
            }
        }

        if (state.entries.isEmpty()) {
            item {
                EmptyState(
                    title = "Nothing logged yet",
                    subtitle = "Tap + to add something, or scan a barcode from the Foods tab.",
                )
            }
        }

        state.entriesBySlot.forEach { (slot, entries) ->
            item(key = "header-$slot") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        slot.label(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${entries.sumOf { it.kcal }.toInt()} kcal",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(entries, key = { it.id }) { entry ->
                EntryRow(entry) { vm.deleteEntry(entry.id) }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogSlot.entries.filter { it != LogSlot.SUPPLEMENT }.forEach { slot ->
                    TextButton(onClick = { onLogFood(slot) }) { Text("+ ${slot.label()}") }
                }
            }
        }
    }
}

@Composable
private fun DayPicker(
    state: TodayUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous day")
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (state.date) {
                    LocalDate.now() -> "Today"
                    LocalDate.now().minusDays(1) -> "Yesterday"
                    else -> state.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${state.date.dayOfMonth} " +
                    state.date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Disabled rather than hidden, so the control does not jump around as you move days.
        IconButton(onClick = onNext, enabled = !state.isToday) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next day")
        }
        if (!state.isToday) {
            TextButton(onClick = onToday) { Text("Today") }
        }
    }
}

@Composable
private fun EntryRow(entry: NutritionEntry, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                buildList {
                    entry.grams?.let { add("${it.toInt()} g") }
                    entry.servings?.takeIf { entry.grams == null }?.let {
                        add(if (it == 1.0) "1 serving" else "$it servings")
                    }
                    add("P ${entry.proteinG.toInt()}")
                    add("C ${entry.carbsG.toInt()}")
                    add("F ${entry.fatG.toInt()}")
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${entry.kcal.toInt()}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove ${entry.label}",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SupplementRow(status: SupplementStatus, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = status.takenToday, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(
                "${status.supplement.name} · ${status.supplement.doseAmount.trimZero()} " +
                    status.supplement.doseUnit,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                when {
                    status.streakDays > 1 -> "${status.streakDays} day streak"
                    status.streakDays == 1 -> "Started today"
                    else -> "${(status.adherence30d * 100).toInt()}% over 30 days"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (status.supplement.affectsMacros) {
            Box(Modifier.padding(end = 12.dp)) {
                Text(
                    "${status.supplement.kcalPerDose.toInt()} kcal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

fun LogSlot.label(): String = when (this) {
    LogSlot.BREAKFAST -> "Breakfast"
    LogSlot.LUNCH -> "Lunch"
    LogSlot.DINNER -> "Dinner"
    LogSlot.SNACK -> "Snacks"
    LogSlot.SUPPLEMENT -> "Supplements"
}

fun Double.trimZero(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()
