package com.lucca.ko.data.repair

import android.util.Log
import com.lucca.ko.data.prefs.PlanViewRepository
import com.lucca.ko.data.repo.MealPlanRepository
import java.time.LocalDate

/**
 * Housekeeping for the fixed-week meal plan, run at launch, on every return to the foreground and
 * when the plan or Today screen opens. Both jobs are idempotent, so running them often is free.
 *
 * Lives beside [StartupRepairs] rather than in a repository because it needs both the plan table
 * and a preference, and repositories here don't reach into each other.
 */
class PlanMaintenance(
    private val mealPlanRepository: MealPlanRepository,
    private val planViewRepository: PlanViewRepository,
) {
    suspend fun run(today: LocalDate = LocalDate.now()) {
        runCatching { mealPlanRepository.convertLegacyDates(today) }
            .onFailure { Log.w(TAG, "Plan date conversion failed", it) }
        runCatching { resetTicksOnNewWeek(today) }
            .onFailure { Log.w(TAG, "Weekly tick reset failed", it) }
    }

    /**
     * "Cooked" ticks describe this week, so they come off when a new week starts; the meals stay.
     * The very first run only records the week — there is no earlier week to clear.
     */
    private suspend fun resetTicksOnNewWeek(today: LocalDate) {
        val monday = today.minusDays(today.dayOfWeek.value - 1L).toString()
        val done = planViewRepository.ticksResetFor()
        if (done == monday) return
        if (done != null) mealPlanRepository.clearCooked()
        planViewRepository.markTicksResetFor(monday)
    }

    private companion object {
        const val TAG = "PlanMaintenance"
    }
}
