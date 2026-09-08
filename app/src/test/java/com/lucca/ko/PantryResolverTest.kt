package com.lucca.ko

import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.domain.Availability
import com.lucca.ko.domain.IngredientMatcher
import com.lucca.ko.domain.PantryResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PantryResolverTest {

    private fun item(name: String, status: StockStatus, id: Long = name.hashCode().toLong()) =
        PantryItem(
            id = id,
            name = name,
            normalizedName = IngredientMatcher.normalize(name),
            status = status,
        )

    private val pantry = listOf(
        item("Olive oil", StockStatus.IN_STOCK),
        item("Onion", StockStatus.LOW),
        item("Chicken breast", StockStatus.OUT),
    )

    @Test
    fun inStockIngredientIsHave() {
        val (match, availability) = PantryResolver.resolve(
            IngredientMatcher.normalize("2 tbsp olive oil"), null, pantry,
        )
        assertEquals("Olive oil", match?.name)
        assertEquals(Availability.HAVE, availability)
    }

    @Test
    fun lowStockIngredientIsLow() {
        val (_, availability) = PantryResolver.resolve(
            IngredientMatcher.normalize("1 large onion, chopped"), null, pantry,
        )
        assertEquals(Availability.LOW, availability)
    }

    @Test
    fun outOfStockIngredientIsMissing() {
        val (match, availability) = PantryResolver.resolve(
            IngredientMatcher.normalize("chicken breasts"), null, pantry,
        )
        assertEquals("Chicken breast", match?.name)
        assertEquals(Availability.MISSING, availability)
    }

    @Test
    fun unknownIngredientIsMissingWithNoMatch() {
        val (match, availability) = PantryResolver.resolve(
            IngredientMatcher.normalize("saffron threads"), null, pantry,
        )
        assertNull(match)
        assertEquals(Availability.MISSING, availability)
    }

    @Test
    fun explicitLinkOverridesNameMatch() {
        val onion = pantry[1]
        val (match, availability) = PantryResolver.resolve(
            IngredientMatcher.normalize("olive oil"), onion.id, pantry,
        )
        assertEquals("Onion", match?.name)
        assertEquals(Availability.LOW, availability)
    }
}
