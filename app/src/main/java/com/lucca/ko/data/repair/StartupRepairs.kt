package com.lucca.ko.data.repair

import android.util.Log
import com.lucca.ko.data.db.dao.PantryDao
import com.lucca.ko.data.db.dao.RecipeDao
import com.lucca.ko.data.db.dao.ShoppingDao
import com.lucca.ko.data.db.dao.TagDao
import com.lucca.ko.data.prefs.SettingsRepository
import com.lucca.ko.domain.IngredientMatcher
import com.lucca.ko.domain.units.MeasureParser

/**
 * One-shot data repairs run in the background at launch, each guarded by its own DataStore flag.
 *
 * These live here rather than in a repository because they are migrations that happen to need
 * Kotlin: parsing "1 1/2 cups" and folding accents are not things to attempt in SQL.
 */
class StartupRepairs(
    private val pantryDao: PantryDao,
    private val shoppingDao: ShoppingDao,
    private val recipeDao: RecipeDao,
    private val tagDao: TagDao,
    private val settings: SettingsRepository,
) {
    suspend fun runAll() {
        runCatching { repairNormalization() }
            .onFailure { Log.w(TAG, "Normalization repair failed", it) }
        runCatching { parseMeasures() }
            .onFailure { Log.w(TAG, "Measure backfill failed", it) }
        runCatching { backfillSearchBlobs() }
            .onFailure { Log.w(TAG, "Search blob backfill failed", it) }
    }

    /**
     * Recomputes `normalizedName` everywhere using the current rules. Fixes rows saved before the
     * accent-folding fix, e.g. "Orégano" stored as "gano".
     *
     * A row whose repaired name would collide with an existing one cannot be updated (the unique
     * index rejects it) and is left alone rather than swallowed silently — the collision means two
     * pantry items are genuinely the same thing, which is a merge decision, not a repair.
     */
    private suspend fun repairNormalization() {
        if (settings.isNormalizationRepaired()) return
        var collisions = 0

        pantryDao.getAll().forEach { item ->
            val fixed = IngredientMatcher.normalize(item.name)
            if (fixed.isNotBlank() && fixed != item.normalizedName) {
                if (pantryDao.byNormalized(fixed) != null) {
                    collisions++
                } else {
                    pantryDao.update(item.copy(normalizedName = fixed))
                }
            }
        }
        shoppingDao.getAll().forEach { item ->
            val fixed = IngredientMatcher.normalize(item.name)
            if (fixed.isNotBlank() && fixed != item.normalizedName) {
                if (shoppingDao.byNormalized(fixed) != null) {
                    collisions++
                } else {
                    shoppingDao.update(item.copy(normalizedName = fixed))
                }
            }
        }
        // Recipe ingredients have no unique index, so these always apply.
        recipeDao.getAllIngredients().forEach { ing ->
            val fixed = IngredientMatcher.normalize(ing.rawName)
            if (fixed.isNotBlank() && fixed != ing.normalizedName) {
                recipeDao.updateIngredient(ing.copy(normalizedName = fixed))
            }
        }

        if (collisions > 0) {
            Log.i(TAG, "$collisions rows normalise onto an existing name; left for manual merge")
        }
        settings.markNormalizationRepaired()
    }

    /** Fills `quantity`/`unit` from the original free-text `measure` (see [MeasureParser]). */
    private suspend fun parseMeasures() {
        if (settings.isMeasuresParsed()) return
        recipeDao.getAllIngredients().forEach { ing ->
            if (ing.quantity != null || ing.unit != null) return@forEach
            val parsed = MeasureParser.parse(ing.measure) ?: return@forEach
            recipeDao.updateIngredient(ing.copy(quantity = parsed.quantity, unit = parsed.unit))
        }
        settings.markMeasuresParsed()
    }

    /** Populates the library-search blob for recipes that predate it. */
    private suspend fun backfillSearchBlobs() {
        if (settings.isSearchBlobsBackfilled()) return
        recipeDao.getAllRecipes().forEach { recipe ->
            if (!recipe.searchBlob.isNullOrBlank()) return@forEach
            val parts = buildList {
                add(recipe.title)
                recipe.notes?.let(::add)
                addAll(recipeDao.ingredientsFor(recipe.id).map { it.rawName })
                addAll(tagDao.tagsFor(recipe.id).map { it.name })
            }
            recipeDao.setSearchBlob(recipe.id, parts.joinToString(" ").lowercase())
        }
        settings.markSearchBlobsBackfilled()
    }

    private companion object {
        const val TAG = "StartupRepairs"
    }
}
