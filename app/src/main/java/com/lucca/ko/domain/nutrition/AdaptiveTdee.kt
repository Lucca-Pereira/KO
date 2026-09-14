package com.lucca.ko.domain.nutrition

import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Correcting the formula's guess with what actually happened.
 *
 * Mifflin-St Jeor plus an activity multiplier is a population average applied to one person. The
 * honest number is the one your own weight implies: if you ate 2,400 a day and lost a quarter of
 * a kilo a week, you burned about 2,675.
 *
 * Pure JVM and unit-tested.
 */
object AdaptiveTdee {

    /**
     * Energy in a kilogram of body mass.
     *
     * The familiar 7,700 figure is for pure fat. Real weight change is fat, some lean tissue and
     * a lot of water, so this over-estimates on a fast cut — which is one more reason the result
     * below is blended and clamped rather than trusted outright.
     */
    const val KCAL_PER_KG = 7700.0

    /** Days of data at which the observed estimate is trusted completely. */
    const val FULL_CONFIDENCE_DAYS = 28

    /** How far the estimate may stray from the formula, either way. */
    const val CLAMP_FRACTION = 0.35

    data class Result(
        val kcal: Double,
        /** 0..1 — how much of this came from your data rather than the formula. */
        val confidence: Double,
        val daysUsed: Int,
        val daysAvailable: Int,
        val observed: Double?,
        val clamped: Boolean,
    ) {
        val isEstimate: Boolean get() = confidence > 0.0
    }

    /** One day's logged intake, for the mean. */
    data class DayIntake(val date: LocalDate, val kcal: Double)

    /**
     * Drops days that were clearly not fully logged.
     *
     * This is the correctness trap in the whole feature: one day where you logged breakfast and
     * forgot dinner drags the mean intake down, which makes the observed expenditure look higher,
     * which raises your target — the opposite of what should happen. Anything below BMR × 0.9 is
     * treated as an incomplete log rather than an extraordinary fast.
     */
    fun completeDays(days: List<DayIntake>, bmr: Double): List<DayIntake> {
        val floor = bmr * 0.9
        return days.filter { it.kcal >= floor }
    }

    /**
     * @param trendSlopeKgPerDay from [WeightTrend.slopeKgPerDay]; null when there is not enough
     *   weight data, in which case the formula stands unchanged.
     */
    fun estimate(
        formulaTdee: Double,
        trendSlopeKgPerDay: Double?,
        completeDays: List<DayIntake>,
        totalDaysAvailable: Int = completeDays.size,
    ): Result {
        if (trendSlopeKgPerDay == null || completeDays.isEmpty()) {
            return Result(
                kcal = formulaTdee.round(),
                confidence = 0.0,
                daysUsed = completeDays.size,
                daysAvailable = totalDaysAvailable,
                observed = null,
                clamped = false,
            )
        }

        val meanIntake = completeDays.map { it.kcal }.average()
        val observed = meanIntake - trendSlopeKgPerDay * KCAL_PER_KG

        // Weight the observation by how much of it there is. Two weeks of data is worth half a
        // vote, not a whole one.
        val confidence = (completeDays.size.toDouble() / FULL_CONFIDENCE_DAYS).coerceIn(0.0, 1.0)
        val blended = confidence * observed + (1 - confidence) * formulaTdee

        val low = formulaTdee * (1 - CLAMP_FRACTION)
        val high = formulaTdee * (1 + CLAMP_FRACTION)
        val clamped = blended.coerceIn(low, high)

        return Result(
            kcal = clamped.round(),
            confidence = confidence,
            daysUsed = completeDays.size,
            daysAvailable = totalDaysAvailable,
            observed = observed.round(),
            clamped = clamped != blended,
        )
    }

    /**
     * The honesty line under the number on screen.
     *
     * A calorie target derived from nineteen days out of twenty-eight should say so; the user is
     * the only one who knows whether the missing nine were days they forgot to log or days they
     * did not eat.
     */
    fun describe(result: Result): String = when {
        result.confidence <= 0.0 ->
            "From your height, weight and activity — not enough weight data yet to check it."
        result.clamped ->
            "Based on ${result.daysUsed} of ${result.daysAvailable} days, and capped: your logs " +
                "and the formula disagree by more than they should."
        result.confidence < 1.0 ->
            "Based on ${result.daysUsed} of ${result.daysAvailable} days logged — it will settle " +
                "as more come in."
        else -> "Based on ${result.daysUsed} days of logging and your weight trend."
    }
}

private fun Double.round(): Double = roundToInt().toDouble()
