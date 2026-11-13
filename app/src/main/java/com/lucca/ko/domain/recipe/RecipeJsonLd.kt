package com.lucca.ko.domain.recipe

import com.lucca.ko.data.db.RecipeSource
import com.lucca.ko.domain.units.MeasureParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pulls a schema.org `Recipe` out of a web page's JSON-LD and turns it into a [RecipeDraft].
 * Pure JVM, no AI: it only reads what the site already published in structured form.
 */
object RecipeJsonLd {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    private val scriptRegex = Regex(
        "<script[^>]*type\\s*=\\s*[\"']?application/ld\\+json[\"']?[^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** The first Recipe found in [html], or null when the page has none. */
    fun parse(html: String, sourceUrl: String = ""): RecipeDraft? {
        for (match in scriptRegex.findAll(html)) {
            val root = runCatching { json.parseToJsonElement(match.groupValues[1].trim()) }.getOrNull() ?: continue
            val recipe = findRecipe(root) ?: continue
            toDraft(recipe, sourceUrl)?.let { return it }
        }
        return null
    }

    private fun isRecipe(o: JsonObject): Boolean {
        val type = o["@type"] ?: return false
        return when (type) {
            is JsonArray -> type.any { (it as? JsonPrimitive)?.contentOrNull.equals("Recipe", true) }
            is JsonPrimitive -> type.contentOrNull.equals("Recipe", true)
            else -> false
        }
    }

    private fun findRecipe(e: JsonElement): JsonObject? = when (e) {
        is JsonArray -> e.firstNotNullOfOrNull { findRecipe(it) }
        is JsonObject -> if (isRecipe(e)) e else e["@graph"]?.let { findRecipe(it) }
        else -> null
    }

    private fun toDraft(r: JsonObject, sourceUrl: String): RecipeDraft? {
        val title = clean(text(r["name"]) ?: return null)
        if (title.isBlank()) return null
        var key = 0L
        val ingredients = strings(r["recipeIngredient"] ?: r["ingredients"]).map { line ->
            val (amount, name) = splitIngredient(clean(line))
            IngredientDraft(key = --key, name = name, amount = amount)
        }.filter { !it.isBlank }
        val steps = mutableListOf<StepDraft>()
        collectSteps(r["recipeInstructions"], steps)
        val cook = minutes(text(r["cookTime"]))
        val prep = minutes(text(r["prepTime"]))
        // Many sites give only totalTime; better to show it as cook time than to lose it.
        val total = minutes(text(r["totalTime"]))
        return RecipeDraft(
            title = title,
            sourceUrl = sourceUrl,
            imageUrl = image(r["image"]),
            servingsText = yieldServings(r["recipeYield"])?.toString() ?: "2",
            prepText = prep?.toString() ?: "",
            cookText = (cook ?: if (prep == null) total else null)?.toString() ?: "",
            notes = clean(text(r["description"]).orEmpty()),
            ingredients = ingredients,
            steps = steps,
            tags = strings(r["recipeCategory"]).map { clean(it) }.filter { it.isNotBlank() }.distinct(),
            source = RecipeSource.IMPORT,
        )
    }

    private fun collectSteps(e: JsonElement?, out: MutableList<StepDraft>) {
        when (e) {
            null -> {}
            is JsonArray -> e.forEach { collectSteps(it, out) }
            is JsonPrimitive -> {
                // A bare string may hold several lines.
                e.contentOrNull?.let { s ->
                    s.split(Regex("\\r?\\n+")).map { clean(it) }.filter { it.isNotEmpty() }
                        .forEach { out += StepDraft(key = -(out.size + 1L), text = it) }
                }
            }
            is JsonObject -> {
                if (e["itemListElement"] != null) {
                    collectSteps(e["itemListElement"], out) // HowToSection
                } else {
                    val t = text(e["text"]) ?: text(e["name"])
                    if (!t.isNullOrBlank()) out += StepDraft(key = -(out.size + 1L), text = clean(t))
                }
            }
        }
    }

    private fun text(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull

    private fun strings(e: JsonElement?): List<String> = when (e) {
        null -> emptyList()
        is JsonArray -> e.flatMap { strings(it) }
        is JsonPrimitive -> e.contentOrNull.orEmpty().split(Regex("\\r?\\n"))
        is JsonObject -> listOfNotNull(text(e["name"]))
    }.filter { it.isNotBlank() }

    private fun image(e: JsonElement?): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull
        is JsonArray -> e.firstNotNullOfOrNull { image(it) }
        is JsonObject -> text(e["url"])
        else -> null
    }?.takeIf { it.startsWith("http") }

    /** Strips tags, decodes the few HTML entities JSON-LD text commonly carries, and trims. */
    private fun clean(s: String): String = s
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace(Regex("\\s+"), " ").trim()

    /** First integer in a yield such as "4", "4 servings" or ["4","4 servings"]. */
    private fun yieldServings(e: JsonElement?): Int? =
        strings(e).firstNotNullOfOrNull { Regex("\\d+").find(it)?.value?.toIntOrNull() }?.takeIf { it > 0 }

    /** ISO-8601 duration (PT1H30M, P1DT2H) to whole minutes. */
    fun minutes(iso: String?): Int? {
        val m = Regex("^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$", RegexOption.IGNORE_CASE)
            .matchEntire(iso?.trim() ?: return null) ?: return null
        val (d, h, min, s) = m.destructured
        val total = (d.toIntOrNull() ?: 0) * 1440 + (h.toIntOrNull() ?: 0) * 60 +
            (min.toIntOrNull() ?: 0) + if ((s.toIntOrNull() ?: 0) >= 30) 1 else 0
        return total.takeIf { it > 0 }
    }

    private val quantityWord = Regex("^[\\d\u00BD\u00BC\u00BE\u2153\u2154\u215B/.,-]+$")

    /** "1 1/2 cups flour" -> ("1 1/2 cups", "flour"). Falls back to ("", whole line). */
    fun splitIngredient(line: String): Pair<String, String> {
        val words = line.split(' ').filter { it.isNotEmpty() }
        var i = 0
        while (i < words.size && quantityWord.matches(words[i])) i++
        if (i < words.size && MeasureParser.isUnit(words[i].trimEnd('.'))) i++
        if (i == 0 || i >= words.size) return "" to line
        return words.take(i).joinToString(" ") to words.drop(i).joinToString(" ")
    }
}
