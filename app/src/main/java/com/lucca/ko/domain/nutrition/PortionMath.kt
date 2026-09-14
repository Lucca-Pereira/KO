package com.lucca.ko.domain.nutrition

import kotlin.math.round

/** A food's macros, per 100 g — the one shape everything is canonicalised to. */
data class Per100g(
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double? = null,
)

/**
 * Turning "how much of it" into macros.
 *
 * Everything is stored per 100 g, including what Open Food Facts returns, so this is one
 * function instead of a special case per source.
 *
 * Pure JVM and unit-tested.
 */
object PortionMath {

    /** Grams per unit for the amounts a food diary sees. Volumes assume water-like density. */
    private val gramsPerUnit = mapOf(
        "g" to 1.0,
        "kg" to 1000.0,
        "mg" to 0.001,
        "ml" to 1.0,
        "l" to 1000.0,
        "oz" to 28.35,
        "lb" to 453.6,
        "tsp" to 5.0,
        "tbsp" to 15.0,
        "cup" to 240.0,
    )

    fun macrosFor(food: Per100g, grams: Double): MacroTotals {
        val factor = grams / 100.0
        return MacroTotals(
            kcal = (food.kcal * factor).round1(),
            proteinG = (food.proteinG * factor).round1(),
            carbsG = (food.carbsG * factor).round1(),
            fatG = (food.fatG * factor).round1(),
            fiberG = ((food.fiberG ?: 0.0) * factor).round1(),
        )
    }

    /**
     * Converts an amount to grams, or null when there is nothing to go on.
     *
     * A bare count ("2 scoops") only works when the food declares a serving weight. Guessing
     * that an unknown "2" means 200 g would put invented calories in a food diary, which is
     * worse than an obviously missing number.
     */
    fun gramsFor(quantity: Double, unit: String?, servingGrams: Double? = null): Double? {
        val key = unit?.trim()?.lowercase().orEmpty()
        gramsPerUnit[key]?.let { return quantity * it }
        if (key.isEmpty() || key in COUNT_UNITS) {
            return servingGrams?.let { quantity * it }
        }
        return null
    }

    /** How many of the food's own servings a weight comes to. */
    fun servingsFor(grams: Double, servingGrams: Double?): Double? =
        servingGrams?.takeIf { it > 0 }?.let { (grams / it).round2() }

    fun scale(totals: MacroTotals, factor: Double): MacroTotals = MacroTotals(
        kcal = (totals.kcal * factor).round1(),
        proteinG = (totals.proteinG * factor).round1(),
        carbsG = (totals.carbsG * factor).round1(),
        fatG = (totals.fatG * factor).round1(),
        fiberG = (totals.fiberG * factor).round1(),
    )

    /**
     * The share of a target a day's eating covers, uncapped.
     *
     * Deliberately allowed past 1.0: a ring that stops at full cannot tell you that you are 400
     * over, and that is the thing you most need to know.
     */
    fun progress(consumed: Double, target: Double): Double =
        if (target <= 0) 0.0 else consumed / target

    private val COUNT_UNITS = setOf(
        "scoop", "serving", "servings", "piece", "pieces", "whole",
        "slice", "slices", "clove", "cloves", "large", "medium", "small",
        "capsule", "tablet", "bar", "bottle", "can", "pot", "sachet",
    )
}

private fun Double.round1(): Double = round(this * 10) / 10
private fun Double.round2(): Double = round(this * 100) / 100
