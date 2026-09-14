package com.lucca.ko.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * The calorie ring.
 *
 * Deliberately allowed past full, turning a different colour rather than stopping: a ring that
 * caps at 100% cannot tell you that you are 400 over, which is the thing you most need to know.
 */
@Composable
fun CalorieRing(
    consumed: Double,
    target: Double,
    modifier: Modifier = Modifier,
    size: Int = 150,
) {
    val progress = if (target > 0) (consumed / target).toFloat() else 0f
    val animated by animateFloatAsState(progress.coerceAtMost(2f), label = "calorie-ring")
    val over = progress > 1f

    val trackColour = MaterialTheme.colorScheme.surfaceVariant
    val arcColour = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val remaining = target - consumed

    Box(modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size.dp)) {
            val stroke = (size * 0.09f).dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(
                color = trackColour,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = arcColour,
                startAngle = -90f,
                sweepAngle = 360f * min(animated, 1f),
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // The overflow rides on top of the full ring, so "20% over" is visible as such.
            if (animated > 1f) {
                drawArc(
                    color = arcColour.copy(alpha = 0.45f),
                    startAngle = -90f,
                    sweepAngle = 360f * (animated - 1f),
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                consumed.toInt().toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (target > 0) "of ${target.toInt()} kcal" else "kcal",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (target > 0) {
                Text(
                    if (over) "${(-remaining).toInt()} over" else "${remaining.toInt()} left",
                    style = MaterialTheme.typography.labelMedium,
                    color = arcColour,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** One macro's bar: grams eaten against grams targeted. */
@Composable
fun MacroBar(
    label: String,
    consumed: Double,
    target: Double,
    colour: Color,
    modifier: Modifier = Modifier,
) {
    val progress = if (target > 0) (consumed / target).toFloat() else 0f
    val animated by animateFloatAsState(progress.coerceIn(0f, 1.5f), label = "macro-$label")
    val track = MaterialTheme.colorScheme.surfaceVariant

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                if (target > 0) {
                    "${consumed.toInt()} / ${target.toInt()} g"
                } else {
                    "${consumed.toInt()} g"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val radius = size.height / 2
            drawRoundRect(
                color = track,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
            )
            if (animated > 0f) {
                drawRoundRect(
                    color = if (animated > 1f) colour.copy(alpha = 0.7f) else colour,
                    size = Size(size.width * min(animated, 1f), size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
                )
            }
        }
    }
}

/** Protein, carbs and fat side by side under the calorie ring. */
@Composable
fun MacroBars(
    proteinG: Double,
    carbsG: Double,
    fatG: Double,
    proteinTarget: Double,
    carbsTarget: Double,
    fatTarget: Double,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MacroBar("Protein", proteinG, proteinTarget, scheme.primary)
        MacroBar("Carbs", carbsG, carbsTarget, scheme.tertiary)
        MacroBar("Fat", fatG, fatTarget, scheme.secondary)
    }
}

/** A labelled number, for the small stat rows. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
