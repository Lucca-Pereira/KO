package com.lucca.ko.ui.recipes.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lucca.ko.domain.recipe.RecipeDiff

/** The small "the bot suggests a change" card that sits under an assistant message. */
@Composable
fun ProposalChip(
    summary: String,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Suggested change",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                summary.ifBlank { "An edit to this recipe" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onClick = onReview, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) {
                Text("Review")
            }
        }
    }
}

/**
 * The full diff, before anything is applied.
 *
 * This dialog is the safeguard. A 7B asked to make a recipe dairy-free will swap the cream and
 * leave the butter, and that is invisible in a one-line summary — so the change is shown line by
 * line, in the recipe's own words, and nothing is written until you say so.
 */
@Composable
fun ProposalDialog(
    summary: String,
    diff: RecipeDiff.Result,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply this change?") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (summary.isNotBlank()) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }

                if (!diff.hasChanges) {
                    Text(
                        "Nothing would actually change.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                diff.title?.let { DiffRow("Title", it) }
                diff.servings?.let { DiffRow("Servings", it) }

                val ingredients = diff.ingredients.filter { it.change != RecipeDiff.Change.UNCHANGED }
                if (ingredients.isNotEmpty()) {
                    Text(
                        "Ingredients",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ingredients.forEach { DiffLine(it) }
                    val unchanged = diff.ingredients.count { it.change == RecipeDiff.Change.UNCHANGED }
                    if (unchanged > 0) {
                        Text(
                            "$unchanged other ingredient${if (unchanged == 1) "" else "s"} unchanged",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val steps = diff.steps.filter { it.change != RecipeDiff.Change.UNCHANGED }
                if (steps.isNotEmpty()) {
                    Text(
                        "Method",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    steps.forEach { DiffLine(it) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept, enabled = diff.hasChanges) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onReject) { Text("Discard") } },
    )
}

@Composable
private fun DiffRow(label: String, line: RecipeDiff.Line) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        DiffLine(line, Modifier.weight(1f))
    }
}

@Composable
private fun DiffLine(line: RecipeDiff.Line, modifier: Modifier = Modifier) {
    val colours = MaterialTheme.colorScheme
    // Colour alone would not survive a colour-blind reader, so each line is also prefixed.
    val (prefix, colour) = when (line.change) {
        RecipeDiff.Change.ADDED -> "+ " to colours.primary
        RecipeDiff.Change.REMOVED -> "− " to colours.error
        RecipeDiff.Change.CHANGED -> "~ " to colours.tertiary
        RecipeDiff.Change.UNCHANGED -> "  " to colours.onSurfaceVariant
    }

    Column(modifier.fillMaxWidth()) {
        if (line.change == RecipeDiff.Change.CHANGED && line.before != null) {
            Text(
                "  ${line.before}",
                style = MaterialTheme.typography.bodySmall,
                color = colours.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough,
            )
        }
        Text(
            prefix + line.text,
            style = MaterialTheme.typography.bodyMedium,
            color = colour,
            textDecoration = if (line.change == RecipeDiff.Change.REMOVED) {
                TextDecoration.LineThrough
            } else {
                null
            },
            modifier = if (line.change == RecipeDiff.Change.UNCHANGED) {
                Modifier
            } else {
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(colour.copy(alpha = 0.08f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            },
        )
    }
}

/** The colour used for a decided proposal's status line. */
@Composable
internal fun statusColour(accepted: Boolean): Color =
    if (accepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
