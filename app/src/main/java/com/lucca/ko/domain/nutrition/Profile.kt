package com.lucca.ko.domain.nutrition

import kotlinx.serialization.Serializable

@Serializable
enum class Sex { MALE, FEMALE }

/**
 * The activity multiplier applied to BMR.
 *
 * These are the standard Harris-Benedict factors. They are a starting point, not a measurement —
 * which is exactly why [AdaptiveTdee] exists to correct them from what actually happens to your
 * weight.
 */
@Serializable
enum class ActivityLevel(val factor: Double, val label: String, val description: String) {
    SEDENTARY(1.2, "Sedentary", "Desk job, little exercise"),
    LIGHT(1.375, "Lightly active", "Training 1–3 days a week"),
    MODERATE(1.55, "Moderately active", "Training 3–5 days a week"),
    VERY(1.725, "Very active", "Training 6–7 days a week"),
    EXTRA(1.9, "Extremely active", "Physical job, or training twice a day"),
}

@Serializable
enum class Goal(val label: String) {
    CUT("Lose fat"),
    MAINTAIN("Maintain"),
    LEAN_BULK("Build muscle"),
}

/** Everything the calorie maths needs about you. */
@Serializable
data class UserProfile(
    val sex: Sex = Sex.MALE,
    val birthYear: Int = 0,
    val heightCm: Double = 0.0,
    val weightKg: Double = 0.0,
    val bodyFatPct: Double? = null,
    val activity: ActivityLevel = ActivityLevel.MODERATE,
    val goal: Goal = Goal.MAINTAIN,
    /** Let the weight trend correct the formula's guess. */
    val useAdaptiveTdee: Boolean = true,
    /** Overrides the whole calculation when set. */
    val manualKcalTarget: Int? = null,
    /** Overrides the default protein rule when set. */
    val proteinPerKgOverride: Double? = null,
) {
    val isComplete: Boolean
        get() = birthYear > 1900 && heightCm > 50 && weightKg > 20

    fun ageAt(currentYear: Int): Int = (currentYear - birthYear).coerceIn(10, 120)
}

/** A day's target. */
@Serializable
data class MacroTargets(
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val source: TargetSource = TargetSource.FORMULA,
)

@Serializable
enum class TargetSource { FORMULA, ADAPTIVE, MANUAL }

/** What was eaten, summed. */
data class MacroTotals(
    val kcal: Double = 0.0,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val fiberG: Double = 0.0,
) {
    operator fun plus(other: MacroTotals) = MacroTotals(
        kcal + other.kcal,
        proteinG + other.proteinG,
        carbsG + other.carbsG,
        fatG + other.fatG,
        fiberG + other.fiberG,
    )
}
