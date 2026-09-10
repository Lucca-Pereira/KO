package com.lucca.ko

import com.lucca.ko.domain.IngredientMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IngredientMatcherTest {

    @Test
    fun normalize_stripsMeasuresDescriptorsAndPunctuation() {
        assertEquals("olive oil", IngredientMatcher.normalize("2 tbsp extra virgin olive oil"))
        assertEquals("chicken breast", IngredientMatcher.normalize("Chicken Breasts, diced"))
        assertEquals("garlic", IngredientMatcher.normalize("3 cloves of garlic, minced"))
        assertEquals("tomato", IngredientMatcher.normalize("Fresh tomatoes (chopped)"))
    }

    @Test
    fun normalize_handlesEdgeCases() {
        assertEquals("", IngredientMatcher.normalize("   "))
        assertEquals("salt", IngredientMatcher.normalize("a pinch of salt"))
    }

    @Test
    fun normalize_foldsAccents() {
        assertEquals("oregano", IngredientMatcher.normalize("Orégano"))
        assertEquals("pimenton picante", IngredientMatcher.normalize("Pimentón picante"))
        assertEquals("pure de patata", IngredientMatcher.normalize("Puré de patatas"))
        assertEquals("jalapeno", IngredientMatcher.normalize("Jalapeño"))
    }

    @Test
    fun normalize_canonicalisesUsUkSynonyms() {
        assertEquals("aubergine", IngredientMatcher.normalize("Eggplant"))
        assertEquals("courgette", IngredientMatcher.normalize("zucchini"))
        assertEquals("coriander", IngredientMatcher.normalize("fresh cilantro"))
        assertEquals("prawn", IngredientMatcher.normalize("Shrimps"))
        // a UK-English alias still matches a US-worded recipe line
        assertEquals(
            IngredientMatcher.normalize("aubergine"),
            IngredientMatcher.normalize("eggplant"),
        )
    }

    @Test
    fun bestMatch_exactBeatsPartial() {
        val pantry = listOf("onion", "red onion", "spring onion")
        assertEquals("onion", IngredientMatcher.bestMatch("onion", pantry))
    }

    @Test
    fun bestMatch_findsSubsetMatch() {
        val pantry = listOf("olive oil", "plain flour", "chicken stock")
        assertEquals("olive oil", IngredientMatcher.bestMatch(IngredientMatcher.normalize("olive oil"), pantry))
        assertEquals("chicken stock", IngredientMatcher.bestMatch("chicken stock cube".let { IngredientMatcher.normalize(it) }, pantry))
    }

    @Test
    fun bestMatch_returnsNullWhenUnrelated() {
        val pantry = listOf("onion", "garlic", "rice")
        assertNull(IngredientMatcher.bestMatch("saffron", pantry))
    }
}
