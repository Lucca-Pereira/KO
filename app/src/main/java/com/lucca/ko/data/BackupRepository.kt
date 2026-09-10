package com.lucca.ko.data

import androidx.room.withTransaction
import com.lucca.ko.data.db.Dish
import com.lucca.ko.data.db.DishIngredient
import com.lucca.ko.data.db.KoDatabase
import com.lucca.ko.data.db.MealPlanEntry
import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.ShoppingListItem
import com.lucca.ko.data.prefs.AppSettings
import com.lucca.ko.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Everything the user has entered, in one portable file. */
@Serializable
data class KoBackup(
    val schema: Int = BackupRepository.SCHEMA,
    val app: String = "KO Kitchen",
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: AppSettings = AppSettings(),
    val pantry: List<PantryItem> = emptyList(),
    val dishes: List<Dish> = emptyList(),
    val dishIngredients: List<DishIngredient> = emptyList(),
    val mealPlan: List<MealPlanEntry> = emptyList(),
    val shopping: List<ShoppingListItem> = emptyList(),
)

data class ImportSummary(
    val pantry: Int,
    val dishes: Int,
    val plan: Int,
    val shopping: Int,
)

/**
 * Exports the full app state to a JSON string and restores it from one.
 * Import is destructive: it wipes the current database first, so a restore
 * onto a fresh install reproduces exactly what was backed up (row ids and all).
 */
class BackupRepository(
    private val db: KoDatabase,
    private val settings: SettingsRepository,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun exportJson(): String {
        val backup = KoBackup(
            settings = settings.settings.first(),
            pantry = db.pantryDao().getAll(),
            dishes = db.dishDao().getAllDishes(),
            dishIngredients = db.dishDao().getAllIngredients(),
            mealPlan = db.mealPlanDao().getAll(),
            shopping = db.shoppingDao().getAll(),
        )
        return json.encodeToString(backup)
    }

    suspend fun importJson(text: String): ImportSummary {
        val backup = try {
            json.decodeFromString<KoBackup>(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("That file isn't a valid KO Kitchen backup.", e)
        }
        require(backup.schema <= SCHEMA) {
            "This backup was made by a newer version of KO Kitchen. Update the app first."
        }

        withContext(Dispatchers.IO) { db.clearAllTables() }
        db.withTransaction {
            // Dishes before their ingredients / meal-plan rows (foreign keys).
            db.dishDao().insertAllDishes(backup.dishes)
            db.dishDao().insertAllIngredients(backup.dishIngredients)
            db.pantryDao().insertAll(backup.pantry)
            db.shoppingDao().insertAll(backup.shopping)
            db.mealPlanDao().insertAll(backup.mealPlan)
        }
        settings.update(
            baseUrl = backup.settings.ollamaBaseUrl,
            model = backup.settings.ollamaModel,
            count = backup.settings.suggestionCount,
        )

        return ImportSummary(
            pantry = backup.pantry.size,
            dishes = backup.dishes.size,
            plan = backup.mealPlan.size,
            shopping = backup.shopping.size,
        )
    }

    companion object {
        const val SCHEMA = 1
    }
}
