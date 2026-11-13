package com.lucca.ko.domain

import com.lucca.ko.domain.recipe.StepTimes
import com.lucca.ko.domain.units.MeasureScaler
import org.junit.Assert.assertEquals
import org.junit.Test

class CookingHelpersTest {

    @Test
    fun `plain amounts scale and keep their unit`() {
        assertEquals("400 g", MeasureScaler.scale("200 g", 2.0))
        assertEquals("600g", MeasureScaler.scale("400g", 1.5))
        assertEquals("2", MeasureScaler.scale("1", 2.0))
    }

    @Test
    fun `fractions read and write nicely`() {
        assertEquals("3 cups", MeasureScaler.scale("1 1/2 cups", 2.0))
        assertEquals("¼ tsp", MeasureScaler.scale("½ tsp", 0.5))
        assertEquals("2 ¼ tsp", MeasureScaler.scale("1 1/2 tsp", 1.5))
        assertEquals("1 ½ cups", MeasureScaler.scale("3/4 cups", 2.0))
        assertEquals("1 ½ tbsp", MeasureScaler.scale("1 tbsp", 1.5))
    }

    @Test
    fun `ranges scale both ends`() {
        assertEquals("2-4 tbsp", MeasureScaler.scale("1-2 tbsp", 2.0))
        assertEquals("2 to 4 cloves", MeasureScaler.scale("1 to 2 cloves", 2.0))
    }

    @Test
    fun `amounts with no number or a factor of one are left alone`() {
        assertEquals("a pinch", MeasureScaler.scale("a pinch", 3.0))
        assertEquals("to taste", MeasureScaler.scale("to taste", 2.0))
        assertEquals("200 g", MeasureScaler.scale("200 g", 1.0))
        assertEquals(null, MeasureScaler.scale(null, 2.0))
    }

    @Test
    fun `only the leading number changes`() {
        assertEquals("2 can (400g)", MeasureScaler.scale("1 can (400g)", 2.0))
    }

    @Test
    fun `big results round to whole numbers, tiny ones never show zero`() {
        assertEquals("125", MeasureScaler.scale("250", 0.5))
        assertEquals("⅛", MeasureScaler.format(0.01))
    }

    @Test
    fun `times in steps are found with their text span`() {
        val found = StepTimes.find("Fry skin-side down for 6 minutes, then rest 1 hour.")
        assertEquals(listOf(360, 3600), found.map { it.seconds })
        assertEquals("6 minutes", found[0].label)
    }

    @Test
    fun `a range uses its low end and single letters are ignored`() {
        assertEquals(300, StepTimes.find("Cook 5-7 mins").single().seconds)
        assertEquals(emptyList<Int>(), StepTimes.find("Add 2 s and 5 m of cable").map { it.seconds })
    }

    @Test
    fun `clock face`() {
        assertEquals("10:00", StepTimes.clock(600))
        assertEquals("1:05:00", StepTimes.clock(3900))
        assertEquals("0:09", StepTimes.clock(9))
    }
}
