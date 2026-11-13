package com.lucca.ko.domain.units

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Scales the free-text amount of an ingredient ("1 1/2 cups", "200 g", "1-2 tbsp") by a factor.
 *
 * Only the leading number (or range) is touched; everything after it is kept as written, so
 * "1 can (400g)" doubles to "2 can (400g)". Text with no leading number ("a pinch", "to taste")
 * comes back unchanged, which is right for amounts that don't scale. Pure JVM, unit-tested.
 */
object MeasureScaler {

    private const val FRACTIONS = "¼½¾⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"

    private val vulgar = mapOf(
        '¼' to 0.25, '½' to 0.5, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3,
        '⅕' to 0.2, '⅖' to 0.4, '⅗' to 0.6, '⅘' to 0.8, '⅙' to 1.0 / 6, '⅚' to 5.0 / 6,
        '⅛' to 0.125, '⅜' to 0.375, '⅝' to 0.625, '⅞' to 0.875,
    )

    // Alternatives, in order: "1 1/2", "1½" / "½", "3/4", "1.5" / "200".
    private val number = Regex(
        "^(?:(\\d+)\\s+(\\d+)\\s*/\\s*(\\d+)|(\\d+)?\\s*([$FRACTIONS])|(\\d+)\\s*/\\s*(\\d+)|(\\d+(?:[.,]\\d+)?))",
    )

    private val rangeJoin = Regex("^(?:\\s*[-–]\\s*|\\s+to\\s+)")

    /** Friendly fractions the result is rounded to, as (value, glyph). */
    private val steps = listOf(
        0.0 to "", 0.125 to "⅛", 0.25 to "¼", 1.0 / 3 to "⅓", 0.375 to "⅜", 0.5 to "½",
        0.625 to "⅝", 2.0 / 3 to "⅔", 0.75 to "¾", 0.875 to "⅞", 1.0 to "",
    )

    fun scale(measure: String?, factor: Double): String? {
        if (measure == null || factor <= 0.0 || abs(factor - 1.0) < 1e-9) return measure
        val text = measure.trimStart()
        val first = read(text) ?: return measure
        var rest = text.substring(first.second)
        var out = format(first.first * factor)
        rangeJoin.find(rest)?.let { join ->
            val high = read(rest.substring(join.value.length))
            if (high != null) {
                out += (if (join.value.contains("to")) " to " else "-") + format(high.first * factor)
                rest = rest.substring(join.value.length + high.second)
            }
        }
        return out + rest
    }

    /** The number at the start of [text] and how many characters it used, or null. */
    private fun read(text: String): Pair<Double, Int>? {
        val m = number.find(text) ?: return null
        val g = m.groupValues
        val value = when {
            g[1].isNotEmpty() -> {
                val d = g[3].toDouble()
                if (d == 0.0) return null
                g[1].toDouble() + g[2].toDouble() / d
            }
            g[5].isNotEmpty() -> (g[4].toDoubleOrNull() ?: 0.0) + (vulgar[g[5][0]] ?: return null)
            g[6].isNotEmpty() -> {
                val d = g[7].toDouble()
                if (d == 0.0) return null
                g[6].toDouble() / d
            }
            else -> g[8].replace(',', '.').toDoubleOrNull() ?: return null
        }
        return value to m.value.length
    }

    /** 2.25 -> "2 ¼", 0.5 -> "½", 600.0 -> "600", 12.4 -> "12". */
    fun format(value: Double): String {
        if (value >= 10) return value.roundToInt().toString()
        var whole = floor(value).toInt()
        val frac = value - whole
        val nearest = steps.minByOrNull { abs(it.first - frac) }!!
        if (nearest.first == 1.0) whole += 1
        val glyph = if (nearest.first == 1.0) "" else nearest.second
        return when {
            whole == 0 && glyph.isEmpty() -> "⅛" // never show 0 for a real amount
            whole == 0 -> glyph
            glyph.isEmpty() -> whole.toString()
            else -> "$whole $glyph"
        }
    }

    /** "½×", "1×", "2×", "1.5×" for the scale chips. */
    fun label(factor: Double): String = when {
        abs(factor - 0.5) < 1e-9 -> "½×"
        abs(factor - factor.roundToInt()) < 1e-9 -> "${factor.roundToInt()}×"
        else -> "$factor×"
    }
}
