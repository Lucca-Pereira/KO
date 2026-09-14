package com.lucca.ko.domain.nutrition

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One dated measurement. */
data class DatedValue(val date: LocalDate, val value: Double)

/**
 * Smoothing a weight series into something you can actually read.
 *
 * Daily bodyweight swings by a kilo or more on water alone, so the raw series says nothing about
 * whether a cut is working. An exponential moving average does, and the slope of that average is
 * what [AdaptiveTdee] uses to correct the formula's guess.
 *
 * Pure JVM and unit-tested.
 */
object WeightTrend {

    /**
     * Exponential moving average over the *daily* series, with gaps carried forward.
     *
     * Carried forward, not interpolated: a four-day gap followed by a reading 1.5 kg higher
     * should move the trend slowly, because you do not know what happened in between.
     * Interpolating would invent three measurements and make the trend look better-evidenced
     * than it is.
     */
    fun ema(points: List<DatedValue>, halfLifeDays: Int = 7): List<DatedValue> {
        if (points.isEmpty()) return emptyList()

        val sorted = points.sortedBy { it.date }
        // One reading per day; a second weigh-in on the same day replaces the first.
        val byDate = LinkedHashMap<LocalDate, Double>()
        sorted.forEach { byDate[it.date] = it.value }

        val alpha = 2.0 / (halfLifeDays + 1)
        val out = mutableListOf<DatedValue>()
        var current: Double? = null
        var day = sorted.first().date
        val last = sorted.last().date

        while (!day.isAfter(last)) {
            val reading = byDate[day]
            current = when {
                current == null -> reading
                reading == null -> current // gap: hold, do not invent a measurement
                else -> alpha * reading + (1 - alpha) * current
            }
            current?.let { out += DatedValue(day, it) }
            day = day.plusDays(1)
        }
        return out
    }

    /**
     * Least-squares slope of the smoothed series over the last [windowDays], in kg per day.
     *
     * Returns null below [MIN_POINTS] readings. A trend drawn through four data points is a
     * decoration, and feeding it into a calorie target would be worse than showing nothing.
     */
    fun slopeKgPerDay(
        smoothed: List<DatedValue>,
        windowDays: Int = 21,
        minPoints: Int = MIN_POINTS,
    ): Double? {
        if (smoothed.size < minPoints) return null

        val last = smoothed.last().date
        val window = smoothed.filter { ChronoUnit.DAYS.between(it.date, last) < windowDays }
        if (window.size < minPoints) return null

        val origin = window.first().date
        val xs = window.map { ChronoUnit.DAYS.between(origin, it.date).toDouble() }
        val ys = window.map { it.value }

        val meanX = xs.average()
        val meanY = ys.average()
        var numerator = 0.0
        var denominator = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            numerator += dx * (ys[i] - meanY)
            denominator += dx * dx
        }
        return if (denominator == 0.0) null else numerator / denominator
    }

    /** Convenience: the trend over a window, in kg per week, which is how people think about it. */
    fun slopeKgPerWeek(smoothed: List<DatedValue>, windowDays: Int = 21): Double? =
        slopeKgPerDay(smoothed, windowDays)?.times(7)

    /** The smoothed weight today, which is the number worth putting on screen. */
    fun currentTrend(smoothed: List<DatedValue>): Double? = smoothed.lastOrNull()?.value

    const val MIN_POINTS = 10
}
