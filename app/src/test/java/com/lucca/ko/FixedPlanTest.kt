package com.lucca.ko

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lucca.ko.data.db.KoDatabase
import com.lucca.ko.data.db.MealPlanEntry
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.prefs.PlanViewRepository
import com.lucca.ko.data.repair.PlanMaintenance
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.domain.plan.FixedWeek
import com.lucca.ko.domain.recipe.RecipeDraft
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The meal plan as a fixed week: where entries land, how a move reaches the sync, what happens
 * to a plan made on real dates, and the weekly tick reset. Real in-memory Room, as elsewhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixedPlanTest {

    private lateinit var db: KoDatabase
    private lateinit var recipes: RecipeRepository
    private lateinit var plan: MealPlanRepository
    private lateinit var planView: PlanViewRepository

    /** A Friday; its week starts Monday 2026-11-09. */
    private val today = LocalDate.of(2026, 11, 13)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KoDatabase::class.java,
        ).allowMainThreadQueries().build()
        recipes = RecipeRepository(
            recipeDao = db.recipeDao(),
            tagDao = db.tagDao(),
            pantryDao = db.pantryDao(),
            shoppingDao = db.shoppingDao(),
            mealPlanDao = db.mealPlanDao(),
        )
        plan = MealPlanRepository(db.mealPlanDao(), db.recipeDao())
        planView = PlanViewRepository(ApplicationProvider.getApplicationContext())
        runBlocking { planView.clear() }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun recipe(title: String = "Teriyaki"): Long = recipes.saveDraft(RecipeDraft(title = title))

    private suspend fun insertDated(recipeId: Long, date: String, synced: Boolean = true): Long =
        db.mealPlanDao().insert(
            MealPlanEntry(
                date = date,
                slot = MealSlot.DINNER,
                dishId = recipeId,
                titleSnapshot = "Teriyaki",
                remoteId = "old-$date",
                syncedAt = if (synced) 1000L else null,
            ),
        )

    @Test
    fun `a meal added on a real date lands on that weekday in week one`() = runTest {
        val id = recipe()
        plan.addToPlan(id, today, MealSlot.DINNER)

        val stored = db.mealPlanDao().getAll().single()
        assertEquals(FixedWeek.dateFor(1, DayOfWeek.FRIDAY).toString(), stored.date)
        assertEquals(1, plan.weekPlan(1).first().size)
        assertTrue(plan.weekPlan(2).first().isEmpty())
    }

    @Test
    fun `a meal added to a week two slot stays in week two`() = runTest {
        val id = recipe()
        plan.addToPlan(id, FixedWeek.dateFor(2, DayOfWeek.TUESDAY), MealSlot.LUNCH)

        assertTrue(plan.weekPlan(1).first().isEmpty())
        assertEquals(1, plan.weekPlan(2).first().size)
        assertEquals(1, plan.dayPlan(FixedWeek.dateFor(2, DayOfWeek.TUESDAY)).first().size)
    }

    @Test
    fun `moving a synced meal re-issues it so the move reaches the server`() = runTest {
        val id = recipe()
        val entryId = plan.addToPlan(id, FixedWeek.dateFor(1, DayOfWeek.MONDAY), MealSlot.DINNER)
        val before = db.mealPlanDao().byId(entryId)!!
        plan.stampSynced(entryId, 5000)

        plan.move(entryId, FixedWeek.dateFor(2, DayOfWeek.SUNDAY), MealSlot.LUNCH)

        val after = db.mealPlanDao().byId(entryId)!!
        assertEquals(FixedWeek.dateFor(2, DayOfWeek.SUNDAY).toString(), after.date)
        assertEquals(MealSlot.LUNCH, after.slot)
        assertNotEquals(before.remoteId, after.remoteId)
        assertNull(after.syncedAt)
        // Pending again, and the old id is gone from what the phone says it holds.
        assertEquals(listOf(entryId), plan.pendingSyncPush().map { it.id })
        assertFalse(before.remoteId in plan.syncPresentIds())
    }

    @Test
    fun `a dated plan converts this week and next, and leaves other weeks alone`() = runTest {
        val id = recipe()
        val lastWeek = insertDated(id, "2026-11-06")
        val thisWeek = insertDated(id, "2026-11-10") // Tuesday
        val nextWeek = insertDated(id, "2026-11-19") // Thursday of the following week
        val farOff = insertDated(id, "2026-12-01")

        plan.convertLegacyDates(today)

        val dao = db.mealPlanDao()
        assertEquals(FixedWeek.dateFor(1, DayOfWeek.TUESDAY).toString(), dao.byId(thisWeek)!!.date)
        assertEquals(FixedWeek.dateFor(2, DayOfWeek.THURSDAY).toString(), dao.byId(nextWeek)!!.date)
        // Converted rows go back up as new ones.
        assertNull(dao.byId(thisWeek)!!.syncedAt)
        assertNotEquals("old-2026-11-10", dao.byId(thisWeek)!!.remoteId)
        // Rows from other weeks are untouched: still there, just outside the plan.
        assertEquals("2026-11-06", dao.byId(lastWeek)!!.date)
        assertEquals("2026-12-01", dao.byId(farOff)!!.date)
        assertEquals(1000L, dao.byId(lastWeek)!!.syncedAt)
        assertEquals(2, plan.weekPlan(1).first().size + plan.weekPlan(2).first().size)
    }

    @Test
    fun `converting twice changes nothing the second time`() = runTest {
        val id = recipe()
        val entryId = insertDated(id, "2026-11-10")
        plan.convertLegacyDates(today)
        val once = db.mealPlanDao().byId(entryId)!!

        plan.convertLegacyDates(today.plusWeeks(3))

        assertEquals(once, db.mealPlanDao().byId(entryId)!!)
    }

    @Test
    fun `ticks clear when a new week starts, and only then`() = runTest {
        val id = recipe()
        val entryId = plan.addToPlan(id, FixedWeek.dateFor(1, DayOfWeek.MONDAY), MealSlot.DINNER)
        val maintenance = PlanMaintenance(plan, planView)
        planView.markTicksResetFor("2026-11-02") // an earlier week's reset has already run

        plan.setCooked(entryId, true)
        maintenance.run(today)
        assertFalse("a new week clears the tick", db.mealPlanDao().byId(entryId)!!.cooked)

        plan.setCooked(entryId, true)
        maintenance.run(today.plusDays(2)) // Sunday, same week
        assertTrue("the same week keeps it", db.mealPlanDao().byId(entryId)!!.cooked)

        maintenance.run(today.plusDays(3)) // the next Monday
        assertFalse(db.mealPlanDao().byId(entryId)!!.cooked)
        assertEquals("the meal itself stays planned", 1, plan.weekPlan(1).first().size)
    }

    @Test
    fun `active week follows the plan length`() = runTest {
        planView.set(weeks = 2, activeWeek = 2)
        assertEquals(2, planView.current().activeWeek)

        planView.set(weeks = 1)
        assertEquals("a one-week plan only has week one", 1, planView.current().activeWeek)
    }
}
