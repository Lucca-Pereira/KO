package com.lucca.ko.ui.plan

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.relations.PlannedRecipe
import com.lucca.ko.ui.common.KoTopBar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    onPickFromLibrary: (String, String) -> Unit,
    onAskAgent: (String, String) -> Unit,
    onNewRecipe: (String, String) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    vm: PlanViewModel = viewModel(factory = PlanViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var addFor by remember { mutableStateOf<LocalDate?>(null) }
    var movingEntry by remember { mutableStateOf<PlannedRecipe?>(null) }
    var servingsFor by remember { mutableStateOf<PlannedRecipe?>(null) }

    Scaffold(
        topBar = {
            KoTopBar(
                title = "Meal plan",
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
                    textAlign = TextAlign.Center,
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
                        onOpenRecipe = onOpenRecipe,
                        onRemove = vm::removeEntry,
                        onToggleCooked = { entry -> vm.setCooked(entry.entry.id, !entry.entry.cooked) },
                        onMove = { movingEntry = it },
                        onServings = { servingsFor = it },
                    )
                }
            }
        }
    }

    // ---- Add sheet ----------------------------------------------------------------

    val target = addFor
    if (target != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var slot by remember { mutableStateOf(MealSlot.DINNER) }
        ModalBottomSheet(onDismissRequest = { addFor = null }, sheetState = sheetState) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Add a meal", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealSlot.entries.forEach { s ->
                        FilterChip(
                            selected = slot == s,
                            onClick = { slot = s },
                            label = { Text(s.label()) },
                        )
                    }
                }
                // Library first: it is the common case now that recipes persist.
                OutlinedButton(
                    onClick = { onPickFromLibrary(target.toString(), slot.name); addFor = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("From my recipes") }
                OutlinedButton(
                    onClick = { onAskAgent(target.toString(), slot.name); addFor = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ask the agent") }
                OutlinedButton(
                    onClick = { onNewRecipe(target.toString(), slot.name); addFor = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Write a new recipe") }
            }
        }
    }

    // ---- Move ----------------------------------------------------------------------

    val moving = movingEntry
    if (moving != null) {
        MoveEntryDialog(
            entry = moving,
            onDismiss = { movingEntry = null },
            onConfirm = { date, slot ->
                vm.move(moving.entry.id, date, slot)
                movingEntry = null
            },
        )
    }

    // ---- Servings ------------------------------------------------------------------

    val servingsTarget = servingsFor
    if (servingsTarget != null) {
        ServingsDialog(
            entry = servingsTarget,
            onDismiss = { servingsFor = null },
            onConfirm = { servings ->
                vm.setServings(servingsTarget.entry.id, servings)
                servingsFor = null
            },
        )
    }
}

@Composable
private fun DayCard(
    day: DayPlan,
    onAdd: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onToggleCooked: (PlannedRecipe) -> Unit,
    onMove: (PlannedRecipe) -> Unit,
    onServings: (PlannedRecipe) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (day.isToday) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
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
                    Icon(Icons.Filled.Add, contentDescription = "Add a meal to ${day.weekdayLabel}")
                }
            }

            day.dishesBySlot.forEach { (slot, planned) ->
                Text(
                    slot.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                planned.forEach { entry ->
                    PlannedRow(
                        entry = entry,
                        onOpen = { entry.recipe?.let { onOpenRecipe(it.id) } },
                        onRemove = { onRemove(entry.entry.id) },
                        onToggleCooked = { onToggleCooked(entry) },
                        onMove = { onMove(entry) },
                        onServings = { onServings(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlannedRow(
    entry: PlannedRecipe,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onToggleCooked: () -> Unit,
    onMove: () -> Unit,
    onServings: () -> Unit,
) {
    // The recipe is null once it has been deleted from the library. The entry survives on its
    // title snapshot — greyed out and not tappable — so what you cooked stays on record.
    val recipe = entry.recipe
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (recipe != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = entry.entry.cooked, onCheckedChange = { onToggleCooked() })
        Column(Modifier.weight(1f)) {
            Text(
                entry.displayTitle.ifBlank { "Untitled" },
                color = if (recipe != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textDecoration = if (entry.entry.cooked) TextDecoration.LineThrough else null,
            )
            val servings = entry.entry.servings
            if (servings != 1.0) {
                Text(
                    "${formatServings(servings)} servings",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Options for ${entry.displayTitle}",
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Servings…") },
                onClick = { menuOpen = false; onServings() },
            )
            DropdownMenuItem(
                text = { Text("Move to another day…") },
                onClick = { menuOpen = false; onMove() },
            )
            DropdownMenuItem(
                text = { Text("Remove from plan") },
                onClick = { menuOpen = false; onRemove() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveEntryDialog(
    entry: PlannedRecipe,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, MealSlot) -> Unit,
) {
    val current = runCatching { LocalDate.parse(entry.entry.date) }.getOrDefault(LocalDate.now())
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = current.atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli(),
    )
    var slot by remember { mutableStateOf(entry.entry.slot) }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = pickerState.selectedDateMillis
                    val date = if (millis == null) {
                        current
                    } else {
                        // The picker works in UTC; read the date back the same way so a
                        // selection never lands on the day before in a negative offset.
                        Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    onConfirm(date, slot)
                },
            ) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState, title = null)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MealSlot.entries.forEach { s ->
                FilterChip(selected = slot == s, onClick = { slot = s }, label = { Text(s.label()) })
            }
        }
    }
}

@Composable
private fun ServingsDialog(
    entry: PlannedRecipe,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var value by remember { mutableStateOf(entry.entry.servings) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Servings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How much of ${entry.displayTitle} are you making?")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedButton(
                        onClick = { value = (value - 0.5).coerceAtLeast(0.5) },
                    ) { Text("−") }
                    Text(
                        formatServings(value),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    OutlinedButton(
                        onClick = { value = (value + 0.5).coerceAtMost(24.0) },
                    ) { Text("+") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatServings(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
