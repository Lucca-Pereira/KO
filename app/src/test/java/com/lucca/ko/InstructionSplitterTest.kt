package com.lucca.ko

import com.lucca.ko.domain.recipe.InstructionSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionSplitterTest {

    @Test
    fun `empty input yields no steps`() {
        assertTrue(InstructionSplitter.split(null).isEmpty())
        assertTrue(InstructionSplitter.split("").isEmpty())
        assertTrue(InstructionSplitter.split("   \n  ").isEmpty())
    }

    @Test
    fun `trusts the author's own numbering and strips the prefix`() {
        val steps = InstructionSplitter.split(
            """
            1. Preheat the oven to 180C.
            2. Chop the onions finely.
            3. Fry until golden brown.
            """.trimIndent(),
        )
        assertEquals(3, steps.size)
        assertEquals("Preheat the oven to 180C.", steps[0])
        assertEquals("Chop the onions finely.", steps[1])
        assertEquals("Fry until golden brown.", steps[2])
    }

    @Test
    fun `handles other numbering and bullet styles`() {
        assertEquals(
            listOf("Chop the onions finely.", "Fry until golden brown."),
            InstructionSplitter.split("1) Chop the onions finely.\n2) Fry until golden brown."),
        )
        assertEquals(
            listOf("Chop the onions finely.", "Fry until golden brown."),
            InstructionSplitter.split("Step 1: Chop the onions finely.\nStep 2: Fry until golden brown."),
        )
        assertEquals(
            listOf("Chop the onions finely.", "Fry until golden brown."),
            InstructionSplitter.split("- Chop the onions finely.\n• Fry until golden brown."),
        )
    }

    @Test
    fun `treats separate lines as separate steps when there is no numbering`() {
        val steps = InstructionSplitter.split(
            "Preheat the oven to 180C.\nChop the onions finely.\nFry until golden brown.",
        )
        assertEquals(3, steps.size)
    }

    @Test
    fun `splits a single run-on paragraph on sentence boundaries`() {
        val steps = InstructionSplitter.split(
            "Preheat the oven to 180C. Chop the onions finely. Fry them until golden brown.",
        )
        assertEquals(3, steps.size)
        assertEquals("Preheat the oven to 180C.", steps[0])
    }

    @Test
    fun `glues a fragment too short to be a step onto the previous one`() {
        val steps = InstructionSplitter.split(
            "Chop the onions finely and set them to one side. Mix. Fry until golden brown.",
        )
        // "Mix." on its own is not a step worth its own line.
        assertEquals(2, steps.size)
        assertTrue(steps[0].endsWith("Mix."))
    }

    @Test
    fun `a single sentence stays a single step`() {
        assertEquals(
            listOf("Put everything in the pot and simmer for an hour"),
            InstructionSplitter.split("Put everything in the pot and simmer for an hour"),
        )
    }

    @Test
    fun `does not mistake soft-wrapped prose for steps`() {
        // Short wrapped lines are not step boundaries; falling through to sentence splitting
        // gives a better answer than three two-word "steps".
        val steps = InstructionSplitter.split("Chop\nthe\nonions finely and fry them.")
        assertEquals(1, steps.size)
    }
}
