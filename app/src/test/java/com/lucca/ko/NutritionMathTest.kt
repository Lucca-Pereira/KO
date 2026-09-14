package com.lucca.ko

import com.lucca.ko.domain.nutrition.ActivityLevel
import com.lucca.ko.domain.nutrition.AdaptiveTdee
import com.lucca.ko.domain.nutrition.DatedValue
import com.lucca.ko.domain.nutrition.EnergyCalculator
import com.lucca.ko.domain.nutrition.Goal
import com.lucca.ko.domain.nutrition.MacroTotals
import com.lucca.ko.domain.nutrition.Per100g
import com.lucca.ko.domain.nutrition.PortionMath
import com.lucca.ko.domain.nutrition.Sex
import com.lucca.ko.domain.nutrition.StreakCalculator
import com.lucca.ko.domain.nutrition.UserProfile
import com.lucca.ko.domain.nutrition.WeightTrend
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind every number the gym side shows.
 *
 * All of it is hand-checkable on purpose: an app that tells you to eat 2,100 calories should be
 * able to show its working.
 */
class EnergyCalculatorTest {

    @Test
    fun `mifflin-st jeor matches the published formula`() {
        // 80 kg, 180 cm, 30 y male: 10(80) + 6.25(180) - 5(30) + 5 = 800 + 1125 - 150 + 5
        assertEquals(1780.0, EnergyCalculator.bmr(Sex.MALE, 80.0, 180.0, 30), 0.01)
        // Same body, female: the constant is -161 instead of +5.
        assertEquals(1614.0, EnergyCalculator.bmr(Sex.FEMALE, 80.0, 180.0, 30), 0.01)
    }

    @Test
    fun `tdee scales bmr by the activity factor`() {
        assertEquals(2759.0, EnergyCalculator.tdee(1780.0, ActivityLevel.MODERATE), 0.01)
    }

    @Test
    fun `a cut is twenty percent under maintenance`() {
        val t = EnergyCalculator.targetsFor(
            tdee = 2759.0, bmr = 1780.0, goal = Goal.CUT, weightKg = 80.0,
        )
        assertEquals(2207.0, t.kcal, 1.0)
    }

    @Test
    fun `a lean bulk is ten percent over`() {
        val t = EnergyCalculator.targetsFor(
            tdee = 2759.0, bmr = 1780.0, goal = Goal.LEAN_BULK, weightKg = 80.0,
        )
        assertEquals(3035.0, t.kcal, 1.0)
    }

    @Test
    fun `a cut is floored at bmr plus ten percent`() {
        // Small, sedentary: 20% off maintenance would land under the basal rate, which an app
        // should not hand you unasked.
        val bmr = 1200.0
        val tdee = EnergyCalculator.tdee(bmr, ActivityLevel.SEDENTARY) // 1440
        val t = EnergyCalculator.targetsFor(tdee, bmr, Goal.CUT, weightKg = 50.0)
        assertEquals(1320.0, t.kcal, 1.0) // the floor, not 1152
        assertTrue(t.kcal > bmr)
    }

    @Test
    fun `protein defaults to two grams per kilo of bodyweight`() {
        val t = EnergyCalculator.targetsFor(2759.0, 1780.0, Goal.MAINTAIN, weightKg = 80.0)
        assertEquals(160.0, t.proteinG, 1.0)
    }

    @Test
    fun `known body fat switches protein to lean mass`() {
        // 80 kg at 25% body fat is 60 kg lean; 2.2 x 60 = 132, which is less than 2.0 x 80.
        val t = EnergyCalculator.targetsFor(
            2759.0, 1780.0, Goal.MAINTAIN, weightKg = 80.0, bodyFatPct = 25.0,
        )
        assertEquals(132.0, t.proteinG, 1.0)
    }

    @Test
    fun `an implausible body fat reading is ignored rather than obeyed`() {
        val t = EnergyCalculator.targetsFor(
            2759.0, 1780.0, Goal.MAINTAIN, weightKg = 80.0, bodyFatPct = 95.0,
        )
        assertEquals(160.0, t.proteinG, 1.0) // fell back to bodyweight
    }

    @Test
    fun `a protein override wins over both rules`() {
        val t = EnergyCalculator.targetsFor(
            2759.0, 1780.0, Goal.MAINTAIN, weightKg = 80.0, bodyFatPct = 25.0,
            proteinPerKgOverride = 1.6,
        )
        assertEquals(128.0, t.proteinG, 1.0)
    }

