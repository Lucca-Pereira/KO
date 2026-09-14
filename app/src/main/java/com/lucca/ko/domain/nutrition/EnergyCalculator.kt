package com.lucca.ko.domain.nutrition

import kotlin.math.roundToInt

/**
 * Calories and macros from your own numbers.
 *
 * Mifflin-St Jeor rather than Harris-Benedict: it is the more accurate of the two for modern
 * populations and is what most calculators have used for a decade.
 *
 * Pure JVM and unit-tested. Every number a food diary judges you against comes out of here, so
 * it is worth being able to check by hand.
 */
object EnergyCalculator {

    const val KCAL_PER_G_PROTEIN = 4.0
    const val KCAL_PER_G_CARB = 4.0
    const val KCAL_PER_G_FAT = 9.0

    /** Basal metabolic rate: what you would burn asleep all day. */
    fun bmr(sex: Sex, weightKg: Double, heightCm: Double, ageYears: Int): Double {
        val base = 10 * weightKg + 6.25 * heightCm - 5 * ageYears
        return base + if (sex == Sex.MALE) 5.0 else -161.0
    }

    /** Total daily energy expenditure: BMR scaled by how much you move. */
    fun tdee(bmr: Double, activity: ActivityLevel): Double = bmr * activity.factor

    /**
     * The day's targets for a goal.
     *
     * A cut is 20% below maintenance, **floored at BMR × 1.1**. Without that floor, a light,
     * sedentary person on an aggressive setting can be handed a target below their basal rate,
     * which an app should not do on its own say-so.
     *
     * Protein is 2.0 g per kg of bodyweight, or 2.2 g per kg of *lean* mass when body fat is
     * known — the lean-mass rule is the better one for anyone carrying appreciable fat, where
     * bodyweight overstates how much tissue actually needs feeding.
     *
     * Fat is the larger of 0.8 g/kg and 20% of calories, because very low fat is a hormonal
     * problem rather than a dietary preference. Carbs take whatever is left.
     */
    fun targetsFor(
        tdee: Double,
        bmr: Double,
        goal: Goal,
        weightKg: Double,
        bodyFatPct: Double? = null,
        proteinPerKgOverride: Double? = null,
        source: TargetSource = TargetSource.FORMULA,
    ): MacroTargets {
        val raw = when (goal) {
            Goal.CUT -> tdee * 0.80
            Goal.MAINTAIN -> tdee
            Goal.LEAN_BULK -> tdee * 1.10
        }
        val kcal = maxOf(raw, bmr * 1.1)

        val leanKg = bodyFatPct
            ?.takeIf { it in 3.0..70.0 }
            ?.let { weightKg * (1 - it / 100.0) }

        val proteinG = when {
            proteinPerKgOverride != null -> proteinPerKgOverride * weightKg
            leanKg != null -> 2.2 * leanKg
            else -> 2.0 * weightKg
        }

        val fatFromWeight = 0.8 * weightKg
        val fatFromEnergy = kcal * 0.20 / KCAL_PER_G_FAT
        val fatG = maxOf(fatFromWeight, fatFromEnergy)

        val remaining = kcal - proteinG * KCAL_PER_G_PROTEIN - fatG * KCAL_PER_G_FAT
        val carbsG = (remaining / KCAL_PER_G_CARB).coerceAtLeast(0.0)

        return MacroTargets(
            kcal = kcal.round(),
            proteinG = proteinG.round(),
            carbsG = carbsG.round(),
            fatG = fatG.round(),
            source = source,
        )
    }

    /** The whole calculation from a profile, for when nothing needs overriding. */
    fun targetsFor(profile: UserProfile, currentYear: Int): MacroTargets? {
        if (!profile.isComplete) return null

        profile.manualKcalTarget?.let { manual ->
            // A manual calorie number still gets sensible macros split out of it, rather than
            // leaving three of the four rings meaningless.
            return targetsFor(
                tdee = manual.toDouble(),
                bmr = 0.0,
                goal = Goal.MAINTAIN,
                weightKg = profile.weightKg,
                bodyFatPct = profile.bodyFatPct,
                proteinPerKgOverride = profile.proteinPerKgOverride,
                source = TargetSource.MANUAL,
            )
        }

        val basal = bmr(profile.sex, profile.weightKg, profile.heightCm, profile.ageAt(currentYear))
        return targetsFor(
            tdee = tdee(basal, profile.activity),
            bmr = basal,
            goal = profile.goal,
            weightKg = profile.weightKg,
            bodyFatPct = profile.bodyFatPct,
            proteinPerKgOverride = profile.proteinPerKgOverride,
        )
    }
}

private fun Double.round(): Double = roundToInt().toDouble()
