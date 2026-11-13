package com.lucca.ko.domain.plan

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedWeekTest {

    @Test
    fun anchorIsAMonday() {
        assertEquals(DayOfWeek.MONDAY, FixedWeek.ANCHOR.dayOfWeek)
    }

    @Test
    fun dateForRoundTripsThroughWeekAndWeekday() {
        for (week in 1..2) {
            for (day in DayOfWeek.entries) {
                val date = FixedWeek.dateFor(week, day)
                assertTrue(FixedWeek.isSlot(date))
                assertEquals(week, FixedWeek.weekOf(date))
                assertEquals(day, date.dayOfWeek)
            }
        }
    }

    @Test
    fun onlyTheReservedFortnightIsASlot() {
        assertFalse(FixedWeek.isSlot(FixedWeek.ANCHOR.minusDays(1)))
        assertTrue(FixedWeek.isSlot(FixedWeek.ANCHOR.plusDays(13)))
        assertFalse(FixedWeek.isSlot(FixedWeek.ANCHOR.plusDays(14)))
    }

    @Test
    fun aRealDateLandsOnItsWeekdayInWeekOne() {
        // 2026-11-13 is a Friday.
        val slot = FixedWeek.slotFor(LocalDate.of(2026, 11, 13))
        assertEquals(FixedWeek.dateFor(1, DayOfWeek.FRIDAY), slot)
    }

    @Test
    fun aSlotIsLeftWhereItIs() {
        val weekTwoTuesday = FixedWeek.dateFor(2, DayOfWeek.TUESDAY)
        assertEquals(weekTwoTuesday, FixedWeek.slotFor(weekTwoTuesday))
        assertEquals(weekTwoTuesday.toString(), FixedWeek.slotFor(weekTwoTuesday.toString()))
    }

    @Test
    fun unparseableDateIsReturnedUnchanged() {
        assertEquals("not a date", FixedWeek.slotFor("not a date"))
    }

    @Test
    fun legacyConversionKeepsThisWeekAndNextAndDropsTheRest() {
        val today = LocalDate.of(2026, 11, 13) // Friday; its week starts Monday the 9th
        assertEquals(
            FixedWeek.dateFor(1, DayOfWeek.MONDAY),
            FixedWeek.slotForLegacy(LocalDate.of(2026, 11, 9), today),
        )
        assertEquals(
            FixedWeek.dateFor(1, DayOfWeek.SUNDAY),
            FixedWeek.slotForLegacy(LocalDate.of(2026, 11, 15), today),
        )
        assertEquals(
            FixedWeek.dateFor(2, DayOfWeek.WEDNESDAY),
            FixedWeek.slotForLegacy(LocalDate.of(2026, 11, 18), today),
        )
        assertNull(FixedWeek.slotForLegacy(LocalDate.of(2026, 11, 8), today))
        assertNull(FixedWeek.slotForLegacy(LocalDate.of(2026, 11, 23), today))
    }
}
