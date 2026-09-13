package com.lucca.ko.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.lucca.ko.data.db.MealPlanEntry
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.relations.PlannedRecipe
import kotlinx.coroutines.flow.Flow

@Dao
interface MealPlanDao {

    @Transaction
    @Query("SELECT * FROM meal_plan WHERE date BETWEEN :start AND :end ORDER BY date, slot, sortOrder")
    fun observeRange(start: String, end: String): Flow<List<PlannedRecipe>>

    @Query("SELECT * FROM meal_plan WHERE id = :id")
    suspend fun byId(id: Long): MealPlanEntry?

    @Query("SELECT COUNT(*) FROM meal_plan WHERE dishId = :dishId")
    suspend fun countForRecipe(dishId: Long): Int

    @Insert
    suspend fun insert(entry: MealPlanEntry): Long

    @Update
    suspend fun update(entry: MealPlanEntry)

    @Query("UPDATE meal_plan SET date = :date, slot = :slot WHERE id = :id")
    suspend fun move(id: Long, date: String, slot: MealSlot)

    @Query("UPDATE meal_plan SET servings = :servings WHERE id = :id")
    suspend fun setServings(id: Long, servings: Double)

    @Query("UPDATE meal_plan SET cooked = :cooked WHERE id = :id")
    suspend fun setCooked(id: Long, cooked: Boolean)

    /** Keeps the snapshot in step after a recipe is renamed, so history stays accurate. */
    @Query("UPDATE meal_plan SET titleSnapshot = :title WHERE dishId = :dishId")
    suspend fun refreshTitleSnapshots(dishId: Long, title: String)

    @Query("DELETE FROM meal_plan WHERE id = :id")
    suspend fun delete(id: Long)

    // ---- Backup ---------------------------------------------------------------------

    @Query("SELECT * FROM meal_plan")
    suspend fun getAll(): List<MealPlanEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<MealPlanEntry>)
}
