package com.lucca.ko.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lucca.ko.data.remote.MealSummary

/**
 * A TheMealDB search hit. Lives here because the suggestion and search screens both show one and
 * had drifted into two near-identical private copies.
 */
@Composable
fun MealRow(
    meal: MealSummary,
    saving: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = !saving, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RecipeThumbnail(model = meal.thumbUrl, size = 56)
        Text(meal.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (saving) CircularProgressIndicator(Modifier.size(20.dp))
    }
}

/**
 * A square recipe image, falling back to a placeholder rather than an empty hole — most manual
 * recipes never get a photo, and a ragged list reads worse than a plain one.
 */
@Composable
fun RecipeThumbnail(
    model: Any?,
    size: Int,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val hasImage = when (model) {
        null -> false
        is String -> model.isNotBlank()
        else -> true
    }
    if (hasImage) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size.dp).clip(shape),
        )
    } else {
        Box(
            modifier
                .size(size.dp)
                .clip(shape)
                .then(Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Restaurant,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size((size * 0.45f).dp),
            )
        }
    }
}

/** "45 min · 4 servings · cooked 3×" — whichever of those are actually known. */
@Composable
fun RecipeMetaLine(
    totalMinutes: Int?,
    servings: Int?,
    timesCooked: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val parts = buildList {
        totalMinutes?.takeIf { it > 0 }?.let { add(formatMinutes(it)) }
        servings?.takeIf { it > 0 }?.let { add("$it serving${if (it == 1) "" else "s"}") }
        if (timesCooked > 0) add("cooked $timesCooked×")
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier,
    )
}

fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}
