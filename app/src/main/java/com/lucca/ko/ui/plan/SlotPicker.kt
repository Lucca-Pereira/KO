package com.lucca.ko.ui.plan

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lucca.ko.KoApp
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.prefs.PlanView
import java.time.DayOfWeek
import java.time.format.TextStyle

/**
 * The plan layout (one week or two, and which is active), for the few composables outside the
 * plan screen that need to offer "which day?" without a ViewModel of their own.
 */
@Composable
fun rememberPlanView(): PlanView {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { (context as KoApp).container.planViewRepository }
    val view by repository.view.collectAsState(initial = PlanView())
    return view
}

/**
 * Chips for picking a place in the fixed plan: the week (only for a two-week plan), the weekday
 * and the meal. Shared by "add to plan" on a recipe and "move" on the plan itself.
 */
@Composable
fun SlotPickerFields(
    weeks: Int,
    week: Int,
    onWeek: (Int) -> Unit,
    day: DayOfWeek,
    onDay: (DayOfWeek) -> Unit,
    slot: MealSlot,
    onSlot: (MealSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (weeks > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..weeks).forEach { w ->
                    FilterChip(selected = week == w, onClick = { onWeek(w) }, label = { Text("Week $w") })
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DayOfWeek.entries.forEach { d ->
                FilterChip(
                    selected = day == d,
                    onClick = { onDay(d) },
                    label = { Text(d.label(TextStyle.SHORT)) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MealSlot.entries.forEach { s ->
                FilterChip(selected = slot == s, onClick = { onSlot(s) }, label = { Text(s.label()) })
            }
        }
    }
}
