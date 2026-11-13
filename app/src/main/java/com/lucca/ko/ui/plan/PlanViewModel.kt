package com.lucca.ko.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.relations.PlannedRecipe
import com.lucca.ko.data.prefs.PlanView
import com.lucca.ko.data.prefs.PlanViewRepository
import com.lucca.ko.data.repair.PlanMaintenance
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.domain.plan.FixedWeek
import com.lucca.ko.ui.koFactory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DayPlan(
    /** The slot date this day is stored under — see [FixedWeek]. Never shown. */
    val date: LocalDate,
    val weekdayLabel: String,
    val isToday: Boolean,
    val dishesBySlot: List<Pair<MealSlot, List<PlannedRecipe>>>,
)

data class PlanUiState(
    val days: List<DayPlan> = emptyList(),
    /** 1 or 2: how long the plan is. */
    val weeks: Int = 1,
    /** Which week is on screen, and counts as "this week" for the Today screen. */
    val activeWeek: Int = 1,
)

fun MealSlot.label(): String = when (this) {
    MealSlot.BREAKFAST -> "Breakfast"
    MealSlot.LUNCH -> "Lunch"
    MealSlot.DINNER -> "Dinner"
    MealSlot.OTHER -> "Other"
}

fun DayOfWeek.label(style: TextStyle = TextStyle.FULL): String =
    getDisplayName(style, Locale.getDefault())

@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModel(
    private val repo: MealPlanRepository,
    private val planViewRepository: PlanViewRepository,
    private val maintenance: PlanMaintenance,
) : ViewModel() {

    init {
        // The app can stay open across a Monday; opening the plan is when stale ticks would show.
        viewModelScope.launch { maintenance.run() }
    }

    val state = planViewRepository.view
        .flatMapLatest { view ->
            repo.weekPlan(view.activeWeek).map { planned -> buildState(view, planned) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanUiState())

    private fun buildState(view: PlanView, planned: List<PlannedRecipe>): PlanUiState {
        val today = LocalDate.now().dayOfWeek
        val days = DayOfWeek.entries.map { day ->
            val date = FixedWeek.dateFor(view.activeWeek, day)
            val forDay = planned.filter { it.entry.date == date.toString() }
            DayPlan(
                date = date,
                weekdayLabel = day.label(),
                isToday = day == today,
                dishesBySlot = MealSlot.entries
                    .map { slot -> slot to forDay.filter { it.entry.slot == slot } }
                    .filter { it.second.isNotEmpty() },
            )
        }
        return PlanUiState(days = days, weeks = view.weeks, activeWeek = view.activeWeek)
    }

    fun setWeeks(weeks: Int) {
        viewModelScope.launch { planViewRepository.set(weeks = weeks) }
    }

    fun setActiveWeek(week: Int) {
        viewModelScope.launch { planViewRepository.set(activeWeek = week) }
    }

    /** Removes the planned meal only — the recipe stays in the library. */
    fun removeEntry(id: Long) = viewModelScope.launch { repo.removePlanEntry(id) }

    /** Ticking this also bumps the recipe's own cooked count and last-cooked date. */
    fun setCooked(id: Long, cooked: Boolean) = viewModelScope.launch { repo.setCooked(id, cooked) }

    fun setServings(id: Long, servings: Double) =
        viewModelScope.launch { repo.setServings(id, servings) }

    fun move(id: Long, date: LocalDate, slot: MealSlot) =
        viewModelScope.launch { repo.move(id, date, slot) }

    companion object {
        val Factory = koFactory {
            PlanViewModel(it.mealPlanRepository, it.planViewRepository, it.planMaintenance)
        }
    }
}
