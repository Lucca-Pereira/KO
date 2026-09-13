package com.lucca.ko

import com.lucca.ko.domain.units.MeasureParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeasureParserTest {

    private fun q(raw: String?) = MeasureParser.parse(raw)?.quantity
    private fun u(raw: String?) = MeasureParser.parse(raw)?.unit

    @Test
    fun `parses a plain number and unit`() {
        assertEquals(200.0, q("200g"))
        assertEquals("g", u("200g"))
        assertEquals(200.0, q("200 g"))
        assertEquals("g", u("200 grams"))
        assertEquals(2.0, q("2 cups"))
        assertEquals("cup", u("2 cups"))
    }

    @Test
    fun `normalises unit spellings onto one canonical form`() {
        assertEquals("tbsp", u("1 tablespoon"))
        assertEquals("tbsp", u("1 tbs"))
        assertEquals("tbsp", u("2 Tbsp"))
        assertEquals("tsp", u("1 teaspoon"))
        assertEquals("kg", u("1 kilo"))
        assertEquals("ml", u("250 millilitres"))
        assertEquals("can", u("1 tin"))
    }

    @Test
    fun `parses mixed numbers with an ascii fraction`() {
        assertEquals(1.5, q("1 1/2 cups"))
        assertEquals("cup", u("1 1/2 cups"))
        assertEquals(2.25, q("2 1/4 tsp"))
    }

    @Test
    fun `parses a bare ascii fraction`() {
        assertEquals(0.75, q("3/4 cup"))
        assertEquals(0.5, q("1/2 tsp"))
    }

    @Test
    fun `parses unicode vulgar fractions, alone and after a whole number`() {
        assertEquals(0.5, q("½ tsp"))
        assertEquals("tsp", u("½ tsp"))
        assertEquals(1.5, q("1½ cups"))
        assertEquals(1.5, q("1 ½ cups"))
        assertEquals(0.25, q("¼ tsp"))
        assertEquals(0.75, q("¾ cup"))
    }

    @Test
    fun `takes the low end of a range`() {
        // Under-buying is recoverable; over-buying is waste.
        assertEquals(1.0, q("1-2 tbsp"))
        assertEquals(2.0, q("2 to 3 cloves"))
        assertEquals("clove", u("2 to 3 cloves"))
    }

    @Test
    fun `handles decimal separators from either convention`() {
        assertEquals(1.5, q("1.5 l"))
        assertEquals(1.5, q("1,5 l"))
    }

    @Test
    fun `recognises an amount that is only a unit`() {
        assertEquals("pinch", u("a pinch"))
        assertNull(q("a pinch"))
        assertEquals("dash", u("a dash"))
        assertEquals("handful", u("handful"))
    }

    @Test
    fun `treats an instruction as no amount at all`() {
        // "to taste" is a direction, not a quantity — inventing a number here would be worse
        // than leaving the original text to speak for itself.
        assertNull(MeasureParser.parse("to taste"))
        assertNull(MeasureParser.parse("as needed"))
        assertNull(MeasureParser.parse("for garnish"))
    }

    @Test
    fun `returns null for nothing usable`() {
        assertNull(MeasureParser.parse(null))
        assertNull(MeasureParser.parse(""))
        assertNull(MeasureParser.parse("   "))
    }

    @Test
    fun `parses a size descriptor as the unit`() {
        assertEquals(1.0, q("1 large"))
        assertEquals("large", u("1 large"))
        assertEquals(2.0, q("2 medium"))
    }

    @Test
    fun `keeps the number when the unit is unrecognised`() {
        val m = MeasureParser.parse("3 glugs")
        assertEquals(3.0, m?.quantity)
        assertNull(m?.unit)
    }
}
