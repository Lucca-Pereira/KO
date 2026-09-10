package com.lucca.ko.domain

import java.text.Normalizer

/**
 * Pure text utilities for reconciling free-form recipe ingredient names with pantry
 * item names. No Android dependencies so it can be unit-tested on the JVM.
 */
object IngredientMatcher {

    private val descriptors = setOf(
        "fresh", "dried", "ground", "chopped", "minced", "sliced", "diced", "grated",
        "large", "small", "medium", "boneless", "skinless", "ripe", "raw", "cooked",
        "frozen", "canned", "tinned", "whole", "extra", "virgin", "fine", "coarse",
        "hot", "cold", "warm", "organic", "light", "dark", "low", "reduced", "unsalted",
        "salted", "smoked", "plain", "of", "a", "the", "for", "to", "taste", "optional",
        "finely", "roughly", "thinly", "freshly", "peeled", "crushed", "toasted", "or",
        "and", "into", "cut", "pieces", "wedges", "halved", "quartered", "boiling",
    )

    private val units = setOf(
        "g", "kg", "mg", "ml", "l", "tbsp", "tbsps", "tablespoon", "tablespoons",
        "tsp", "tsps", "teaspoon", "teaspoons", "cup", "cups", "oz", "ounce", "ounces",
        "lb", "lbs", "pound", "pounds", "clove", "cloves", "pinch", "pinches", "dash",
        "can", "cans", "jar", "jars", "package", "packages", "pkg", "packet", "packets",
        "slice", "slices", "sprig", "sprigs", "stick", "sticks", "handful", "bunch",
        "piece", "pieces", "ml.", "pint", "pints", "quart", "gallon", "cm", "inch",
    )

    /** US -> UK food words, so a "cilantro" alias matches TheMealDB's "Coriander". */
    private val synonyms = mapOf(
        "eggplant" to "aubergine",
        "zucchini" to "courgette",
        "cilantro" to "coriander",
        "shrimp" to "prawn",
        "garbanzo" to "chickpea",
        "arugula" to "rocket",
        "beet" to "beetroot",
        "cornstarch" to "cornflour",
        "scallion" to "spring onion",
    )

    /** Lower-case, strip measures/descriptors/punctuation, singularise, canonicalise synonyms. */
    fun normalize(input: String): String {
        var s = input.lowercase().trim()
        // fold accents so "orégano" -> "oregano", "puré" -> "pure", "jalapeño" -> "jalapeno"
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        // drop parenthetical notes
        s = s.replace(Regex("\\([^)]*\\)"), " ")
        // drop anything after a comma (usually preparation notes)
        s = s.substringBefore(',')
        // punctuation and digits -> space
        s = s.replace(Regex("[^a-z\\s]"), " ")
        val tokens = s.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in descriptors && it !in units }
            .map { singularize(it) }
            .map { synonyms[it] ?: it }
            .filter { it.isNotEmpty() }
        val joined = tokens.joinToString(" ")
        return joined.ifEmpty { input.lowercase().trim() }
    }

    private fun singularize(word: String): String = when {
        word.length <= 3 -> word
        word.endsWith("ies") -> word.dropLast(3) + "y"
        word.endsWith("oes") -> word.dropLast(2)
        word.endsWith("ses") || word.endsWith("shes") || word.endsWith("ches") -> word.dropLast(2)
        word.endsWith("ss") -> word
        word.endsWith("s") -> word.dropLast(1)
        else -> word
    }

    /**
     * Returns the best matching value from [candidatesNormalized] for [ingredientNormalized],
     * or null if nothing plausibly matches.
     */
    fun bestMatch(ingredientNormalized: String, candidatesNormalized: Collection<String>): String? {
        if (ingredientNormalized.isBlank()) return null
        candidatesNormalized.firstOrNull { it == ingredientNormalized }?.let { return it }

        val ingTokens = ingredientNormalized.split(' ').filter { it.isNotEmpty() }.toSet()
        if (ingTokens.isEmpty()) return null

        // token-subset match in either direction, preferring the fewest extra tokens
        return candidatesNormalized
            .mapNotNull { cand ->
                val candTokens = cand.split(' ').filter { it.isNotEmpty() }.toSet()
                if (candTokens.isEmpty()) return@mapNotNull null
                val overlap = ingTokens intersect candTokens
                when {
                    overlap.isEmpty() -> null
                    candTokens.containsAll(ingTokens) -> cand to (candTokens.size - ingTokens.size)
                    ingTokens.containsAll(candTokens) -> cand to (ingTokens.size - candTokens.size)
                    // strong single-word overlap (e.g. "chicken thigh" vs "chicken breast")
                    overlap.size == candTokens.size || overlap.size == ingTokens.size -> cand to 5
                    else -> null
                }
            }
            .minByOrNull { it.second }
            ?.first
    }
}
