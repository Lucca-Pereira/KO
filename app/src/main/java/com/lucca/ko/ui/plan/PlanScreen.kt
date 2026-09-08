package com.lucca.ko.ui.plan

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.db.MealSlot
import java.time.LocalDate

private fun MealSlot.label() = when (this) {
    MealSlot.BREAKFAST -> "Breakfast"
    MealSlot.LUNCH -> "Lunch"
    MealSlot.DINNER -> "Dinner"
    MealSlot.OTHER -> "Other"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    onAddSuggested: (String, String) -> Unit,
    onSearchMeal: (String, String) -> Unit,
    onAddManual: (String, String) -> Unit,
    onOpenDish: (Long) -> Unit,
    vm: PlanViewModel = viewModel(factory = PlanViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var addFor by remember { mutableStateOf<LocalDate?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meal plan") },
                actions = {
                    if (!state.isCurrentWeek) TextButton(onClick = vm::goToday) { Text("Today") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::prevWeek) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous week")
                }
                Text(
                    state.rangeLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = vm::nextWeek) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next week")
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.days, key = { it.date.toString() }) { day ->
                    DayCard(
                        day = day,
                        onAdd = { addFor = day.date },
                        onOpenDish = onOpenDish,
                        onRemove = vm::removeEntry,
                    )
                }
            }
        }
    }

    val target = addFor
    if (target != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var slot by remember { mutableStateOf(MealSlot.DINNER) }
        ModalBottomSheet(onDismissRequest = { addFor = null }, sheetState = sheetState) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Add a dish", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealSlot.entries.forEach { s ->
                        FilterChip(
                            selected = slot == s,
                            onClick = { slot = s },
                            label = { Text(s.label()) },
                        )
                    }
                }
                OutlinedButton(
                    onClick = { onAddSuggested(target.toString(), slot.name); addFor = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ask the recipe bot") }
                OutlinedButton(
                    onClick = { onSearchMeal(target.toString(), slot.name); addFor = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Search TheMealDB") }
                OutlinedButton(
                    onClick = { onAddManual(target.toString(), slot.name); addFor = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add manually") }
            }
        }
    }
}

@Composable
private fun DayCard(
    day: DayPlan,
    onAdd: () -> Unit,
    onOpenDish: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (day.isToday) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        day.weekdayLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(day.dateLabel, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = "Add dish to ${day.weekdayLabel}")
                }
            }

            if (day.dishesBySlot.isEmpty()) {
                Text(
                    "Nothing planned",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                day.dishesBySlot.forEach { (slot, dishes) ->
                    Text(
                        slot.label().uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    dishes.forEach { planned ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenDish(planned.dish.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(planned.dish.title, Modifier.weight(1f))
                            IconButton(onClick = { onRemove(planned.entry.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Remove ${planned.dish.title}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
