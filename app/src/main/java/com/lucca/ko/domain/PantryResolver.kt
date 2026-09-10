package com.lucca.ko.domain

import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.StockStatus

enum class Availability { HAVE, LOW, MISSING }

/** A recipe ingredient line resolved against the current pantry for display. */
data class ResolvedIngredient(
    val dishIngredientId: Long,
    val rawName: String,
    val normalizedName: String,
    val measure: String?,
    val availability: Availability,
    val pantryItem: PantryItem?,
)

object PantryResolver {

    fun availabilityFor(status: StockStatus): Availability = when (status) {
        StockStatus.IN_STOCK -> Availability.HAVE
        StockStatus.LOW -> Availability.LOW
        StockStatus.OUT -> Availability.MISSING
    }

    /**
     * Resolve one ingredient against the pantry.
     *
     * @param explicitPantryItemId a manual link set by the user; takes priority.
     */
    fun resolve(
        normalizedName: String,
        explicitPantryItemId: Long?,
        pantry: List<PantryItem>,
    ): Pair<PantryItem?, Availability> {
        if (explicitPantryItemId != null) {
            val linked = pantry.firstOrNull { it.id == explicitPantryItemId }
            if (linked != null) return linked to availabilityFor(linked.status)
        }
        // Index each item by its own normalized name and, if set, its English alias
        // so a "butter" recipe line resolves to a "mantequilla" pantry item.
        val byName = HashMap<String, PantryItem>()
        for (p in pantry) {
            byName.putIfAbsent(p.normalizedName, p)
            p.searchName
                ?.let { IngredientMatcher.normalize(it) }
                ?.takeIf { it.isNotBlank() }
                ?.let { byName.putIfAbsent(it, p) }
        }
        val matchName = IngredientMatcher.bestMatch(normalizedName, byName.keys)
        val match = matchName?.let { byName[it] }
        return match to (match?.let { availabilityFor(it.status) } ?: Availability.MISSING)
    }
}
