package com.lucca.ko.domain.recipe

/** A duration found in a step's text: where it sits and how many seconds it is. */
data class StepTime(val start: Int, val end: Int, val seconds: Int, val label: String)

/**
 * Finds the times mentioned in a recipe step ("simmer for 10 minutes", "rest 1 hour",
 * "fry 5-7 mins") so they can be started as timers. A range uses its low end. Bare single
 * letters ("5 m", "2 s") are ignored on purpose: too many false hits.
 */
object StepTimes {

    private val pattern = Regex(
        "(\\d+(?:[.,]\\d+)?)(?:\\s*(?:-|–|to)\\s*(\\d+(?:[.,]\\d+)?))?\\s*" +
            "(hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun find(text: String): List<StepTime> = pattern.findAll(text).mapNotNull { m ->
        val amount = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
        val unit = m.groupValues[3].lowercase()
        val seconds = when {
            unit.startsWith("h") -> amount * 3600
            unit.startsWith("m") -> amount * 60
            else -> amount
        }.toInt()
        if (seconds <= 0) return@mapNotNull null
        StepTime(m.range.first, m.range.last + 1, seconds, m.value)
    }.toList()

    /** "10:00", "1:05:00" — the clock face of a timer. */
    fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
