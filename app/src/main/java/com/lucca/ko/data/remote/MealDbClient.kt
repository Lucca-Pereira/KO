package com.lucca.ko.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class MealSummary(
    val id: String,
    val title: String,
    val thumbUrl: String?,
)

data class MealDbIngredient(val name: String, val measure: String?)

data class MealDetail(
    val id: String,
    val title: String,
    val thumbUrl: String?,
    val category: String?,
    val area: String?,
    val instructions: String?,
    val sourceUrl: String?,
    val youtubeUrl: String?,
    val ingredients: List<MealDbIngredient>,
) {
    /** A link that is safe to open in a browser. */
    fun bestLink(): String = when {
        !sourceUrl.isNullOrBlank() -> sourceUrl
        !youtubeUrl.isNullOrBlank() -> youtubeUrl
        else -> "https://www.themealdb.com/meal/$id"
    }
}

/** Thin client for the free TheMealDB test API (developer key "1"). */
class MealDbClient(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://www.themealdb.com/api/json/v1/1/",
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun searchByName(query: String): List<MealSummary> =
        getMeals("search.php?s=" + query.trim().encode()).map { it.toSummary() }

    suspend fun filterByIngredient(ingredient: String): List<MealSummary> =
        getMeals("filter.php?i=" + ingredient.trim().encode()).map { it.toSummary() }

    suspend fun lookup(id: String): MealDetail? =
        getMeals("lookup.php?i=" + id.encode()).firstOrNull()?.toDetail()

    suspend fun random(): MealDetail? = getMeals("random.php").firstOrNull()?.toDetail()

    private suspend fun getMeals(path: String): List<JsonObject> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl + path).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("TheMealDB HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val root = json.parseToJsonElement(body).jsonObject
            val meals = root["meals"] ?: return@use emptyList()
            if (meals is JsonObject) return@use emptyList()
            runCatching { meals.jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())
        }
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.toSummary() = MealSummary(
        id = str("idMeal").orEmpty(),
        title = str("strMeal").orEmpty(),
        thumbUrl = str("strMealThumb"),
    )

    private fun JsonObject.toDetail(): MealDetail {
        val ingredients = buildList {
            for (i in 1..20) {
                val name = str("strIngredient$i") ?: continue
                add(MealDbIngredient(name.trim(), str("strMeasure$i")?.trim()))
            }
        }
        return MealDetail(
            id = str("idMeal").orEmpty(),
            title = str("strMeal").orEmpty(),
            thumbUrl = str("strMealThumb"),
            category = str("strCategory"),
            area = str("strArea"),
            instructions = str("strInstructions"),
            sourceUrl = str("strSource"),
            youtubeUrl = str("strYoutube"),
            ingredients = ingredients,
        )
    }
}

private fun String.encode(): String =
    java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
