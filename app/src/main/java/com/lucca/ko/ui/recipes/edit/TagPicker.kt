package com.lucca.ko.ui.recipes.edit

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Tags as chips, plus a text field that commits on Done.
 *
 * [suggestions] are the tags already used elsewhere in the library; offering them keeps
 * "weeknight" and "Weeknight" from becoming two tags, which matters because the library filter
 * is driven by them.
 */
@Composable
fun TagPicker(
    tags: List<String>,
    suggestions: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var typed by remember { mutableStateOf("") }
    val unused = suggestions.filterNot { s -> tags.any { it.equals(s, ignoreCase = true) } }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tags", style = MaterialTheme.typography.titleMedium)

        if (tags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tags.forEach { tag ->
                    InputChip(
                        selected = true,
                        onClick = { onRemove(tag) },
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove tag $tag",
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Add a tag") },
            placeholder = { Text("weeknight, vegetarian, batch cook…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    onAdd(typed)
                    typed = ""
                },
            ),
            trailingIcon = {
                if (typed.isNotBlank()) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Add tag",
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(20.dp),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        if (unused.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                unused.take(12).forEach { suggestion ->
                    AssistChip(
                        onClick = { onAdd(suggestion) },
                        label = { Text(suggestion) },
                    )
                }
            }
        }
    }
}
