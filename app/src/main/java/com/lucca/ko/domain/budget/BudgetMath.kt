package com.lucca.ko.domain.budget

import java.math.BigDecimal
import java.text.NumberFormat
import java.time.YearMonth
import java.util.Locale

/** One category's month: what was spent against its limit (null = no limit set). */
data class CategorySpend(val category: String, val spentCents: Long, val limitCents: Long?) {
    val over: Boolean get() = limitCents != null && spentCents > limitCents

    /** 0..1 for a progress bar; 0 when there's no limit to measure against. */
    val fraction: Float
        get() = if (limitCents == null || limitCents <= 0) 0f
        else (spentCents.toDouble() / limitCents).coerceIn(0.0, 1.0).toFloat()
}

data class MonthSummary(
    val totalSpentCents: Long,
    /** Sum of every limit set, whether or not that category has spending this month. */
    val totalLimitCents: Long,
    val rows: List<CategorySpend>,
) {
    val over: Boolean get() = totalLimitCents > 0 && totalSpentCents > totalLimitCents
}

/** Pure budget arithmetic: integer cents only, no floats on anything that gets stored. */
object BudgetMath {

    fun normalizeCategory(raw: String): String = raw.trim().lowercase()

    /**
     * Folds a month's expenses (category, cents) and the limits into per-category rows: every
     * category that has either spending or a limit, biggest spender first.
     */
    fun summarize(spend: List<Pair<String, Long>>, limits: Map<String, Long>): MonthSummary {
        val spentBy = spend.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
        val rows = (spentBy.keys + limits.keys).map { c ->
            CategorySpend(c, spentBy[c] ?: 0L, limits[c])
        }.sortedWith(compareByDescending<CategorySpend> { it.spentCents }.thenBy { it.category })
        return MonthSummary(spentBy.values.sum(), limits.values.sum(), rows)
    }

    /** `[from, until)` ISO dates covering [month], for a text comparison in SQL. */
    fun monthRange(month: YearMonth): Pair<String, String> =
        month.atDay(1).toString() to month.plusMonths(1).atDay(1).toString()

    /**
     * Typed amount to cents: "12", "12.5", "12,50", "1,234.56", "1.234,56". The last separator
     * is the decimal point when 1-2 digits follow it; otherwise separators are grouping.
     * Null for anything that isn't a positive amount.
     */
    fun parseCents(text: String): Long? {
        val t = text.trim().replace(" ", "")
        if (t.isEmpty() || !t.all { it.isDigit() || it == '.' || it == ',' }) return null
        val sep = t.indexOfLast { it == '.' || it == ',' }
        val whole: String
        val frac: String
        if (sep >= 0 && t.length - sep - 1 in 1..2) {
            whole = t.substring(0, sep).filter { it.isDigit() }
            frac = t.substring(sep + 1)
        } else {
            whole = t.filter { it.isDigit() }
            frac = ""
        }
        val wholePart = whole.ifEmpty { "0" }.toLongOrNull() ?: return null
        val fracPart = frac.padEnd(2, '0').toLong()
        return (wholePart * 100 + fracPart).takeIf { it > 0 }
    }

    /** Cents as a plain editable number ("12.50"), for refilling an amount field. */
    fun plainAmount(cents: Long): String =
        "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

    /** Cents as money in [locale]'s currency, via BigDecimal so no float touches the value. */
    fun format(cents: Long, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getCurrencyInstance(locale).format(BigDecimal.valueOf(cents, 2))
}
