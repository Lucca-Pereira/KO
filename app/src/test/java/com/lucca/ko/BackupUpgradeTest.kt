package com.lucca.ko

import com.lucca.ko.data.backup.DishIngredientV1
import com.lucca.ko.data.backup.DishV1
import com.lucca.ko.data.backup.KoBackupV1
import com.lucca.ko.data.backup.MealPlanEntryV1
import com.lucca.ko.data.backup.PantryItemV1
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.RecipeSource
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.domain.backup.BackupUpgrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The v1 -> v2 backup upgrade must reach the same end state as `MIGRATION_2_3` does on a live
 * database: restoring an old backup and upgrading in place should not give you two different
 * libraries. The assertions here deliberately mirror `MigrationTest`.
 */
class BackupUpgradeTest {

    /** The same shape as MigrationTest's fixture: one meal added twice, plus a manual recipe. */
    private fun fixture() = KoBackupV1(
        pantry = listOf(
            PantryItemV1(id = 1, name = "Cebolla", normalizedName = "cebolla"),
            PantryItemV1(
                id = 2,
                name = "Orégano",
                normalizedName = "gano",
                status = StockStatus.LOW,
            ),
        ),
        dishes = listOf(
            DishV1(id = 1, title = "Teriyaki Chicken", mealdbId = "52772", createdAt = 100),
            DishV1(id = 2, title = "Teriyaki Chicken", mealdbId = "52772", createdAt = 200),
            DishV1(id = 3, title = "Abuela's stew", createdAt = 300),
        ),
        dishIngredients = listOf(
            DishIngredientV1(id = 1, dishId = 1, rawName = "chicken", normalizedName = "chicken", measure = "2 lbs"),
            DishIngredientV1(id = 2, dishId = 1, rawName = "soy sauce", normalizedName = "soy sauce", measure = "1/2 cup"),
            DishIngredientV1(id = 3, dishId = 2, rawName = "chicken", normalizedName = "chicken", measure = "2 lbs"),
            DishIngredientV1(id = 4, dishId = 3, rawName = "Cebolla", normalizedName = "cebolla"),
        ),
        mealPlan = listOf(
            MealPlanEntryV1(id = 1, date = "2026-09-14", slot = MealSlot.DINNER, dishId = 1),
            MealPlanEntryV1(id = 2, date = "2026-09-16", slot = MealSlot.DINNER, dishId = 2),
            MealPlanEntryV1(id = 3, date = "2026-09-17", slot = MealSlot.LUNCH, dishId = 3),
        ),
    )

    @Test
    fun `collapses duplicate mealdb recipes onto the lowest id`() {
        val v2 = BackupUpgrade.upgrade(fixture())
        assertEquals(2, v2.recipes.size)
        assertEquals(listOf(1L, 3L), v2.recipes.map { it.id })
    }

    @Test
    fun `repoints plan entries at the surviving recipe and keeps manual ones untouched`() {
        val v2 = BackupUpgrade.upgrade(fixture())
        assertEquals(3, v2.mealPlan.size)
        assertEquals(1L, v2.mealPlan.first { it.id == 1L }.dishId)
        assertEquals(1L, v2.mealPlan.first { it.id == 2L }.dishId)
        assertEquals(3L, v2.mealPlan.first { it.id == 3L }.dishId)
    }

    @Test
    fun `fills the title snapshot on every plan entry`() {
        val v2 = BackupUpgrade.upgrade(fixture())
        assertEquals("Teriyaki Chicken", v2.mealPlan.first { it.id == 2L }.titleSnapshot)
        assertEquals("Abuela's stew", v2.mealPlan.first { it.id == 3L }.titleSnapshot)
    }

    @Test
    fun `drops the ingredients of the collapsed duplicate`() {
        val v2 = BackupUpgrade.upgrade(fixture())
        assertEquals(3, v2.recipeIngredients.size)
        assertEquals(0, v2.recipeIngredients.count { it.dishId == 2L })
    }

    @Test
    fun `orders ingredients by their original insertion order`() {
        val v2 = BackupUpgrade.upgrade(fixture())
        val forFirst = v2.recipeIngredients.filter { it.dishId == 1L }.sortedBy { it.sortOrder }
        assertEquals(listOf("chicken", "soy sauce"), forFirst.map { it.rawName })
        assertEquals(listOf(0, 1), forFirst.map { it.sortOrder })
    }

    @Test
    fun `parses measures on the way in`() {
        val v2 = BackupUpgrade.upgrade(fixture())
        val soy = v2.recipeIngredients.first { it.rawName == "soy sauce" }
        assertEquals(0.5, soy.quantity)
        assertEquals("cup", soy.unit)
        // The original text is never discarded.
        assertEquals("1/2 cup", soy.measure)
        assertNull(v2.recipeIngredients.first { it.dishId == 3L }.quantity)
    }

    @Test
    fun `tags each recipe with where it came from`() {
        val v2 = BackupUpgrade.upgrade(fixture())
        assertEquals(RecipeSource.MEALDB, v2.recipes.first { it.id == 1L }.source)
        assertEquals(RecipeSource.MANUAL, v2.recipes.first { it.id == 3L }.source)
    }

    @Test
    fun `renormalises names saved before the accent-folding fix`() {
        val v2 = BackupUpgrade.upgrade(fixture())
        // "Orégano" was stored as "gano" by the old normalizer.
        assertEquals("oregano", v2.pantry.first { it.id == 2L }.normalizedName)
        assertEquals("cebolla", v2.pantry.first { it.id == 1L }.normalizedName)
    }

    @Test
    fun `announces itself as schema 2`() {
        assertEquals(2, BackupUpgrade.upgrade(fixture()).schema)
    }

    @Test
    fun `handles an empty backup`() {
        val v2 = BackupUpgrade.upgrade(KoBackupV1())
        assertEquals(0, v2.recipes.size)
        assertEquals(0, v2.mealPlan.size)
        assertEquals(0, v2.pantry.size)
    }

    @Test
    fun `keeps a plan entry whose recipe is missing from the file entirely`() {
        val broken = fixture().let {
            it.copy(mealPlan = it.mealPlan + MealPlanEntryV1(id = 9, date = "2026-09-18", dishId = 99))
        }
        val v2 = BackupUpgrade.upgrade(broken)
        val orphan = v2.mealPlan.first { it.id == 9L }
        // dishId is nullable now, so a dangling reference costs the link, not the entry.
        assertNull(orphan.dishId)
        assertEquals("", orphan.titleSnapshot)
        assertEquals(4, v2.mealPlan.size)
    }
}
