package com.lucca.ko.ui.nutrition

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lucca.ko.data.db.FoodItem
import com.lucca.ko.data.db.LogSlot
import com.lucca.ko.data.repo.toPer100g
import com.lucca.ko.domain.nutrition.PortionMath

/**
 * The portion picker.
 *
 * Grams with quick presets, plus the food's own serving when it declares one. The macros update
 * as you type, because the whole question being answered is "how much of my day does this cost",
 * and making someone log it first to find out is the wrong order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogEntrySheet(
    food: FoodItem,
    initialSlot: LogSlot,
    onDismiss: () -> Unit,
    onLog: (grams: Double, slot: LogSlot) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var gramsText by remember {
        mutableStateOf(food.servingGrams?.toInt()?.toString() ?: "100")
    }
    var slot by remember { mutableStateOf(initialSlot) }

    val grams = gramsText.toDoubleOrNull() ?: 0.0
    val preview = PortionMath.macrosFor(food.toPer100g(), grams)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                listOfNotNull(food.brand, food.name).joinToString(" "),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "${food.kcalPer100.toInt()} kcal per 100 g",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            DecimalField(gramsText, { gramsText = it }, "Grams")

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                food.servingGrams?.let { serving ->
                    AssistChip(
                        onClick = { gramsText = serving.toInt().toString() },
                        label = { Text(food.servingLabel ?: "1 serving") },
                    )
                    AssistChip(
                        onClick = { gramsText = (serving * 2).toInt().toString() },
                        label = { Text("2×") },
                    )
                }
                listOf(30, 50, 100, 150, 200, 250).forEach { preset ->
                    AssistChip(
                        onClick = { gramsText = preset.toString() },
                        label = { Text("$preset g") },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogSlot.entries.filter { it != LogSlot.SUPPLEMENT }.forEach { candidate ->
                    FilterChip(
                        selected = slot == candidate,
                        onClick = { slot = candidate },
                        label = { Text(candidate.label()) },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${preview.kcal.toInt()} kcal",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "P ${preview.proteinG.toInt()} · C ${preview.carbsG.toInt()} · " +
                        "F ${preview.fatG.toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = { onLog(grams, slot) },
                enabled = grams > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Log it") }
        }
    }
}
