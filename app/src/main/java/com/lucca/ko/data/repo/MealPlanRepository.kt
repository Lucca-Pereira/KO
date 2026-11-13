package com.lucca.ko.data.repo

import com.lucca.ko.data.db.MealPlanEntry
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.dao.MealPlanDao
import com.lucca.ko.data.db.dao.RecipeDao
import com.lucca.ko.data.db.relations.PlannedRecipe
import com.lucca.ko.domain.plan.FixedWeek
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * The meal plan: a fixed week or two, addressed by the slot dates of [FixedWeek] rather than by
 * calendar date. Entries are *references* to library recipes: planning the same recipe on five
 * days creates five rows pointing at one recipe, and removing one removes only that row.
 */
class MealPlanRepository(
    private val mealPlanDao: MealPlanDao,
    private val recipeDao: RecipeDao,
) {
    /** Everything planned in [week] (1 or 2). */
    fun weekPlan(week: Int): Flow<List<PlannedRecipe>> {
        val monday = FixedWeek.firstDayOf(week)
        return mealPlanDao.observeRange(monday.toString(), monday.plusDays(6).toString())
    }

    /** Everything planned on one slot date — what the Today screen shows. */
    fun dayPlan(slotDate: LocalDate): Flow<List<PlannedRecipe>> =
        mealPlanDao.observeRange(slotDate.toString(), slotDate.toString())

    /** How many planned meals point at this recipe — shown in the delete confirmation. */
    suspend fun planCountForRecipe(recipeId: Long): Int = mealPlanDao.countForRecipe(recipeId)

    /** [date] may be a slot date or a real one; a real one lands on its weekday in week 1. */
    suspend fun addToPlan(
        recipeId: Long,
        date: LocalDate,
        slot: MealSlot,
        servings: Double = 1.0,
    ): Long {
        val recipe = recipeDao.recipeById(recipeId)
        return mealPlanDao.insert(
            MealPlanEntry(
                date = FixedWeek.slotFor(date).toString(),
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
        mealPlanDao.move(id, FixedWeek.slotFor(date).toString(), slot, UUID.randomUUID().toString())

    /**
     * One-time conversion of a plan made on calendar dates. The week containing [today] becomes
     * week 1 and the next one week 2; older and later rows are left untouched and simply never
     * shown, so nothing is destroyed. Safe to call on every launch — slot rows are skipped.
     */
    suspend fun convertLegacyDates(today: LocalDate = LocalDate.now()) {
        mealPlanDao.getAll().forEach { entry ->
            val date = runCatching { LocalDate.parse(entry.date) }.getOrNull() ?: return@forEach
            if (FixedWeek.isSlot(date)) return@forEach
            val slot = FixedWeek.slotForLegacy(date, today) ?: return@forEach
            mealPlanDao.move(entry.id, slot.toString(), entry.slot, UUID.randomUUID().toString())
        }
    }

    /** Clears every "cooked" tick; the meals themselves stay planned. */
    suspend fun clearCooked() = mealPlanDao.clearCooked()

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

    suspend fun syncPresentIds(): List<String> = mealPlanDao.allRemoteIds()

    /**
     * Stored exactly as pulled. A row still on a real calendar date (written before the plan
     * became fixed) is dealt with by [convertLegacyDates], which the sync runs afterwards — the
     * one place that decides which old dates still belong in the plan.
     */
    suspend fun insertFromSync(entry: MealPlanEntry): Long = mealPlanDao.insert(entry)

    suspend fun stampSynced(id: Long, syncedAt: Long) = mealPlanDao.stampSynced(id, syncedAt)

    suspend fun stampSync(id: Long, remoteId: String, syncedAt: Long) =
        mealPlanDao.stampSync(id, remoteId, syncedAt)

    suspend fun setRemoteId(id: Long, remoteId: String) = mealPlanDao.setRemoteId(id, remoteId)
}