    @Test
    fun `fat never drops below the hormonal floor`() {
        // On an aggressive cut, 20% of calories is less than 0.8 g/kg, so the weight rule wins.
        val t = EnergyCalculator.targetsFor(1600.0, 1400.0, Goal.CUT, weightKg = 90.0)
        assertTrue("fat must not fall below 0.8 g/kg", t.fatG >= 72.0)
    }

    @Test
    fun `the macros add up to the calorie target`() {
        val t = EnergyCalculator.targetsFor(2759.0, 1780.0, Goal.MAINTAIN, weightKg = 80.0)
        val fromMacros = t.proteinG * 4 + t.carbsG * 4 + t.fatG * 9
        assertEquals(t.kcal, fromMacros, 5.0)
    }

    @Test
    fun `carbs are floored at zero rather than going negative`() {
        // A very heavy person on a very low target: protein and fat alone exceed it.
        val t = EnergyCalculator.targetsFor(1200.0, 1000.0, Goal.CUT, weightKg = 150.0)
        assertTrue(t.carbsG >= 0.0)
    }

    @Test
    fun `an incomplete profile yields no target at all`() {
        assertNull(EnergyCalculator.targetsFor(UserProfile(), currentYear = 2026))
        assertNull(
            EnergyCalculator.targetsFor(
                UserProfile(birthYear = 1996, heightCm = 180.0),
                currentYear = 2026,
            ),
        )
    }

    @Test
    fun `a complete profile yields one`() {
        val profile = UserProfile(
            sex = Sex.MALE, birthYear = 1996, heightCm = 180.0, weightKg = 80.0,
            activity = ActivityLevel.MODERATE, goal = Goal.CUT,
        )
        val t = EnergyCalculator.targetsFor(profile, currentYear = 2026)
        assertNotNull(t)
        assertEquals(2207.0, t!!.kcal, 5.0)
    }

    @Test
    fun `a manual calorie target still gets macros split out of it`() {
        val profile = UserProfile(
            birthYear = 1996, heightCm = 180.0, weightKg = 80.0, manualKcalTarget = 2000,
        )
        val t = EnergyCalculator.targetsFor(profile, currentYear = 2026)!!
        assertEquals(2000.0, t.kcal, 1.0)
        // Otherwise three of the four rings would be meaningless.
        assertTrue(t.proteinG > 0 && t.carbsG > 0 && t.fatG > 0)
    }
}

class WeightTrendTest {

    private val start = LocalDate.of(2026, 1, 1)

    private fun series(vararg values: Double) =
        values.mapIndexed { i, v -> DatedValue(start.plusDays(i.toLong()), v) }

    @Test
    fun `an empty series smooths to nothing`() {
        assertTrue(WeightTrend.ema(emptyList()).isEmpty())
    }

    @Test
    fun `the first point passes through unchanged`() {
        assertEquals(80.0, WeightTrend.ema(series(80.0)).single().value, 0.001)
    }

    @Test
    fun `smoothing follows the published ema formula`() {
        // alpha = 2/(7+1) = 0.25. Second point: 0.25(82) + 0.75(80) = 80.5
        val smoothed = WeightTrend.ema(series(80.0, 82.0))
        assertEquals(80.5, smoothed[1].value, 0.001)
    }

    @Test
    fun `a daily swing moves the trend far less than the raw reading`() {
        val smoothed = WeightTrend.ema(series(80.0, 80.0, 80.0, 83.0))
        // Three kilos of water overnight should not become three kilos of trend.
        assertTrue(smoothed.last().value < 81.0)
    }

    @Test
    fun `a gap holds the trend rather than inventing readings`() {
        val points = listOf(
            DatedValue(start, 80.0),
            DatedValue(start.plusDays(5), 81.5),
        )
        val smoothed = WeightTrend.ema(points)
        // One point per day across the gap...
        assertEquals(6, smoothed.size)
        // ...but the held days are all the first value, not a straight line to the second.
        assertEquals(80.0, smoothed[1].value, 0.001)
        assertEquals(80.0, smoothed[4].value, 0.001)
        assertTrue(smoothed[5].value > 80.0)
    }

    @Test
    fun `a second weigh-in on the same day replaces the first`() {
        val points = listOf(DatedValue(start, 80.0), DatedValue(start, 81.0))
        assertEquals(81.0, WeightTrend.ema(points).single().value, 0.001)
    }

    @Test
    fun `too few points means no slope at all`() {
        // A trend through four readings is decoration; feeding it into a calorie target is worse.
        assertNull(WeightTrend.slopeKgPerDay(WeightTrend.ema(series(80.0, 79.9, 79.8, 79.7))))
    }

