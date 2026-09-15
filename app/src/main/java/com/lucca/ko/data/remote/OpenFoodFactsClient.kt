package com.lucca.ko.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** One product, in the shape [com.lucca.ko.data.repo.NutritionRepository] needs to build a [com.lucca.ko.data.db.FoodItem]. */
data class OffProduct(
    val name: String,
    val brand: String?,
    val servingLabel: String?,
    val servingGrams: Double?,
    val kcalPer100: Double,
    val proteinPer100: Double,
    val carbsPer100: Double,
    val fatPer100: Double,
    val fiberPer100: Double?,
    val sugarPer100: Double?,
    val satFatPer100: Double?,
    val sodiumMgPer100: Double?,
    val imageUrl: String?,
)

/**
 * Thin client for the free, keyless Open Food Facts product API.
 *
 * Called straight from the phone rather than through the recipe agent: a barcode scan is a
 * lookup against a public database, not something an LLM should be guessing at, and it needs to
 * work at full speed standing in a shop.
 */
class OpenFoodFactsClient(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://world.openfoodfacts.org/api/v2/product/",
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun byBarcode(barcode: String): OffProduct? = withContext(Dispatchers.IO) {
        val clean = barcode.trim()
        if (clean.isEmpty()) return@withContext null
        val request = Request.Builder().url("$baseUrl$clean.json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@use null
            if (root.num("status") != 1.0) return@use null
            val product = root["product"]?.jsonObject ?: return@use null
            val name = product.str("product_name")?.takeIf { it.isNotBlank() } ?: return@use null
            val nutriments = product["nutriments"]?.jsonObject ?: JsonObject(emptyMap())
            val servingLabel = product.str("serving_size")
            OffProduct(
                name = name,
                brand = product.str("brands"),
                servingLabel = servingLabel,
                servingGrams = nutriments.num("serving_quantity") ?: servingLabel?.let(::parseGrams),
                kcalPer100 = nutriments.num("energy-kcal_100g") ?: 0.0,
                proteinPer100 = nutriments.num("proteins_100g") ?: 0.0,
                carbsPer100 = nutriments.num("carbohydrates_100g") ?: 0.0,
                fatPer100 = nutriments.num("fat_100g") ?: 0.0,
                fiberPer100 = nutriments.num("fiber_100g"),
                sugarPer100 = nutriments.num("sugars_100g"),
                satFatPer100 = nutriments.num("saturated-fat_100g"),
                sodiumMgPer100 = nutriments.num("sodium_100g")?.let { it * 1000 },
                imageUrl = product.str("image_front_small_url") ?: product.str("image_url"),
            )
        }
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.num(key: String): Double? =
        this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

    /** Pulls the leading number out of something like "30 g" or "1 bar (45g)". */
    private fun parseGrams(label: String): Double? =
        Regex("""(\d+(?:[.,]\d+)?)\s*g""").find(label)
            ?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
}
