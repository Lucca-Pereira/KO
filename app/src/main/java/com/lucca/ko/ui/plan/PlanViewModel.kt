package com.lucca.ko.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.relations.PlannedRecipe
import com.lucca.ko.data.prefs.PlanView
import com.lucca.ko.data.prefs.PlanViewRepository
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.ui.koFactory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DayPlan(
    val date: LocalDate,
    val weekdayLabel: String,
    val dateLabel: String,
    val isToday: Boolean,
    val dishesBySlot: List<Pair<MealSlot, List<PlannedRecipe>>>,
)

data class PlanUiState(
    val weekStart: LocalDate = LocalDate.now(),
    val rangeLabel: String = "",
    val isCurrentWeek: Boolean = true,
    val days: List<DayPlan> = emptyList(),
    /** 1 or 2: how many weeks the screen shows at once. */
    val weeks: Int = 1,
    /** False pins the screen to the current week(s), with no paging to others. */
    val calendar: Boolean = true,
)

fun MealSlot.label(): String = when (this) {
    MealSlot.BREAKFAST -> "Breakfast"
    MealSlot.LUNCH -> "Lunch"
    MealSlot.DINNER -> "Dinner"
    MealSlot.OTHER -> "Other"
}

private fun mondayOf(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

private fun shortDate(date: LocalDate): String =
    "${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"

@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModel(
    private val repo: MealPlanRepository,
    private val planViewRepository: PlanViewRepository,
) : ViewModel() {

    private val weekStart = MutableStateFlow(mondayOf(LocalDate.now()))

    val state = combine(weekStart, planViewRepository.view) { start, view ->
        // Without the calendar there is nothing to page to, so the screen is always this week.
        (if (view.calendar) start else mondayOf(LocalDate.now())) to view
    }
        .flatMapLatest { (start, view) ->
            repo.weekPlan(start, view.weeks).map { planned -> buildState(start, view, planned) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanUiState())

    private fun buildState(start: LocalDate, view: PlanView, planned: List<PlannedRecipe>): PlanUiState {
        val today = LocalDate.now()
        val spanDays = 7L * view.weeks
        val days = (0 until spanDays).map { offset ->
            val date = start.plusDays(offset)
            val forDay = planned.filter { it.entry.date == date.toString() }
            DayPlan(
                date = date,
                weekdayLabel = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                dateLabel = shortDate(date),
                isToday = date == today,
                dishesBySlot = MealSlot.entries
                    .map { slot -> slot to forDay.filter { it.entry.slot == slot } }
                    .filter { it.second.isNotEmpty() },
            )
        }
        return PlanUiState(
            weekStart = start,
            rangeLabel = "${shortDate(start)} – ${shortDate(start.plusDays(spanDays - 1))}",
            isCurrentWeek = start == mondayOf(today),
            days = days,
            weeks = view.weeks,
            calendar = view.calendar,
        )
    }

    // Paging moves by the whole span, so a biweekly plan steps a fortnight at a time.
    fun nextWeek() { weekStart.value = weekStart.value.plusWeeks(state.value.weeks.toLong()) }
    fun prevWeek() { weekStart.value = weekStart.value.minusWeeks(state.value.weeks.toLong()) }
    fun goToday() { weekStart.value = mondayOf(LocalDate.now()) }

    fun setWeeks(weeks: Int) {
        viewModelScope.launch { planViewRepository.set(weeks = weeks) }
    }

    fun setCalendar(calendar: Boolean) {
        goToday()
        viewModelScope.launch { planViewRepository.set(calendar = calendar) }
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
        val Factory = koFactory { PlanViewModel(it.mealPlanRepository, it.planViewRepository) }
    }
}