    @Test
    fun `a steady loss produces a negative slope`() {
        val values = (0 until 30).map { 85.0 - it * 0.05 }.toDoubleArray()
        val slope = WeightTrend.slopeKgPerDay(WeightTrend.ema(series(*values)))
        assertNotNull(slope)
        assertTrue("a cut should slope down", slope!! < 0)
        assertEquals(-0.05, slope, 0.02)
    }

    @Test
    fun `a steady gain produces a positive slope`() {
        val values = (0 until 30).map { 75.0 + it * 0.03 }.toDoubleArray()
        assertTrue(WeightTrend.slopeKgPerDay(WeightTrend.ema(series(*values)))!! > 0)
    }

    @Test
    fun `weekly slope is the daily one times seven`() {
        val values = (0 until 30).map { 85.0 - it * 0.05 }.toDoubleArray()
        val smoothed = WeightTrend.ema(series(*values))
        assertEquals(
            WeightTrend.slopeKgPerDay(smoothed)!! * 7,
            WeightTrend.slopeKgPerWeek(smoothed)!!,
            0.0001,
        )
    }

    @Test
    fun `the current trend is the last smoothed value`() {
        val smoothed = WeightTrend.ema(series(80.0, 81.0, 82.0))
        assertEquals(smoothed.last().value, WeightTrend.currentTrend(smoothed)!!, 0.0001)
    }
}

class AdaptiveTdeeTest {

    private val start = LocalDate.of(2026, 1, 1)

    private fun days(count: Int, kcal: Double) =
        (0 until count).map { AdaptiveTdee.DayIntake(start.plusDays(it.toLong()), kcal) }

    @Test
    fun `no weight trend leaves the formula untouched`() {
        val result = AdaptiveTdee.estimate(2700.0, null, days(28, 2400.0))
        assertEquals(2700.0, result.kcal, 0.01)
        assertEquals(0.0, result.confidence, 0.01)
        assertTrue(!result.isEstimate)
    }

    @Test
    fun `no intake data leaves the formula untouched`() {
        val result = AdaptiveTdee.estimate(2700.0, -0.03, emptyList())
        assertEquals(2700.0, result.kcal, 0.01)
    }

    @Test
    fun `a quarter kilo a week at 2400 implies roughly 2675`() {
        // -0.25 kg/week = -0.0357 kg/day; 0.0357 x 7700 = 275 kcal/day deficit.
        val result = AdaptiveTdee.estimate(2675.0, -0.25 / 7, days(28, 2400.0))
        assertEquals(2675.0, result.observed!!, 15.0)
        assertEquals(1.0, result.confidence, 0.01)
        assertEquals(2675.0, result.kcal, 20.0)
    }

    @Test
    fun `two weeks of data counts for about half a vote`() {
        val result = AdaptiveTdee.estimate(2700.0, -0.25 / 7, days(14, 2000.0))
        assertEquals(0.5, result.confidence, 0.01)
        // Observed is ~2275; blended halfway to the formula's 2700 is ~2487.
        assertTrue(result.kcal > 2400 && result.kcal < 2600)
    }

    @Test
    fun `a wild disagreement is capped rather than obeyed`() {
        // A fortnight of bad logging should not be allowed to claim a 4,000 kcal expenditure.
        val result = AdaptiveTdee.estimate(2500.0, 0.2, days(28, 5000.0))
        assertTrue(result.clamped)
        assertTrue(result.kcal <= 2500 * 1.35 + 1)
    }

    @Test
    fun `a half-logged day is dropped rather than dragging the mean down`() {
        // The correctness trap: logging breakfast and forgetting dinner looks like a fast, which
        // would inflate the estimated expenditure and raise the target.
        val bmr = 1800.0
        val logged = days(20, 2400.0) + days(5, 600.0).map {
            AdaptiveTdee.DayIntake(it.date.plusDays(100), it.kcal)
        }
        val complete = AdaptiveTdee.completeDays(logged, bmr)
        assertEquals(20, complete.size)
    }

    @Test
    fun `a genuinely low but plausible day is kept`() {
        val kept = AdaptiveTdee.completeDays(days(3, 1700.0), bmr = 1800.0)
        assertEquals(3, kept.size) // 1700 is above 1800 x 0.9
    }

    @Test
    fun `the description says how much of it is guesswork`() {
        val none = AdaptiveTdee.estimate(2700.0, null, emptyList())
        assertTrue(AdaptiveTdee.describe(none).contains("not enough", ignoreCase = true))

        val partial = AdaptiveTdee.estimate(2700.0, -0.03, days(14, 2400.0), totalDaysAvailable = 28)
        assertTrue(AdaptiveTdee.describe(partial).contains("14 of 28"))

        val full = AdaptiveTdee.estimate(2675.0, -0.25 / 7, days(28, 2400.0))
        assertTrue(AdaptiveTdee.describe(full).contains("28 days"))
    }
}

