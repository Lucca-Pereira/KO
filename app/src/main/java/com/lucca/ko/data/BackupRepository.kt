package com.lucca.ko.data

import androidx.room.withTransaction
import com.lucca.ko.data.backup.KoBackupV1
import com.lucca.ko.data.backup.KoBackupV2
import com.lucca.ko.data.backup.toEntity
import com.lucca.ko.data.backup.toV2
import com.lucca.ko.data.db.KoDatabase
import com.lucca.ko.data.prefs.SettingsRepository
import com.lucca.ko.domain.IngredientMatcher
import com.lucca.ko.domain.backup.BackupUpgrade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ImportSummary(
    val pantry: Int,
    val recipes: Int,
    val plan: Int,
    val shopping: Int,
    val loggedDays: Int = 0,
)

/**
 * Exports the full app state to a JSON string and restores it from one.
 *
 * Import is destructive: it wipes the current database first, so a restore onto a fresh install
 * reproduces exactly what was backed up, row ids and all. A schema-1 file (anything exported
 * before v0.3.0) is still readable — it is upgraded through [BackupUpgrade], which applies the
 * same dedupe rules as `MIGRATION_2_3`.
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
        val backup = KoBackupV2(
            exportedAt = System.currentTimeMillis(),
            pantry = db.pantryDao().getAll().map { it.toV2() },
            recipes = db.recipeDao().getAllRecipes().map { it.toV2() },
            recipeIngredients = db.recipeDao().getAllIngredients().map { it.toV2() },
            recipeSteps = db.recipeDao().getAllSteps().map { it.toV2() },
            tags = db.tagDao().getAllTags().map { it.toV2() },
            recipeTags = db.tagDao().getAllLinks().map { it.toV2() },
            mealPlan = db.mealPlanDao().getAll().map { it.toV2() },
            shopping = db.shoppingDao().getAll().map { it.toV2() },
            // Seed rows are excluded: they ship in the APK, so backing them up would triple the
            // file size to restore something the app already has.
            foods = db.foodDao().getAll().filterNot { it.readOnly }.map { it.toV2() },
            nutritionEntries = db.nutritionDao().getAll().map { it.toV2() },
            nutritionTargets = db.nutritionDao().allTargets().map { it.toV2() },
            bodyMetrics = db.bodyDao().getAll().map { it.toV2() },
            supplements = db.supplementDao().getAllSupplements().map { it.toV2() },
            supplementLog = db.supplementDao().getAllLogs().map { it.toV2() },
        )
        return json.encodeToString(KoBackupV2.serializer(), backup)
    }

    suspend fun importJson(text: String): ImportSummary {
        val backup = parse(text)

        // Recompute normalized names with the current rules. A v1 file has already had this done
        // by BackupUpgrade; doing it again is idempotent and covers v2 files written by an older
        // build of the normalizer.
        val pantry = backup.pantry.map { it.copy(normalizedName = renorm(it.name, it.normalizedName)) }
        val shopping = backup.shopping.map { it.copy(normalizedName = renorm(it.name, it.normalizedName)) }
        val ingredients = backup.recipeIngredients.map {
            it.copy(normalizedName = renorm(it.rawName, it.normalizedName))
        }

        withContext(Dispatchers.IO) { db.clearAllTables() }
        db.withTransaction {
            // Parents before children, for the foreign keys.
            db.recipeDao().insertAllRecipes(backup.recipes.map { it.toEntity() })
            db.recipeDao().insertAllIngredients(ingredients.map { it.toEntity() })
            db.recipeDao().insertAllSteps(backup.recipeSteps.map { it.toEntity() })
            db.tagDao().insertAllTags(backup.tags.map { it.toEntity() })
            db.tagDao().insertAllLinks(backup.recipeTags.map { it.toEntity() })
            db.pantryDao().insertAll(pantry.map { it.toEntity() })
            db.shoppingDao().insertAll(shopping.map { it.toEntity() })
            db.mealPlanDao().insertAll(backup.mealPlan.map { it.toEntity() })
            db.foodDao().insertAll(backup.foods.map { it.toEntity() })
            db.supplementDao().insertAllSupplements(backup.supplements.map { it.toEntity() })
            db.supplementDao().insertAllLogs(backup.supplementLog.map { it.toEntity() })
            db.nutritionDao().insertAll(backup.nutritionEntries.map { it.toEntity() })
            db.nutritionDao().insertAllTargets(backup.nutritionTargets.map { it.toEntity() })
            db.bodyDao().insertAll(backup.bodyMetrics.map { it.toEntity() })
        }

        settings.markNormalizationRepaired()
        settings.markMeasuresParsed()
        // clearAllTables() took the bundled foods with everything else; FoodSeedLoader puts them
        // back on next launch, since its guard is "are there any read-only rows".
        // Search blobs are not in the file; let StartupRepairs rebuild them on next launch.

        return ImportSummary(
            pantry = backup.pantry.size,
            recipes = backup.recipes.size,
            plan = backup.mealPlan.size,
            shopping = backup.shopping.size,
            loggedDays = backup.nutritionEntries.map { it.date }.distinct().size,
        )
    }

    /** Dispatches on the file's own `schema` field; upgrades a v1 file on the way in. */
    private fun parse(text: String): KoBackupV2 {
        val schema = try {
            Json.parseToJsonElement(text).jsonObject["schema"]?.jsonPrimitive?.int ?: 1
        } catch (e: Exception) {
            throw IllegalArgumentException("That file isn't a valid KO Kitchen backup.", e)
        }
        require(schema <= SCHEMA) {
            "This backup was made by a newer version of KO Kitchen. Update the app first."
        }
        return try {
            when (schema) {
                1 -> BackupUpgrade.upgrade(json.decodeFromString(KoBackupV1.serializer(), text))
                else -> json.decodeFromString(KoBackupV2.serializer(), text)
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("That file isn't a valid KO Kitchen backup.", e)
        }
    }

    private fun renorm(displayName: String, stored: String): String =
        IngredientMatcher.normalize(displayName).ifBlank { stored }

    companion object {
        const val SCHEMA = 2
    }
}
