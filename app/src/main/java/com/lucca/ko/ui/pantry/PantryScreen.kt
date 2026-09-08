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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.db.PRESET_CATEGORIES
import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.ui.common.EmptyState
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
        topBar = { TopAppBar(title = { Text("Pantry (${state.total})") }) },
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
        ModalBottomSheet(
            onDismissRequest = { showEditor = false },
            sheetState = sheetState,
        ) {
            PantryEditor(
                original = editing,
                onDelete = editing?.let { item ->
                    {
                        vm.delete(item.id)
                        scope.launch { sheetState.hide() }.invokeOnCompletion { showEditor = false }
                    }
                },
                onSave = { name, category, status, qty, note ->
                    vm.save(editing?.id, name, category, status, qty, note)
                    scope.launch { sheetState.hide() }.invokeOnCompletion { showEditor = false }
                },
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
) {
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var category by remember { mutableStateOf(original?.category ?: "Other") }
    var status by remember { mutableStateOf(original?.status ?: StockStatus.IN_STOCK) }
    var quantity by remember { mutableStateOf(original?.quantity.orEmpty()) }
    var note by remember { mutableStateOf(original?.note.orEmpty()) }
    var catMenu by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (original == null) "Add item" else "Edit item",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
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
                    DropdownMenuItem(text = { Text(c) }, onClick = { category = c; catMenu = false })
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(name, category, status, quantity, note) },
                enabled = name.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
        }
    }
}
