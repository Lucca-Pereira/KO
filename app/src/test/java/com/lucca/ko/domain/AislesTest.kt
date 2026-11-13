package com.lucca.ko.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AislesTest {
    @Test
    fun `aisles follow the walk round a shop`() {
        val sorted = listOf("Frozen", "Other", "Produce", "Dairy & Eggs")
            .sortedBy { CategoryGuesser.aisleRank(it) }
        assertEquals(listOf("Produce", "Dairy & Eggs", "Frozen", "Other"), sorted)
    }

    @Test
    fun `an unknown aisle sorts with Other and every guess is a listed aisle`() {
        assertEquals(CategoryGuesser.aisleRank("Other"), CategoryGuesser.aisleRank("Mystery"))
        listOf("milk", "chicken", "rice", "zzz").forEach {
            assertTrue(CategoryGuesser.guess(it) in CategoryGuesser.aisles)
        }
    }
}
