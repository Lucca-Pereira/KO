package com.lucca.ko.ui.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.db.BodyMetric
import com.lucca.ko.ui.common.LoadingBox
import com.lucca.ko.ui.common.StatTile
import com.lucca.ko.ui.common.WeightChart
import java.time.LocalDate
import kotlin.math.abs

@Composable
fun BodyScreen(
    onEditProfile: () -> Unit,
    vm: BodyViewModel = viewModel(factory = BodyViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<LocalDate?>(null) }

    if (state.loading) {
        LoadingBox()
        return
    }

    editing?.let { date ->
        WeighInDialog(
            date = date,
            existing = state.entries.firstOrNull { it.date == date.toString() },
            onDismiss = { editing = null },
            onSave = { vm.save(it); editing = null },
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatTile(
                            label = "Trend weight",
                            value = state.trend.currentTrendKg?.let { "%.1f kg".format(it) } ?: "—",
                            hint = state.trend.raw.lastOrNull()
                                ?.let { "last weigh-in %.1f".format(it.value) },
                        )
                        StatTile(
                            label = "Change",
                            value = state.trend.changePerWeekKg
                                ?.let { "%+.2f kg/wk".format(it) } ?: "—",
                            hint = state.trend.changePerWeekKg?.let { describeRate(it) },
                        )
                    }
                    // The raw readings as dots, the trend as a line: hiding the readings would
                    // make the trend look better-evidenced than it is.
                    WeightChart(raw = state.trend.raw, smoothed = state.trend.smoothed)
                    if (!state.trend.hasEnoughData) {
                        Text(
                            "A trend needs about ten weigh-ins before it means anything. " +
                                "Daily weight swings a kilo on water alone.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { editing = LocalDate.now() }) {
                        Text("Log today's weigh-in")
                    }
                }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Daily energy", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.tdee?.targets?.kcal?.toInt()?.let { "$it kcal target" } ?: "Not set up",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        state.tdee?.explanation
                            ?: "Add your height, weight and goal to get a target.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = onEditProfile) { Text("Edit profile") }
                }
            }
        }

        if (state.entries.isNotEmpty()) {
            item {
                Text("History", style = MaterialTheme.typography.titleSmall)
            }
            items(state.entries, key = { it.id }) { metric ->
                MetricRow(metric, onDelete = { vm.delete(metric.id) })
            }
        }
    }
}

@Composable
private fun MetricRow(metric: BodyMetric, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(metric.date, style = MaterialTheme.typography.bodyMedium)
            Text(
                buildList {
                    metric.weightKg?.let { add("%.1f kg".format(it)) }
                    metric.bodyFatPct?.let { add("%.1f%% fat".format(it)) }
                    metric.waistCm?.let { add("waist %.0f".format(it)) }
                }.joinToString(" · ").ifEmpty { "No measurements" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Delete ${metric.date}",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WeighInDialog(
    date: LocalDate,
    existing: BodyMetric?,
    onDismiss: () -> Unit,
    onSave: (BodyMetric) -> Unit,
) {
    var weight by remember { mutableStateOf(existing?.weightKg?.toString().orEmpty()) }
    var bodyFat by remember { mutableStateOf(existing?.bodyFatPct?.toString().orEmpty()) }
    var waist by remember { mutableStateOf(existing?.waistCm?.toString().orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weigh-in for $date") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField(weight, { weight = it }, "Weight (kg)")
                DecimalField(bodyFat, { bodyFat = it }, "Body fat % (optional)")
                DecimalField(waist, { waist = it }, "Waist cm (optional)")
                Text(
                    "Weigh yourself at the same time each day — first thing, before eating — or " +
                        "the trend is measuring your breakfast.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = weight.toDoubleOrNull() != null,
                onClick = {
                    onSave(
                        BodyMetric(
                            id = existing?.id ?: 0,
                            date = date.toString(),
                            weightKg = weight.toDoubleOrNull(),
                            bodyFatPct = bodyFat.toDoubleOrNull(),
                            waistCm = waist.toDoubleOrNull(),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun DecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        // Accept a comma as well as a point: half of Europe types it that way, and rejecting
        // the keystroke silently is the most annoying possible response.
        onValueChange = { input ->
            onValueChange(input.replace(',', '.').filter { it.isDigit() || it == '.' })
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

private fun describeRate(kgPerWeek: Double): String = when {
    abs(kgPerWeek) < 0.05 -> "holding steady"
    kgPerWeek < 0 -> "losing"
    else -> "gaining"
}
