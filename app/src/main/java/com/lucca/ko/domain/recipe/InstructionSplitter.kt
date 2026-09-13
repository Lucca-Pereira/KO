package com.lucca.ko.domain.recipe

/**
 * Splits a wall of recipe prose into cooking steps.
 *
 * TheMealDB stores instructions as one blob, sometimes with numbered lines, sometimes with blank
 * lines between paragraphs, sometimes as a single run-on paragraph. The result seeds the step
 * editor; the original text is never modified, so a bad split costs the user one edit rather than
 * their recipe.
 *
 * Pure JVM and unit-tested.
 */
object InstructionSplitter {

    private const val MIN_STEP_LENGTH = 12

    /** Leading "1.", "1)", "Step 1:", "- ", "• ". */
    private val stepPrefix = Regex("""^\s*(?:step\s*)?(?:\d{1,2}\s*[.)\-:]|[-*•])\s*""", RegexOption.IGNORE_CASE)

    /** A sentence end followed by a capital letter — the last-resort split. */
    private val sentenceBreak = Regex("""(?<=[.!?])\s+(?=[A-ZÀ-Þ])""")

    fun split(text: String?): List<String> {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return emptyList()

        // 1. Explicitly numbered or bulleted lines are the author's own step boundaries: trust them.
        val lines = body.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.count { stepPrefix.containsMatchIn(it) } >= 2) {
            return lines.map { it.replace(stepPrefix, "") }.filter { it.isNotEmpty() }
        }

        // 2. Several real lines and no numbering: one line per step.
        if (lines.size >= 2) {
            val cleaned = lines.map { it.replace(stepPrefix, "") }.filter { it.isNotEmpty() }
            // Guard against soft-wrapped prose masquerading as steps.
            if (cleaned.all { it.length >= MIN_STEP_LENGTH }) return cleaned
        }

        // 3. One paragraph: split on sentence boundaries, then glue fragments too short to be a
        //    step ("Set aside.", abbreviations like "Preheat to 180C. Mix.") onto the previous one.
        val sentences = body.replace(stepPrefix, "").split(sentenceBreak)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.size <= 1) return listOf(body)

        val steps = mutableListOf<String>()
        for (sentence in sentences) {
            if (sentence.length < MIN_STEP_LENGTH && steps.isNotEmpty()) {
                steps[steps.lastIndex] = steps.last() + " " + sentence
            } else {
                steps += sentence
            }
        }
        return steps
    }
}
