package com.lucca.ko.ui.shopping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.db.ShoppingListItem
import com.lucca.ko.ui.common.EmptyState
import com.lucca.ko.ui.common.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(vm: ShoppingViewModel = viewModel(factory = ShoppingViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shopping list") },
                actions = {
                    if (state.checked.isNotEmpty()) {
                        TextButton(onClick = vm::clearChecked) { Text("Clear checked") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Add an item") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (input.isNotBlank()) { vm.add(input); input = "" }
                    }),
                )
                IconButton(
                    onClick = { if (input.isNotBlank()) { vm.add(input); input = "" } },
                ) { Icon(Icons.Filled.Add, contentDescription = "Add") }
            }

            if (state.sections.isEmpty() && state.checked.isEmpty()) {
                EmptyState(
                    title = "Nothing to buy",
                    subtitle = "Items land here when you mark something as \"Out\" in the pantry or a recipe.",
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                    state.sections.forEach { section ->
                        item(key = "h-${section.category}") { SectionHeader(section.category) }
                        items(section.items, key = { it.id }) { item ->
                            ShoppingRow(item, onToggle = { vm.toggle(item) }, onDelete = { vm.delete(item.id) })
                        }
                    }
                    if (state.checked.isNotEmpty()) {
                        item(key = "h-checked") { SectionHeader("In the basket") }
                        items(state.checked, key = { it.id }) { item ->
                            ShoppingRow(item, onToggle = { vm.toggle(item) }, onDelete = { vm.delete(item.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShoppingRow(item: ShoppingListItem, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.checked, onCheckedChange = { onToggle() })
        Text(
            text = item.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (item.checked) TextDecoration.LineThrough else null,
            color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
