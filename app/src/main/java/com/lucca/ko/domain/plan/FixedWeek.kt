package com.lucca.ko.domain.plan

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The meal plan is a fixed week (or two), not a calendar: "Week 1, Tuesday" rather than a date.
 *
 * It is still stored in the `date` column every plan entry already has, as a date inside one
 * reserved fortnight starting Monday 2001-01-01. That keeps the table, the backup format and the
 * sync wire exactly as they were — a slot is just a date nobody would plan a meal on — at the
 * price of this one translation layer. Nothing outside this file should know the anchor.
 */
object FixedWeek {

    const val WEEKS = 2

    /** A Monday. Week 1 is this day through the following Sunday; week 2 the seven days after. */
    val ANCHOR: LocalDate = LocalDate.of(2001, 1, 1)

    private val LAST: LocalDate = ANCHOR.plusDays(7L * WEEKS - 1)

    /** The stored date for [day] of [week] (1 or 2). */
    fun dateFor(week: Int, day: DayOfWeek): LocalDate =
        ANCHOR.plusDays(7L * (week.coerceIn(1, WEEKS) - 1) + (day.value - 1))

    fun firstDayOf(week: Int): LocalDate = dateFor(week, DayOfWeek.MONDAY)

    fun isSlot(date: LocalDate): Boolean = !date.isBefore(ANCHOR) && !date.isAfter(LAST)

    /** 1 or 2. Only meaningful when [isSlot]. */
    fun weekOf(date: LocalDate): Int = (ChronoUnit.DAYS.between(ANCHOR, date) / 7).toInt() + 1

    /**
     * Where a real calendar date lands in the plan: its own weekday, in week 1. A date that is
     * already a slot is returned unchanged, so this is safe to apply to anything that arrives
     * from outside (an import file, the sync service).
     */
    fun slotFor(date: LocalDate): LocalDate =
        if (isSlot(date)) date else dateFor(1, date.dayOfWeek)

    /** [slotFor] over the ISO strings the database stores; unparseable input is returned as is. */
    fun slotFor(isoDate: String): String =
        runCatching { slotFor(LocalDate.parse(isoDate)).toString() }.getOrDefault(isoDate)

    /**
     * Converts a plan made on real dates, once: the week containing [today] becomes week 1 and
     * the week after it week 2. Returns null for any other date — an old week has no place in a
     * fixed plan, and the caller leaves those rows alone rather than piling six past Mondays
     * onto one.
     */
    fun slotForLegacy(date: LocalDate, today: LocalDate): LocalDate? {
        if (isSlot(date)) return date
        val thisMonday = today.minusDays(today.dayOfWeek.value - 1L)
        val offset = ChronoUnit.DAYS.between(thisMonday, date)
        return if (offset in 0 until 7L * WEEKS) ANCHOR.plusDays(offset) else null
    }
}
