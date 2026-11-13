package com.lucca.ko.domain.shopping

import com.lucca.ko.domain.units.MeasureParser
import com.lucca.ko.domain.units.MeasureScaler

/**
 * Amounts on the shopping list: pulling "2 kg" off the front of what you typed, and combining
 * two amounts when the same item is added twice. Pure JVM, unit-tested.
 */
object ShoppingQuantity {

    private val lead = Regex(
        "^(\\d+\\s+\\d+/\\d+|\\d+/\\d+|\\d+(?:[.,]\\d+)?|[¼½¾⅓⅔⅛])\\s*([A-Za-z]+)?\\s+(.+)$",
    )

    /**
     * "2 kg potatoes" -> ("2 kg", "potatoes"); "3 eggs" -> ("3", "eggs"); "milk" -> (null, "milk").
     * Only a real unit word is taken into the amount, so "3 eggs" does not become 3 "eggs".
     */
    fun split(text: String): Pair<String?, String> {
        val clean = text.trim()
        val m = lead.find(clean) ?: return null to clean
        val number = m.groupValues[1]
        val word = m.groupValues[2]
        val rest = m.groupValues[3].trim()
        return if (word.isNotEmpty() && MeasureParser.isUnit(word)) {
            "$number $word" to rest
        } else {
            number to (if (word.isEmpty()) rest else "$word $rest")
        }
    }

    /**
     * Adds two amounts. Same unit (or none) sums ("2 kg" + "1 kg" = "3 kg"); anything else is
     * kept side by side ("2 kg + 3") rather than guessed at.
     */
    fun merge(a: String?, b: String?): String? {
        val left = a?.trim().orEmpty()
        val right = b?.trim().orEmpty()
        if (left.isEmpty()) return right.ifEmpty { null }
        if (right.isEmpty()) return left
        val ma = MeasureParser.parse(left)
        val mb = MeasureParser.parse(right)
        val qa = ma?.quantity
        val qb = mb?.quantity
        if (ma != null && mb != null && qa != null && qb != null && ma.unit == mb.unit) {
            val total = MeasureScaler.format(qa + qb)
            return if (ma.unit == null) total else "$total ${ma.unit}"
        }
        return if (left.equals(right, ignoreCase = true)) left else "$left + $right"
    }
}
