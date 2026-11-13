package com.lucca.ko.domain

import com.lucca.ko.domain.shopping.ShoppingQuantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShoppingQuantityTest {

    @Test
    fun `a leading amount with a unit is split off`() {
        assertEquals("2 kg" to "potatoes", ShoppingQuantity.split("2 kg potatoes"))
        assertEquals("1 1/2 cups" to "milk", ShoppingQuantity.split("1 1/2 cups milk"))
    }

    @Test
    fun `a bare count keeps the word as part of the name`() {
        assertEquals("3" to "eggs", ShoppingQuantity.split("3 eggs"))
        assertEquals("2" to "red onions", ShoppingQuantity.split("2 red onions"))
    }

    @Test
    fun `text with no amount is left alone`() {
        assertEquals(null to "milk", ShoppingQuantity.split("  milk "))
    }

    @Test
    fun `same unit amounts add up`() {
        assertEquals("3 kg", ShoppingQuantity.merge("2 kg", "1 kg"))
        assertEquals("5", ShoppingQuantity.merge("2", "3"))
    }

    @Test
    fun `different units are kept side by side, and empties are ignored`() {
        assertEquals("2 kg + 3", ShoppingQuantity.merge("2 kg", "3"))
        assertEquals("2 kg", ShoppingQuantity.merge(null, "2 kg"))
        assertEquals("2 kg", ShoppingQuantity.merge("2 kg", ""))
        assertNull(ShoppingQuantity.merge(null, null))
        assertEquals("a pinch", ShoppingQuantity.merge("a pinch", "A pinch"))
    }
}
