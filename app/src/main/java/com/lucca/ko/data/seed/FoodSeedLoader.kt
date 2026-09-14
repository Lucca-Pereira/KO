package com.lucca.ko.data.seed

import android.content.Context
import android.util.Log
import com.lucca.ko.data.db.FoodItem
import com.lucca.ko.data.db.FoodSource
import com.lucca.ko.data.db.dao.FoodDao
import com.lucca.ko.domain.IngredientMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Loads the bundled table of common foods into the database on first run.
 *
 * The same JSON ships in the NAS service, so the phone and the server agree on what "chicken
 * breast" weighs and contains — which is what lets nutrition estimation be deterministic on
 * either side rather than a model's guess.
 *
 * `Room.createFromAsset` is not an option: it only applies to a database being created, and
 * `ko.db` has existed since v0.1.
 */
class FoodSeedLoader(
    private val context: Context,
    private val foodDao: FoodDao,
) {
    @Serializable
    private data class SeedFile(val version: Int = 1, val foods: List<SeedFood> = emptyList())

    @Serializable
    private data class SeedFood(
        val name: String,
        @SerialName("serving_label") val servingLabel: String? = null,
        @SerialName("serving_grams") val servingGrams: Double? = null,
        @SerialName("kcal_per_100") val kcalPer100: Double = 0.0,
        @SerialName("protein_per_100") val proteinPer100: Double = 0.0,
        @SerialName("carbs_per_100") val carbsPer100: Double = 0.0,
        @SerialName("fat_per_100") val fatPer100: Double = 0.0,
        @SerialName("fiber_per_100") val fiberPer100: Double? = null,
        @SerialName("is_supplement") val isSupplement: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Inserts the seed rows if they are not already there.
     *
     * `IGNORE` on conflict plus `readOnly = 1` means a later re-seed adds what is new and leaves
     * anything you have edited alone.
     */
    suspend fun seedIfEmpty(): Int = withContext(Dispatchers.IO) {
        if (foodDao.seededCount() > 0) return@withContext 0

        val parsed = runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.mapCatching { json.decodeFromString(SeedFile.serializer(), it) }
            .getOrElse {
                Log.w(TAG, "Could not read $ASSET", it)
                return@withContext 0
            }

        val now = System.currentTimeMillis()
        val rows = parsed.foods.map { food ->
            FoodItem(
                name = food.name,
                normalizedName = IngredientMatcher.normalize(food.name)
                    .ifBlank { food.name.lowercase() },
                source = FoodSource.LOCAL,
                readOnly = true,
                servingLabel = food.servingLabel,
                servingGrams = food.servingGrams,
                kcalPer100 = food.kcalPer100,
                proteinPer100 = food.proteinPer100,
                carbsPer100 = food.carbsPer100,
                fatPer100 = food.fatPer100,
                fiberPer100 = food.fiberPer100,
                isSupplement = food.isSupplement,
                createdAt = now,
                updatedAt = now,
            )
        }
        foodDao.insertAll(rows)
        Log.i(TAG, "Seeded ${rows.size} foods")
        rows.size
    }

    private companion object {
        const val ASSET = "foods_seed.json"
        const val TAG = "FoodSeedLoader"
    }
}
