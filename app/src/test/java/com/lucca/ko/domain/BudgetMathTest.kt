package com.lucca.ko.domain

import com.lucca.ko.domain.budget.BudgetMath
import java.time.YearMonth
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetMathTest {

    @Test
    fun summarizeTotalsAndFlagsOverspend() {
        val s = BudgetMath.summarize(
            spend = listOf("food" to 3000L, "food" to 2500L, "fun" to 1000L),
            limits = mapOf("food" to 5000L, "rent" to 90000L),
        )
        assertEquals(6500L, s.totalSpentCents)
        assertEquals(95000L, s.totalLimitCents)
        assertFalse(s.over)
        assertEquals(listOf("food", "fun", "rent"), s.rows.map { it.category })
        val food = s.rows.first()
        assertTrue(food.over)
        assertEquals(1f, food.fraction, 0.0001f)
        assertNull(s.rows[1].limitCents)
        assertEquals(0f, s.rows[1].fraction, 0f)
        assertEquals(0L, s.rows[2].spentCents)
    }

    @Test
    fun emptyMonthIsZero() {
        val s = BudgetMath.summarize(emptyList(), emptyMap())
        assertEquals(0L, s.totalSpentCents)
        assertTrue(s.rows.isEmpty())
    }

    @Test
    fun monthRangeIsHalfOpen() {
        assertEquals("2026-12-01" to "2027-01-01", BudgetMath.monthRange(YearMonth.of(2026, 12)))
    }

    @Test
    fun parseCentsHandlesCommonFormats() {
        assertEquals(1200L, BudgetMath.parseCents("12"))
        assertEquals(1250L, BudgetMath.parseCents("12.5"))
        assertEquals(1250L, BudgetMath.parseCents("12,50"))
        assertEquals(123456L, BudgetMath.parseCents("1,234.56"))
        assertEquals(123456L, BudgetMath.parseCents("1.234,56"))
        assertEquals(1234000L, BudgetMath.parseCents("12,340"))
        assertEquals(5L, BudgetMath.parseCents(".05"))
        assertNull(BudgetMath.parseCents(""))
        assertNull(BudgetMath.parseCents("abc"))
        assertNull(BudgetMath.parseCents("0"))
        assertNull(BudgetMath.parseCents("-5"))
    }

    @Test
    fun formatAndPlainAmountUseExactCents() {
        assertEquals("\$12.05", BudgetMath.format(1205, Locale.US))
        assertEquals("12.05", BudgetMath.plainAmount(1205))
        assertEquals("0.07", BudgetMath.plainAmount(7))
    }

    @Test
    fun categoriesAreTrimmedAndLowerCased() {
        assertEquals("eating out", BudgetMath.normalizeCategory("  Eating Out "))
    }
}
