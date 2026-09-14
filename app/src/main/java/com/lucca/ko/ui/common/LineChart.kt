package com.lucca.ko.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.lucca.ko.domain.nutrition.DatedValue
import java.time.temporal.ChronoUnit

/**
 * A weight chart: readings as dots, the smoothed trend as a line.
 *
 * Hand-rolled Canvas rather than a charting library. This draws two series with no zoom, pan or
 * tooltips, which is about a hundred lines here against a dependency and its API to learn — and
 * the one thing that actually matters, showing the raw readings *and* the trend together, is
 * easier to get right by drawing it than by configuring it.
 */
@Composable
fun WeightChart(
    raw: List<DatedValue>,
    smoothed: List<DatedValue>,
    modifier: Modifier = Modifier,
    height: Int = 180,
) {
    if (raw.isEmpty()) {
        Box(
            modifier.fillMaxWidth().height(height.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No weigh-ins yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val dotColour = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val lineColour = MaterialTheme.colorScheme.primary
    val gridColour = MaterialTheme.colorScheme.outlineVariant

    val all = raw + smoothed
    val minValue = all.minOf { it.value }
    val maxValue = all.maxOf { it.value }
    // A flat series would otherwise divide by zero and, worse, draw a line through the middle
    // that implies precision it does not have.
    val span = (maxValue - minValue).takeIf { it > 0.1 } ?: 1.0
    val padded = minValue - span * 0.1 to maxValue + span * 0.1

    val firstDate = all.minOf { it.date }
    val lastDate = all.maxOf { it.date }
    val totalDays = ChronoUnit.DAYS.between(firstDate, lastDate).toDouble().takeIf { it > 0 } ?: 1.0

    Canvas(modifier.fillMaxWidth().height(height.dp).padding(vertical = 8.dp)) {
        fun x(point: DatedValue): Float =
            (ChronoUnit.DAYS.between(firstDate, point.date) / totalDays).toFloat() * size.width

        fun y(point: DatedValue): Float {
            val fraction = (point.value - padded.first) / (padded.second - padded.first)
            return size.height - fraction.toFloat() * size.height
        }

        // Three gridlines: enough to read a value off, few enough not to be furniture.
        repeat(3) { i ->
            val yPos = size.height * (i + 1) / 4f
            drawLine(gridColour, Offset(0f, yPos), Offset(size.width, yPos), strokeWidth = 1f)
        }

        raw.forEach { point ->
            drawCircle(dotColour, radius = 3.dp.toPx(), center = Offset(x(point), y(point)))
        }

        if (smoothed.size >= 2) {
            val path = Path().apply {
                moveTo(x(smoothed.first()), y(smoothed.first()))
                smoothed.drop(1).forEach { lineTo(x(it), y(it)) }
            }
            drawPath(
                path = path,
                color = lineColour,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/** A bare sparkline, for a small trend next to a number. */
@Composable
fun Sparkline(
    values: List<Double>,
    modifier: Modifier = Modifier,
    colour: Color = MaterialTheme.colorScheme.primary,
) {
    if (values.size < 2) return
    val minValue = values.min()
    val span = (values.max() - minValue).takeIf { it > 0.001 } ?: 1.0

    Canvas(modifier) {
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = step * index
            val y = size.height - ((value - minValue) / span).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, colour, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}
