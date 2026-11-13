package com.lucca.ko.domain

import com.lucca.ko.domain.recipe.RecipeJsonLd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeJsonLdTest {

    private fun page(ld: String) =
        "<html><head><script type=\"application/ld+json\">$ld</script></head><body></body></html>"

    @Test
    fun `plain recipe with strings and durations`() {
        val d = RecipeJsonLd.parse(
            page(
                """{"@context":"https://schema.org","@type":"Recipe","name":"Pancakes &amp; Syrup",
                "image":"https://x.test/p.jpg","recipeYield":"4 servings","prepTime":"PT15M","cookTime":"PT1H30M",
                "recipeIngredient":["200 g flour","1 1/2 cups milk","2 eggs","salt"],
                "recipeInstructions":"Mix.\nFry."}""",
            ),
            "https://x.test/r",
        )!!
        assertEquals("Pancakes & Syrup", d.title)
        assertEquals("4", d.servingsText)
        assertEquals("15", d.prepText)
        assertEquals("90", d.cookText)
        assertEquals("https://x.test/p.jpg", d.imageUrl)
        assertEquals("https://x.test/r", d.sourceUrl)
        assertEquals("200 g" to "flour", d.ingredients[0].amount to d.ingredients[0].name)
        assertEquals("1 1/2 cups" to "milk", d.ingredients[1].amount to d.ingredients[1].name)
        assertEquals("" to "salt", d.ingredients[3].amount to d.ingredients[3].name)
        assertEquals(listOf("Mix.", "Fry."), d.steps.map { it.text })
    }

    @Test
    fun `finds recipe inside graph with array type`() {
        val d = RecipeJsonLd.parse(
            page(
                """{"@context":"https://schema.org","@graph":[{"@type":"WebSite","name":"Site"},
                {"@type":["Recipe","NewsArticle"],"name":"Soup","recipeYield":[6,"6 bowls"],
                "totalTime":"PT45M","image":{"@type":"ImageObject","url":"https://x.test/s.png"},
                "recipeIngredient":["1 onion"],
                "recipeInstructions":[{"@type":"HowToStep","text":"Chop."},{"@type":"HowToStep","text":"Boil."}]}]}""",
            ),
        )!!
        assertEquals("Soup", d.title)
        assertEquals("6", d.servingsText)
        assertEquals("45", d.cookText)
        assertEquals("https://x.test/s.png", d.imageUrl)
        assertEquals(listOf("Chop.", "Boil."), d.steps.map { it.text })
    }

    @Test
    fun `flattens HowToSection steps and reads top-level array`() {
        val d = RecipeJsonLd.parse(
            page(
                """[{"@type":"Organization"},{"@type":"Recipe","name":"Cake",
                "recipeInstructions":[{"@type":"HowToSection","name":"Base","itemListElement":
                [{"@type":"HowToStep","text":"Cream."},{"@type":"HowToStep","text":"Fold."}]},
                {"@type":"HowToSection","name":"Top","itemListElement":[{"@type":"HowToStep","text":"Ice."}]}]}]""",
            ),
        )!!
        assertEquals(listOf("Cream.", "Fold.", "Ice."), d.steps.map { it.text })
        assertEquals("2", d.servingsText)
    }

    @Test
    fun `skips broken script blocks and uses a later valid one`() {
        val html = page("{not json") + page("""{"@type":"Recipe","name":"Toast"}""")
        assertEquals("Toast", RecipeJsonLd.parse(html)!!.title)
    }

    @Test
    fun `no recipe returns null`() {
        assertNull(RecipeJsonLd.parse(page("""{"@type":"Article","name":"News"}""")))
        assertNull(RecipeJsonLd.parse("<html>nothing</html>"))
    }

    @Test
    fun `durations`() {
        assertEquals(90, RecipeJsonLd.minutes("PT1H30M"))
        assertEquals(20, RecipeJsonLd.minutes("PT20M"))
        assertEquals(1500, RecipeJsonLd.minutes("P1DT1H"))
        assertNull(RecipeJsonLd.minutes("PT0M"))
        assertNull(RecipeJsonLd.minutes("soon"))
        assertNotNull(RecipeJsonLd.minutes("pt5m"))
    }
}
