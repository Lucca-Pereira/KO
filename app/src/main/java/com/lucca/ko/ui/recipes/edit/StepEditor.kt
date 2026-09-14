package com.lucca.ko.ui.recipes.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lucca.ko.domain.recipe.StepDraft

/**
 * Add, edit, remove and reorder method steps.
 *
 * An imported recipe arrives as one blob of prose; `InstructionSplitter` seeds this list the
 * first time it is opened, so the common case is correcting a split rather than typing from
 * scratch. The original prose is never overwritten.
 */
fun LazyListScope.stepEditor(
    steps: List<StepDraft>,
    onUpdate: (Long, (StepDraft) -> StepDraft) -> Unit,
    onRemove: (Long) -> Unit,
    onMove: (Long, Int) -> Unit,
    onAdd: () -> Unit,
) {
    item(key = "steps-header") {
        Text(
            "Method",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    itemsIndexed(steps, key = { _, it -> "step-${it.key}" }) { index, draft ->
        StepRowEditor(
            number = index + 1,
            draft = draft,
            isFirst = index == 0,
            isLast = index == steps.lastIndex,
            onTextChange = { value -> onUpdate(draft.key) { it.copy(text = value) } },
            onMinutesChange = { value ->
                onUpdate(draft.key) { it.copy(minutesText = value.filter(Char::isDigit)) }
            },
            onRemove = { onRemove(draft.key) },
            onMoveUp = { onMove(draft.key, -1) },
            onMoveDown = { onMove(draft.key, 1) },
        )
    }

    item(key = "steps-add") { AddRowButton("Add step", onAdd) }
}

@Composable
private fun StepRowEditor(
    number: Int,
    draft: StepDraft,
    isFirst: Boolean,
    isLast: Boolean,
    onTextChange: (String) -> Unit,
    onMinutesChange: (String) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "$number.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 20.dp),
            )
            OutlinedTextField(
                value = draft.text,
                onValueChange = onTextChange,
                label = { Text("Step $number") },
                minLines = 2,
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberField(
                value = draft.minutesText,
                onValueChange = onMinutesChange,
                label = "Minutes",
                modifier = Modifier.width(120.dp).padding(start = 24.dp),
            )
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowControls(
                    isFirst = isFirst,
                    isLast = isLast,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onRemove = onRemove,
                    removeDescription = "Remove step $number",
                )
            }
        }
    }
}
