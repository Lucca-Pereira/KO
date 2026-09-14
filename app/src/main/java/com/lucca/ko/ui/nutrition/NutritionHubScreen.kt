package com.lucca.ko.ui.nutrition

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucca.ko.data.db.FoodItem
import com.lucca.ko.data.db.LogSlot
import com.lucca.ko.data.repo.NutritionRepository
import com.lucca.ko.ui.koFactory
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * The gym side, as three internal tabs rather than three navigation destinations.
 *
 * Internal because back should leave the hub rather than walk you through Foods and Body on the
 * way out — these are views of one thing, not separate places.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionHubScreen(
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSupplements: () -> Unit,
    onNewFood: () -> Unit,
    logVm: LogActionsViewModel = viewModel(factory = LogActionsViewModel.Factory),
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pendingFood by rememberSaveable(stateSaver = FoodPickSaver) {
        androidx.compose.runtime.mutableStateOf<FoodPick?>(null)
    }
    val scope = rememberCoroutineScope()

    pendingFood?.let { pick ->
        val food = pick.food
        if (food != null) {
            LogEntrySheet(
                food = food,
                initialSlot = pick.slot,
                onDismiss = { pendingFood = null },
                onLog = { grams, slot ->
                    scope.launch { logVm.log(food, grams, slot) }
                    pendingFood = null
                    // Back to Today, where the rings have just moved.
                    tab = 0
                },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nutrition") },
                actions = {
                    IconButton(onClick = onOpenSupplements) { Text("Supps") }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }

            when (tab) {
                0 -> TodayScreen(
                    onSetUpProfile = onOpenProfile,
                    onLogFood = { slot ->
                        pendingFood = FoodPick(slot = slot, food = null)
                        tab = 2 // send them to Foods to pick one
                    },
                )
                1 -> BodyScreen(onEditProfile = onOpenProfile)
                else -> FoodListScreen(
                    onPickFood = { food ->
                        pendingFood = FoodPick(slot = pendingFood?.slot ?: defaultSlot(), food = food)
                    },
                    onNewFood = onNewFood,
                )
            }
        }
    }
}

private val TABS = listOf("Today", "Body", "Foods")

/** What the user is part-way through logging. */
data class FoodPick(val slot: LogSlot, val food: FoodItem?)

/**
 * Only the slot survives a process death; the food is re-picked.
 *
 * A half-finished log is not worth the machinery of persisting a whole food row through
 * `rememberSaveable`, and restoring one silently would be stranger than asking again.
 */
private val FoodPickSaver = androidx.compose.runtime.saveable.Saver<FoodPick?, String>(
    save = { it?.slot?.name ?: "" },
    restore = { name ->
        name.takeIf { it.isNotBlank() }
            ?.let { runCatching { LogSlot.valueOf(it) }.getOrNull() }
            ?.let { FoodPick(it, null) }
    },
)

/** Guesses the meal from the clock, so the common case needs no tapping. */
private fun defaultSlot(): LogSlot = when (java.time.LocalTime.now().hour) {
    in 5..10 -> LogSlot.BREAKFAST
    in 11..15 -> LogSlot.LUNCH
    in 16..21 -> LogSlot.DINNER
    else -> LogSlot.SNACK
}

/** The one write the hub itself performs. */
class LogActionsViewModel(private val nutrition: NutritionRepository) :
    androidx.lifecycle.ViewModel() {

    suspend fun log(food: FoodItem, grams: Double, slot: LogSlot) {
        nutrition.logFood(food, grams, LocalDate.now(), slot)
    }

    companion object {
        val Factory = koFactory { LogActionsViewModel(it.nutritionRepository) }
    }
}
