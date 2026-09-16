package com.lucca.ko.data.repo

import com.lucca.ko.data.db.MealPlanEntry
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.dao.MealPlanDao
import com.lucca.ko.data.db.dao.RecipeDao
import com.lucca.ko.data.db.relations.PlannedRecipe
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * The weekly plan. Entries are *references* to library recipes: planning the same recipe on five
 * days creates five rows pointing at one recipe, and removing one removes only that row.
 */
class MealPlanRepository(
    private val mealPlanDao: MealPlanDao,
    private val recipeDao: RecipeDao,
) {
    fun weekPlan(monday: LocalDate): Flow<List<PlannedRecipe>> =
        mealPlanDao.observeRange(monday.toString(), monday.plusDays(6).toString())

    /** How many planned meals point at this recipe — shown in the delete confirmation. */
    suspend fun planCountForRecipe(recipeId: Long): Int = mealPlanDao.countForRecipe(recipeId)

    suspend fun addToPlan(
        recipeId: Long,
        date: LocalDate,
        slot: MealSlot,
        servings: Double = 1.0,
    ): Long {
        val recipe = recipeDao.recipeById(recipeId)
        return mealPlanDao.insert(
            MealPlanEntry(
                date = date.toString(),
                slot = slot,
                dishId = recipeId,
                titleSnapshot = recipe?.title.orEmpty(),
                servings = servings,
                remoteId = UUID.randomUUID().toString(),
            ),
        )
    }

    /** Deletes the planned meal only. The recipe stays in the library. */
    suspend fun removePlanEntry(id: Long) = mealPlanDao.delete(id)

    suspend fun move(id: Long, date: LocalDate, slot: MealSlot) =
        mealPlanDao.move(id, date.toString(), slot)

    suspend fun setServings(id: Long, servings: Double) =
        mealPlanDao.setServings(id, servings.coerceAtLeast(0.25))

    /** Ticking "cooked" also bumps the recipe's own cooked count and last-cooked date. */
    suspend fun setCooked(id: Long, cooked: Boolean) {
        mealPlanDao.setCooked(id, cooked)
        if (cooked) {
            mealPlanDao.byId(id)?.dishId?.let { recipeDao.recordCooked(it, System.currentTimeMillis()) }
        }
    }

    /** Called after a rename so the snapshots the plan falls back on stay accurate. */
    suspend fun refreshTitleSnapshots(recipeId: Long, title: String) =
        mealPlanDao.refreshTitleSnapshots(recipeId, title)

    // ---- Sync ------------------------------------------------------------------------

    suspend fun planByRemoteId(remoteId: String): MealPlanEntry? = mealPlanDao.byRemoteId(remoteId)

    suspend fun pendingSyncPush(): List<MealPlanEntry> = mealPlanDao.pendingPush()

    suspend fun insertFromSync(entry: MealPlanEntry): Long = mealPlanDao.insert(entry)

    suspend fun stampSynced(id: Long, syncedAt: Long) = mealPlanDao.stampSynced(id, syncedAt)

    suspend fun stampSync(id: Long, remoteId: String, syncedAt: Long) =
        mealPlanDao.stampSync(id, remoteId, syncedAt)

    suspend fun setRemoteId(id: Long, remoteId: String) = mealPlanDao.setRemoteId(id, remoteId)
}
