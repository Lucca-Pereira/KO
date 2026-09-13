package com.lucca.ko.domain.units

/** A recipe amount split into a number and a unit. Either half may be absent. */
data class Measure(
    val quantity: Double? = null,
    val unit: String? = null,
)

/**
 * Turns the free-text amounts recipes actually contain into a number and a unit.
 *
 * Pure JVM and unit-tested, like [com.lucca.ko.domain.IngredientMatcher]. The original text is
 * never thrown away — `RecipeIngredient.measure` keeps it — so a parse that gives up costs
 * nothing, and returning null is always preferable to inventing a number.
 */
object MeasureParser {

    /** Unicode vulgar fractions, which TheMealDB and pasted recipes both use freely. */
    private val vulgarFractions = mapOf(
        '¼' to 0.25, '½' to 0.5, '¾' to 0.75,
        '⅐' to 1.0 / 7, '⅑' to 1.0 / 9, '⅒' to 0.1,
        '⅓' to 1.0 / 3, '⅔' to 2.0 / 3,
        '⅕' to 0.2, '⅖' to 0.4, '⅗' to 0.6, '⅘' to 0.8,
        '⅙' to 1.0 / 6, '⅚' to 5.0 / 6,
        '⅛' to 0.125, '⅜' to 0.375, '⅝' to 0.625, '⅞' to 0.875,
    )

    /** Spellings mapped to one canonical singular unit. */
    private val unitAliases = mapOf(
        "g" to "g", "gr" to "g", "gram" to "g", "grams" to "g", "gramme" to "g", "grammes" to "g",
        "kg" to "kg", "kilo" to "kg", "kilos" to "kg", "kilogram" to "kg", "kilograms" to "kg",
        "mg" to "mg",
        "ml" to "ml", "millilitre" to "ml", "millilitres" to "ml", "milliliter" to "ml",
        "milliliters" to "ml", "cc" to "ml",
        "l" to "l", "litre" to "l", "litres" to "l", "liter" to "l", "liters" to "l",
        "oz" to "oz", "ounce" to "oz", "ounces" to "oz",
        "lb" to "lb", "lbs" to "lb", "pound" to "lb", "pounds" to "lb",
        "tsp" to "tsp", "teaspoon" to "tsp", "teaspoons" to "tsp",
        "tbs" to "tbsp", "tbsp" to "tbsp", "tbsps" to "tbsp",
        "tablespoon" to "tbsp", "tablespoons" to "tbsp",
        "cup" to "cup", "cups" to "cup",
        "pint" to "pint", "pints" to "pint",
        "quart" to "quart", "quarts" to "quart",
        "gallon" to "gallon", "gallons" to "gallon",
        "clove" to "clove", "cloves" to "clove",
        "slice" to "slice", "slices" to "slice",
        "can" to "can", "cans" to "can", "tin" to "can", "tins" to "can",
        "jar" to "jar", "jars" to "jar",
        "packet" to "packet", "packets" to "packet", "pack" to "packet", "packs" to "packet",
        "bunch" to "bunch", "bunches" to "bunch",
        "sprig" to "sprig", "sprigs" to "sprig",
        "stalk" to "stalk", "stalks" to "stalk",
        "handful" to "handful", "handfuls" to "handful",
        "pinch" to "pinch", "pinches" to "pinch",
        "dash" to "dash", "dashes" to "dash",
        "drop" to "drop", "drops" to "drop",
        "scoop" to "scoop", "scoops" to "scoop",
        "large" to "large", "medium" to "medium", "small" to "small",
        "whole" to "whole", "piece" to "piece", "pieces" to "piece",
    )

    /** Amounts that carry no number at all but do name a unit. */
    private val bareUnits = setOf("pinch", "dash", "handful", "drop", "sprig", "splash")

    /**
     * Parses [raw] into a [Measure], or returns null when there is nothing usable in it.
     *
     * Handles the shapes that actually turn up: `"200g"`, `"1 1/2 cups"`, `"½ tsp"`, `"1-2 tbsp"`
     * (takes the low end — under-buying is recoverable, over-buying is waste), `"a pinch"`,
     * `"to taste"` (null, deliberately — it is a instruction, not an amount), `"1 large"`.
     */
    fun parse(raw: String?): Measure? {
        val text = raw?.trim()?.lowercase() ?: return null
        if (text.isEmpty()) return null
        // "to taste", "as needed", "for garnish" — real text, but not an amount.
        if (text in setOf("to taste", "as needed", "as required", "to serve", "for garnish")) {
            return null
        }

        val quantity = parseQuantity(text)
        val unit = parseUnit(text)
        return if (quantity == null && unit == null) null else Measure(quantity, unit)
    }

    private fun parseQuantity(text: String): Double? {
        // A leading vulgar fraction, optionally after a whole number: "1½", "1 ½", "½".
        val vulgarMatch = Regex("^(\\d+)?\\s*([${vulgarFractions.keys.joinToString("")}])").find(text)
        if (vulgarMatch != null) {
            val whole = vulgarMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            return whole + (vulgarFractions[vulgarMatch.groupValues[2][0]] ?: 0.0)
        }

        // "1 1/2" — a whole number followed by an ASCII fraction.
        Regex("^(\\d+)\\s+(\\d+)\\s*/\\s*(\\d+)").find(text)?.let { m ->
            val (w, n, d) = m.destructured
            val denominator = d.toDouble()
            if (denominator != 0.0) return w.toDouble() + n.toDouble() / denominator
        }

        // A bare fraction, "3/4".
        Regex("^(\\d+)\\s*/\\s*(\\d+)").find(text)?.let { m ->
            val (n, d) = m.destructured
            val denominator = d.toDouble()
            if (denominator != 0.0) return n.toDouble() / denominator
        }

        // A range, "1-2" or "2 to 3": take the low end.
        Regex("^(\\d+(?:[.,]\\d+)?)\\s*(?:-|–|to)\\s*\\d+(?:[.,]\\d+)?").find(text)?.let { m ->
            return m.groupValues[1].replace(',', '.').toDoubleOrNull()
        }

        // A plain decimal or integer, "200", "1.5", "1,5".
        Regex("^(\\d+(?:[.,]\\d+)?)").find(text)?.let { m ->
            return m.groupValues[1].replace(',', '.').toDoubleOrNull()
        }
        return null
    }

    private fun parseUnit(text: String): String? {
        // Split off letters wherever they start, so "200g" works as well as "200 g".
        val words = Regex("[a-zà-ÿ]+").findAll(text).map { it.value }.toList()
        for (word in words) {
            unitAliases[word]?.let { return it }
            if (word in bareUnits) return word
        }
        return null
    }
}
