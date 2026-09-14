package com.lucca.ko.ui.recipes.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucca.ko.domain.recipe.IngredientDraft

/**
 * Add, edit, remove and reorder ingredient lines.
 *
 * One free-text "amount" field per line rather than a number box plus a unit dropdown: recipes
 * are written as "1 1/2 cups" and "a pinch", people type them that way, and `MeasureParser`
 * turns that into a quantity and a unit on save. The parse is echoed under the field so it is
 * visible when it does not understand something — and the original text is stored either way,
 * so a failed parse costs nothing.
 */
fun LazyListScope.ingredientEditor(
    ingredients: List<IngredientDraft>,
    onUpdate: (Long, (IngredientDraft) -> IngredientDraft) -> Unit,
    onRemove: (Long) -> Unit,
    onMove: (Long, Int) -> Unit,
    onAdd: () -> Unit,
) {
    item(key = "ingredients-header") {
        Text(
            "Ingredients",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    itemsIndexed(ingredients, key = { _, it -> "ing-${it.key}" }) { index, draft ->
        IngredientRowEditor(
            draft = draft,
            isFirst = index == 0,
            isLast = index == ingredients.lastIndex,
            onNameChange = { value -> onUpdate(draft.key) { it.copy(name = value) } },
            onAmountChange = { value -> onUpdate(draft.key) { it.copy(amount = value) } },
            onRemove = { onRemove(draft.key) },
            onMoveUp = { onMove(draft.key, -1) },
            onMoveDown = { onMove(draft.key, 1) },
        )
    }

    item(key = "ingredients-add") { AddRowButton("Add ingredient", onAdd) }
}

@Composable
private fun IngredientRowEditor(
    draft: IngredientDraft,
    isFirst: Boolean,
    isLast: Boolean,
    onNameChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft.amount,
                onValueChange = onAmountChange,
                label = { Text("Amount") },
                placeholder = { Text("200 g") },
                singleLine = true,
                modifier = Modifier.weight(0.42f),
            )
            OutlinedTextField(
                value = draft.name,
                onValueChange = onNameChange,
                label = { Text("Ingredient") },
                singleLine = true,
                modifier = Modifier.weight(0.58f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val parsed = draft.parsed
            Text(
                when {
                    draft.amount.isBlank() -> ""
                    parsed == null -> "Kept as written"
                    else -> listOfNotNull(
                        parsed.quantity?.let { q ->
                            if (q == q.toLong().toDouble()) q.toLong().toString() else q.toString()
                        },
                        parsed.unit,
                    ).joinToString(" ").ifBlank { "Kept as written" }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            RowControls(
                isFirst = isFirst,
                isLast = isLast,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRemove = onRemove,
                removeDescription = "Remove ${draft.name.ifBlank { "ingredient" }}",
            )
        }
    }
}

/** Up / down / remove, shared by the ingredient and step editors. */
@Composable
internal fun RowControls(
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    removeDescription: String,
) {
    IconButton(onClick = onMoveUp, enabled = !isFirst, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.Filled.KeyboardArrowUp,
            contentDescription = "Move up",
            modifier = Modifier.size(20.dp),
        )
    }
    IconButton(onClick = onMoveDown, enabled = !isLast, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = "Move down",
            modifier = Modifier.size(20.dp),
        )
    }
    IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.Filled.Close,
            contentDescription = removeDescription,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
