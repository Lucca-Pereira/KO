package com.lucca.ko.domain.nutrition

import java.time.LocalDate

/**
 * Consecutive-day streaks, for supplements.
 *
 * Creatine has no calories worth logging; what makes it worth tracking at all is whether you
 * took it every day, because that is the entire mechanism.
 *
 * Pure JVM and unit-tested.
 */
object StreakCalculator {

    /**
     * The run of consecutive days ending today, or yesterday.
     *
     * Today not being ticked yet does **not** break the streak. At nine in the morning you have
     * not failed to take today's creatine, and an app that says "streak: 0" before breakfast is
     * simply lying about the thing it is meant to encourage.
     */
    fun currentStreak(takenDates: Set<LocalDate>, today: LocalDate): Int {
        if (takenDates.isEmpty()) return 0

        val start = when {
            today in takenDates -> today
            today.minusDays(1) in takenDates -> today.minusDays(1)
            else -> return 0
        }

        var streak = 0
        var day = start
        while (day in takenDates) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }

    fun longestStreak(takenDates: Set<LocalDate>): Int {
        if (takenDates.isEmpty()) return 0
        val sorted = takenDates.sorted()
        var longest = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i] == sorted[i - 1].plusDays(1)) run + 1 else 1
            if (run > longest) longest = run
        }
        return longest
    }

    /** How many of the last [days] were ticked — the honest number when a streak has broken. */
    fun adherence(takenDates: Set<LocalDate>, today: LocalDate, days: Int = 30): Double {
        if (days <= 0) return 0.0
        val window = (0 until days).map { today.minusDays(it.toLong()) }
        return window.count { it in takenDates }.toDouble() / days
    }
}
