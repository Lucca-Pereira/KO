package com.lucca.ko.ui.pantry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.db.PRESET_CATEGORIES
import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.domain.CategoryGuesser
import com.lucca.ko.ui.common.EmptyState
import com.lucca.ko.ui.common.KoTopBar
import com.lucca.ko.ui.common.SectionHeader
import com.lucca.ko.ui.common.StatusPill
import com.lucca.ko.ui.common.label
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryScreen(vm: PantryViewModel = viewModel(factory = PantryViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PantryItem?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { KoTopBar(title = "Pantry (${state.total})") },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add item")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search pantry") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (state.sections.isEmpty()) {
                EmptyState(
                    title = if (state.total == 0) "Your pantry is empty" else "No matches",
                    subtitle = if (state.total == 0)
                        "Tap + to add products and spices you keep at home."
                    else null,
                )
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, bottom = 96.dp,
                    ),
                ) {
                    state.sections.forEach { section ->
                        item(key = "h-${section.category}") { SectionHeader(section.category) }
                        items(section.items, key = { it.id }) { item ->
                            PantryRow(
                                item = item,
                                onCycle = { vm.cycleStatus(item) },
                                onClick = { editing = item; showEditor = true },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        val current = editing
        fun close() {
            scope.launch { sheetState.hide() }.invokeOnCompletion { showEditor = false }
        }
        ModalBottomSheet(
            onDismissRequest = { showEditor = false },
            sheetState = sheetState,
        ) {
            PantryEditor(
                original = current,
                onDelete = current?.let { item -> { vm.delete(item.id); close() } },
                onSave = { name, category, status, qty, note ->
                    vm.save(current?.id, name, category, status, qty, note)
                    close()
                },
                onSaveAndContinue = if (current == null) {
                    { name, category, status, qty, note ->
                        vm.save(null, name, category, status, qty, note)
                    }
                } else null,
            )
        }
    }
}

@Composable
private fun PantryRow(item: PantryItem, onCycle: () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(item.quantity, item.note).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.clickable(onClick = onCycle)) { StatusPill(item.status) }
    }
}

@Composable
private fun PantryEditor(
    original: PantryItem?,
    onDelete: (() -> Unit)?,
    onSave: (String, String, StockStatus, String?, String?) -> Unit,
    onSaveAndContinue: ((String, String, StockStatus, String?, String?) -> Unit)?,
) {
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var category by remember { mutableStateOf(original?.category ?: "Other") }
    var status by remember { mutableStateOf(original?.status ?: StockStatus.IN_STOCK) }
    var quantity by remember { mutableStateOf(original?.quantity.orEmpty()) }
    var note by remember { mutableStateOf(original?.note.orEmpty()) }
    var catMenu by remember { mutableStateOf(false) }
    // For new items, keep guessing the category from the name until the user picks one.
    var categoryTouched by remember { mutableStateOf(original != null) }
    var addedCount by remember { mutableStateOf(0) }
    val nameFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (original == null) runCatching { nameFocus.requestFocus() }
    }

    fun setName(value: String) {
        name = value
        if (!categoryTouched) category = CategoryGuesser.guess(value)
    }

    fun addAnother() {
        if (name.isBlank() || onSaveAndContinue == null) return
        onSaveAndContinue(name, category, status, quantity, note)
        addedCount++
        name = ""
        quantity = ""
        note = ""
        if (!categoryTouched) category = "Other"
        runCatching { nameFocus.requestFocus() }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (original == null) "Add items" else "Edit item",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
        }

        if (addedCount > 0) {
            Text(
                "$addedCount added",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        OutlinedTextField(
            value = name,
            onValueChange = { setName(it) },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = if (onSaveAndContinue != null) ImeAction.Done else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onDone = { addAnother() }),
            modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
        )

        Box {
            OutlinedTextField(
                value = category,
                onValueChange = {},
                readOnly = true,
                label = { Text("Category") },
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.matchParentSize().clickable { catMenu = true })
            DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                PRESET_CATEGORIES.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c) },
                        onClick = { category = c; categoryTouched = true; catMenu = false },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StockStatus.entries.forEach { s ->
                FilterChip(
                    selected = status == s,
                    onClick = { status = s },
                    label = { Text(s.label()) },
                )
            }
        }

        OutlinedTextField(
            value = quantity,
            onValueChange = { quantity = it },
            label = { Text("Quantity (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )

        if (onSaveAndContinue != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { addAnother() },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Save & add another") }
                OutlinedButton(
                    onClick = { onSave(name, category, status, quantity, note) },
                    enabled = name.isNotBlank() || addedCount > 0,
                ) { Text(if (name.isBlank()) "Done" else "Save & close") }
            }
        } else {
            Button(
                onClick = { onSave(name, category, status, quantity, note) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
