package com.lucca.ko.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.relations.PlannedRecipe
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.ui.koFactory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class PlanViewModel(private val repo: MealPlanRepository) : ViewModel() {

    private val weekStart = MutableStateFlow(mondayOf(LocalDate.now()))

    val state = weekStart
        .flatMapLatest { start ->
            repo.weekPlan(start).map { planned -> buildState(start, planned) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanUiState())

    private fun buildState(start: LocalDate, planned: List<PlannedRecipe>): PlanUiState {
        val today = LocalDate.now()
        val days = (0..6L).map { offset ->
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
            rangeLabel = "${shortDate(start)} – ${shortDate(start.plusDays(6))}",
            isCurrentWeek = start == mondayOf(today),
            days = days,
        )
    }

    fun nextWeek() { weekStart.value = weekStart.value.plusWeeks(1) }
    fun prevWeek() { weekStart.value = weekStart.value.minusWeeks(1) }
    fun goToday() { weekStart.value = mondayOf(LocalDate.now()) }

    /** Removes the planned meal only — the recipe stays in the library. */
    fun removeEntry(id: Long) = viewModelScope.launch { repo.removePlanEntry(id) }

    /** Ticking this also bumps the recipe's own cooked count and last-cooked date. */
    fun setCooked(id: Long, cooked: Boolean) = viewModelScope.launch { repo.setCooked(id, cooked) }

    fun setServings(id: Long, servings: Double) =
        viewModelScope.launch { repo.setServings(id, servings) }

    fun move(id: Long, date: LocalDate, slot: MealSlot) =
        viewModelScope.launch { repo.move(id, date, slot) }

    companion object {
        val Factory = koFactory { PlanViewModel(it.mealPlanRepository) }
    }
}
