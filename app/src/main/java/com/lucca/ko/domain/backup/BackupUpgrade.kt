package com.lucca.ko.domain.backup

import com.lucca.ko.data.backup.DishIngredientV1
import com.lucca.ko.data.backup.DishV1
import com.lucca.ko.data.backup.KoBackupV1
import com.lucca.ko.data.backup.KoBackupV2
import com.lucca.ko.data.backup.MealPlanEntryV1
import com.lucca.ko.data.backup.MealPlanEntryV2
import com.lucca.ko.data.backup.PantryItemV2
import com.lucca.ko.data.backup.RecipeIngredientV2
import com.lucca.ko.data.backup.RecipeV2
import com.lucca.ko.data.backup.SettingsV2
import com.lucca.ko.data.backup.ShoppingListItemV2
import com.lucca.ko.data.db.RecipeSource
import com.lucca.ko.domain.IngredientMatcher
import com.lucca.ko.domain.units.MeasureParser

/**
 * Upgrades a schema-1 backup to schema 2, applying exactly the transformations `MIGRATION_2_3`
 * applies to a live database: collapse duplicate MealDB dishes, repoint plan entries at the
 * survivor, fill in `titleSnapshot`, order ingredients, parse measures.
 *
 * The rules live here — pure, no Room, no Android — precisely because they now exist twice, once
 * as SQL and once as Kotlin. `BackupUpgradeTest` and `MigrationTest` assert the same outcomes.
 */
object BackupUpgrade {

    fun upgrade(v1: KoBackupV1): KoBackupV2 {
        val survivorFor = survivorIdsByMealdbId(v1.dishes)

        val keptDishes = v1.dishes.filter { dish ->
            dish.mealdbId.isNullOrBlank() || survivorFor[dish.mealdbId] == dish.id
        }
        val keptIds = keptDishes.map { it.id }.toSet()

        /** Maps any dish id onto the id that survived the dedupe. */
        fun resolve(dishId: Long): Long? {
            if (dishId in keptIds) return dishId
            val mealdbId = v1.dishes.firstOrNull { it.id == dishId }?.mealdbId
            return mealdbId?.let { survivorFor[it] }
        }

        val titles = keptDishes.associate { it.id to it.title }

        return KoBackupV2(
            exportedAt = v1.exportedAt,
            settings = SettingsV2(
                ollamaBaseUrl = v1.settings.ollamaBaseUrl,
                ollamaModel = v1.settings.ollamaModel,
                suggestionCount = v1.settings.suggestionCount,
            ),
            pantry = v1.pantry.map {
                PantryItemV2(
                    id = it.id,
                    name = it.name,
                    normalizedName = renorm(it.name, it.normalizedName),
                    category = it.category,
                    status = it.status,
                    quantity = it.quantity,
                    note = it.note,
                    searchName = it.searchName,
                    updatedAt = it.updatedAt,
                )
            },
            recipes = keptDishes.map { it.toRecipeV2() },
            recipeIngredients = upgradeIngredients(v1.dishIngredients, keptIds),
            mealPlan = v1.mealPlan.map { it.toV2(::resolve, titles) },
            shopping = v1.shopping.map {
                ShoppingListItemV2(
                    id = it.id,
                    name = it.name,
                    normalizedName = renorm(it.name, it.normalizedName),
                    category = it.category,
                    pantryItemId = it.pantryItemId,
                    checked = it.checked,
                    addedAt = it.addedAt,
                )
            },
        )
    }

    /** For each mealdbId, the lowest dish id — the same `MIN(id)` the SQL migration keeps. */
    private fun survivorIdsByMealdbId(dishes: List<DishV1>): Map<String, Long> =
        dishes.filter { !it.mealdbId.isNullOrBlank() }
            .groupBy { it.mealdbId!! }
            .mapValues { (_, group) -> group.minOf { it.id } }

    private fun DishV1.toRecipeV2() = RecipeV2(
        id = id,
        title = title,
        sourceUrl = sourceUrl,
        imageUrl = imageUrl,
        mealdbId = mealdbId,
        instructions = instructions,
        createdAt = createdAt,
        updatedAt = createdAt,
        source = if (mealdbId.isNullOrBlank()) RecipeSource.MANUAL else RecipeSource.MEALDB,
    )

    private fun upgradeIngredients(
        ingredients: List<DishIngredientV1>,
        keptDishIds: Set<Long>,
    ): List<RecipeIngredientV2> = ingredients
        .filter { it.dishId in keptDishIds }
        .groupBy { it.dishId }
        .flatMap { (_, group) ->
            // Original recipe order is insertion order, i.e. ascending id.
            group.sortedBy { it.id }.mapIndexed { index, ing ->
                val parsed = MeasureParser.parse(ing.measure)
                RecipeIngredientV2(
                    id = ing.id,
                    dishId = ing.dishId,
                    rawName = ing.rawName,
                    normalizedName = renorm(ing.rawName, ing.normalizedName),
                    measure = ing.measure,
                    pantryItemId = ing.pantryItemId,
                    quantity = parsed?.quantity,
                    unit = parsed?.unit,
                    sortOrder = index,
                )
            }
        }

    private fun MealPlanEntryV1.toV2(
        resolve: (Long) -> Long?,
        titles: Map<Long, String>,
    ): MealPlanEntryV2 {
        val target = resolve(dishId)
        return MealPlanEntryV2(
            id = id,
            date = date,
            slot = slot,
            dishId = target,
            titleSnapshot = target?.let { titles[it] }.orEmpty(),
        )
    }

    /**
     * Recomputes a stored normalized name with the current rules; older exports carry keys from
     * before the accent-folding fix ("Orégano" stored as "gano"). Falls back to the stored value
     * when the current rules yield nothing.
     */
    private fun renorm(displayName: String, stored: String): String =
        IngredientMatcher.normalize(displayName).ifBlank { stored }
}