class PortionMathTest {

    private val chicken = Per100g(kcal = 165.0, proteinG = 31.0, carbsG = 0.0, fatG = 3.6)

    @Test
    fun `macros scale with weight`() {
        val m = PortionMath.macrosFor(chicken, 200.0)
        assertEquals(330.0, m.kcal, 0.01)
        assertEquals(62.0, m.proteinG, 0.01)
    }

    @Test
    fun `a fraction of a hundred grams works too`() {
        assertEquals(82.5, PortionMath.macrosFor(chicken, 50.0).kcal, 0.01)
    }

    @Test
    fun `known units convert to grams`() {
        assertEquals(200.0, PortionMath.gramsFor(200.0, "g")!!, 0.01)
        assertEquals(1000.0, PortionMath.gramsFor(1.0, "kg")!!, 0.01)
        assertEquals(30.0, PortionMath.gramsFor(2.0, "tbsp")!!, 0.01)
        assertEquals(240.0, PortionMath.gramsFor(1.0, "cup")!!, 0.01)
    }

    @Test
    fun `a count needs the food's own serving weight`() {
        assertEquals(60.0, PortionMath.gramsFor(2.0, "scoop", servingGrams = 30.0)!!, 0.01)
        // Without one, refuse rather than invent calories.
        assertNull(PortionMath.gramsFor(2.0, "scoop", servingGrams = null))
        assertNull(PortionMath.gramsFor(2.0, null, servingGrams = null))
    }

    @Test
    fun `an unrecognised unit is refused, not guessed`() {
        assertNull(PortionMath.gramsFor(3.0, "glugs", servingGrams = 30.0))
    }

    @Test
    fun `grams convert back to servings`() {
        assertEquals(1.5, PortionMath.servingsFor(45.0, 30.0)!!, 0.01)
        assertNull(PortionMath.servingsFor(45.0, null))
        assertNull(PortionMath.servingsFor(45.0, 0.0))
    }

    @Test
    fun `progress past the target is reported, not capped`() {
        // A ring that stops at full cannot tell you that you are 400 over, which is the thing
        // you most need to know.
        assertEquals(1.2, PortionMath.progress(2400.0, 2000.0), 0.001)
        assertEquals(0.0, PortionMath.progress(2400.0, 0.0), 0.001)
    }

    @Test
    fun `totals add`() {
        val a = MacroTotals(kcal = 100.0, proteinG = 10.0)
        val b = MacroTotals(kcal = 250.0, proteinG = 5.0, fatG = 3.0)
        val sum = a + b
        assertEquals(350.0, sum.kcal, 0.01)
        assertEquals(15.0, sum.proteinG, 0.01)
        assertEquals(3.0, sum.fatG, 0.01)
    }
}

class StreakCalculatorTest {

    private val today = LocalDate.of(2026, 9, 14)

    private fun daysBack(vararg offsets: Int) = offsets.map { today.minusDays(it.toLong()) }.toSet()

    @Test
    fun `no doses is no streak`() {
        assertEquals(0, StreakCalculator.currentStreak(emptySet(), today))
    }

    @Test
    fun `counts consecutive days ending today`() {
        assertEquals(3, StreakCalculator.currentStreak(daysBack(0, 1, 2), today))
    }

    @Test
    fun `today not ticked yet does not break the streak`() {
        // At nine in the morning you have not failed to take today's creatine, and an app that
        // says "streak: 0" before breakfast is lying about the thing it exists to encourage.
        assertEquals(3, StreakCalculator.currentStreak(daysBack(1, 2, 3), today))
    }

    @Test
    fun `a two-day gap does break it`() {
        assertEquals(0, StreakCalculator.currentStreak(daysBack(2, 3, 4), today))
    }

    @Test
    fun `a gap in the middle stops the count there`() {
        assertEquals(2, StreakCalculator.currentStreak(daysBack(0, 1, 3, 4), today))
    }

    @Test
    fun `the longest streak survives a later break`() {
        assertEquals(4, StreakCalculator.longestStreak(daysBack(0, 1, 5, 6, 7, 8)))
        assertEquals(0, StreakCalculator.longestStreak(emptySet()))
    }

    @Test
    fun `adherence is the honest number once a streak has broken`() {
        assertEquals(0.5, StreakCalculator.adherence(daysBack(0, 1, 2, 3, 4), today, days = 10), 0.01)
        assertEquals(1.0, StreakCalculator.adherence(daysBack(0, 1, 2), today, days = 3), 0.01)
        assertEquals(0.0, StreakCalculator.adherence(emptySet(), today), 0.01)
    }
}
