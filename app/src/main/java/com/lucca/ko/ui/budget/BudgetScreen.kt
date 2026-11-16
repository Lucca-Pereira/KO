package com.lucca.ko.ui.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.db.Expense
import com.lucca.ko.domain.budget.BudgetMath
import com.lucca.ko.domain.budget.CategorySpend
import com.lucca.ko.ui.common.EmptyState
import com.lucca.ko.ui.common.KoTopBar
import com.lucca.ko.ui.common.SectionHeader
import com.lucca.ko.ui.theme.Dimens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** The expense being added ([Expense] with id 0) or edited; null = no dialog. */
private data class Editing(val expense: Expense?)

@Composable
fun BudgetScreen(vm: BudgetViewModel = viewModel(factory = BudgetViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Editing?>(null) }
    var limitFor by remember { mutableStateOf<CategorySpend?>(null) }
    var newLimit by remember { mutableStateOf(false) }

    val monthTitle = state.month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        .replaceFirstChar { it.uppercase() } + " " + state.month.year

    Scaffold(
        topBar = {
            KoTopBar(
                title = "Budget",
                subtitle = monthTitle,
                actions = {
                    IconButton(onClick = vm::previousMonth) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                    }
                    IconButton(onClick = vm::nextMonth) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Editing(null) }) {
                Icon(Icons.Filled.Add, contentDescription = "Add expense")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Dimens.m, end = Dimens.m, bottom = 96.dp),
        ) {
            item(key = "total") {
                TotalCard(state.summary.totalSpentCents, state.summary.totalLimitCents)
            }
            item(key = "h-cats") { SectionHeader("Categories") }
            if (state.summary.rows.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "Nothing spent this month",
                        subtitle = "Tap + to add an expense, or ask Claude to log one.",
                    )
                }
            }
            items(state.summary.rows, key = { "c-${it.category}" }) { row ->
                CategoryRow(row, onClick = { limitFor = row })
            }
            item(key = "set-limit") {
                TextButton(onClick = { newLimit = true }) { Text("Set a category limit") }
            }
            if (state.expenses.isNotEmpty()) {
                item(key = "h-exp") { SectionHeader("Expenses · ${state.expenses.size}") }
                items(state.expenses, key = { "e-${it.id}" }) { e ->
                    ExpenseRow(e, onClick = { editing = Editing(e) })
                }
            }
        }
    }

    editing?.let { edit ->
        ExpenseDialog(
            existing = edit.expense,
            categories = state.categories,
            onDismiss = { editing = null },
            onSave = { cents, category, note, date ->
                vm.save(edit.expense?.id, cents, category, note, date)
                editing = null
            },
            onDelete = edit.expense?.let { e -> { vm.delete(e.id); editing = null } },
        )
    }

    limitFor?.let { row ->
        LimitDialog(
            category = row.category,
            fixedCategory = true,
            categories = emptyList(),
            currentCents = row.limitCents,
            onDismiss = { limitFor = null },
            onSave = { cat, cents -> vm.setLimit(cat, cents); limitFor = null },
            onClear = if (row.limitCents != null) ({ vm.clearLimit(row.category); limitFor = null }) else null,
        )
    }
    if (newLimit) {
        LimitDialog(
            category = "",
            fixedCategory = false,
            categories = state.categories,
            currentCents = null,
            onDismiss = { newLimit = false },
            onSave = { cat, cents -> vm.setLimit(cat, cents); newLimit = false },
            onClear = null,
        )
    }
}

@Composable
private fun TotalCard(spent: Long, limit: Long) {
    val over = limit > 0 && spent > limit
    Card(
        Modifier.fillMaxWidth().padding(top = Dimens.s),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Text("Spent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                BudgetMath.format(spent),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (limit > 0) {
                LinearProgressIndicator(
                    progress = { (spent.toDouble() / limit).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    "of ${BudgetMath.format(limit)} in limits",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "No limits set",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(row: CategorySpend, onClick: () -> Unit) {
    val color = if (row.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Dimens.s)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.category.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (row.limitCents != null) {
                    "${BudgetMath.format(row.spentCents)} / ${BudgetMath.format(row.limitCents)}"
                } else {
                    BudgetMath.format(row.spentCents)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.limitCents != null) {
            LinearProgressIndicator(
                progress = { row.fraction },
                modifier = Modifier.fillMaxWidth().padding(top = Dimens.xs),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@Composable
private fun ExpenseRow(e: Expense, onClick: () -> Unit) {
    val date = runCatching { LocalDate.parse(e.spentOn) }.getOrNull()
        ?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: e.spentOn
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                e.note?.takeIf { it.isNotBlank() } ?: e.category.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            Text(
                "$date · ${e.category}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(BudgetMath.format(e.amountCents), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CategorySuggestions(categories: List<String>, onPick: (String) -> Unit) {
    if (categories.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
        items(categories) { c -> SuggestionChip(onClick = { onPick(c) }, label = { Text(c) }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseDialog(
    existing: Expense?,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (cents: Long, category: String, note: String?, date: String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var amount by remember { mutableStateOf(existing?.let { BudgetMath.plainAmount(it.amountCents) }.orEmpty()) }
    var category by remember { mutableStateOf(existing?.category.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var date by remember {
        mutableStateOf(existing?.let { runCatching { LocalDate.parse(it.spentOn) }.getOrNull() } ?: LocalDate.now())
    }
    var pickingDate by remember { mutableStateOf(false) }
    val cents = BudgetMath.parseCents(amount)
    val canSave = cents != null && category.isNotBlank()

    if (pickingDate) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add expense" else "Edit expense") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
                    singleLine = true,
                    isError = amount.isNotBlank() && cents == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                CategorySuggestions(categories) { category = it }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = { onSave(cents!!, category, note, date.toString()) },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun LimitDialog(
    category: String,
    fixedCategory: Boolean,
    categories: List<String>,
    currentCents: Long?,
    onDismiss: () -> Unit,
    onSave: (category: String, cents: Long) -> Unit,
    onClear: (() -> Unit)?,
) {
    var cat by remember { mutableStateOf(category) }
    var amount by remember { mutableStateOf(currentCents?.let { BudgetMath.plainAmount(it) }.orEmpty()) }
    val cents = BudgetMath.parseCents(amount)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (fixedCategory) "Monthly limit for ${cat.replaceFirstChar { it.uppercase() }}" else "Monthly limit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                if (!fixedCategory) {
                    OutlinedTextField(
                        value = cat,
                        onValueChange = { cat = it },
                        label = { Text("Category") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CategorySuggestions(categories) { cat = it }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Per month") },
                    singleLine = true,
                    isError = amount.isNotBlank() && cents == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = cents != null && cat.isNotBlank(),
                onClick = { onSave(cat, cents!!) },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onClear != null) {
                    TextButton(onClick = onClear) { Text("Remove limit", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
