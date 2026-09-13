package com.lucca.ko.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lucca.ko.data.db.MealPlanEntry
import com.lucca.ko.data.db.relations.PlannedDish
import kotlinx.coroutines.flow.Flow

@Dao
interface MealPlanDao {
    @Transaction
    @Query("SELECT * FROM meal_plan WHERE date BETWEEN :start AND :end ORDER BY date, slot")
    fun observeRange(start: String, end: String): Flow<List<PlannedDish>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: MealPlanEntry): Long

    @Query("DELETE FROM meal_plan WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM meal_plan")
    suspend fun getAll(): List<MealPlanEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<MealPlanEntry>)
}
