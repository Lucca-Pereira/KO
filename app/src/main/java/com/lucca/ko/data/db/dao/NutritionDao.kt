package com.lucca.ko.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.lucca.ko.data.db.BodyMetric
import com.lucca.ko.data.db.FoodItem
import com.lucca.ko.data.db.NutritionEntry
import com.lucca.ko.data.db.NutritionTarget
import com.lucca.ko.data.db.Supplement
import com.lucca.ko.data.db.SupplementLog
import kotlinx.coroutines.flow.Flow

/** One day's totals, computed in SQL because the entries carry their own macros. */
data class DayTotals(
    val kcal: Double = 0.0,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val fiberG: Double = 0.0,
)

/** A date and its calorie total, for the adaptive-TDEE mean. */
data class DailyKcal(val date: String, val kcal: Double)

@Dao
interface FoodDao {

    @Query("SELECT * FROM food_items WHERE id = :id")
    suspend fun byId(id: Long): FoodItem?

    @Query("SELECT * FROM food_items WHERE barcode = :barcode LIMIT 1")
    suspend fun byBarcode(barcode: String): FoodItem?

    @Query("SELECT * FROM food_items WHERE normalizedName = :normalized LIMIT 1")
    suspend fun byNormalized(normalized: String): FoodItem?

    @Query(
        """
        SELECT * FROM food_items
        WHERE :query = '' OR name LIKE '%' || :query || '%' OR brand LIKE '%' || :query || '%'
        ORDER BY isFavourite DESC, name COLLATE NOCASE
        LIMIT :limit
        """,
    )
    fun search(query: String, limit: Int = 60): Flow<List<FoodItem>>

    /** Recently logged foods, for the "log it again" shortcut that is most of real usage. */
    @Query(
        """
        SELECT f.* FROM food_items f
        JOIN nutrition_entries e ON e.foodItemId = f.id
        GROUP BY f.id
        ORDER BY MAX(e.loggedAt) DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int = 20): Flow<List<FoodItem>>

    @Upsert
    suspend fun upsert(food: FoodItem): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(foods: List<FoodItem>)

    @Update
    suspend fun update(food: FoodItem)

    @Query("DELETE FROM food_items WHERE id = :id AND readOnly = 0")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM food_items WHERE readOnly = 1")
    suspend fun seededCount(): Int

    @Query("SELECT * FROM food_items")
    suspend fun getAll(): List<FoodItem>
}

@Dao
interface NutritionDao {

    @Query("SELECT * FROM nutrition_entries WHERE date = :date ORDER BY slot, loggedAt")
    fun observeDay(date: String): Flow<List<NutritionEntry>>

    @Query("SELECT * FROM nutrition_entries WHERE date = :date")
    suspend fun entriesOn(date: String): List<NutritionEntry>

    @Query("SELECT * FROM nutrition_entries WHERE id = :id")
    suspend fun byId(id: Long): NutritionEntry?

    /**
     * A day's totals as one SUM with no joins — possible only because every entry carries its
     * own macros rather than pointing at a food whose numbers might since have changed.
     */
    @Query(
        """
        SELECT
            COALESCE(SUM(kcal), 0)     AS kcal,
            COALESCE(SUM(proteinG), 0) AS proteinG,
            COALESCE(SUM(carbsG), 0)   AS carbsG,
            COALESCE(SUM(fatG), 0)     AS fatG,
            COALESCE(SUM(fiberG), 0)   AS fiberG
        FROM nutrition_entries WHERE date = :date
        """,
    )
    fun observeDayTotals(date: String): Flow<DayTotals>

    @Query(
        """
        SELECT date, COALESCE(SUM(kcal), 0) AS kcal
        FROM nutrition_entries
        WHERE date BETWEEN :start AND :end
        GROUP BY date
        ORDER BY date
        """,
    )
    suspend fun dailyKcal(start: String, end: String): List<DailyKcal>

    @Insert
    suspend fun insert(entry: NutritionEntry): Long

    @Insert
    suspend fun insertAll(entries: List<NutritionEntry>)

    @Update
    suspend fun update(entry: NutritionEntry)

    @Query("DELETE FROM nutrition_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM nutrition_entries WHERE date = :date AND supplementId = :supplementId")
    suspend fun deleteSupplementEntry(date: String, supplementId: Long)

    @Query("SELECT * FROM nutrition_entries")
    suspend fun getAll(): List<NutritionEntry>

    // ---- Targets ---------------------------------------------------------------------

    /** The target in force on a date: the most recent one that started on or before it. */
    @Query(
        "SELECT * FROM nutrition_targets WHERE effectiveFrom <= :date " +
            "ORDER BY effectiveFrom DESC LIMIT 1",
    )
    fun observeTargetOn(date: String): Flow<NutritionTarget?>

    @Query(
        "SELECT * FROM nutrition_targets WHERE effectiveFrom <= :date " +
            "ORDER BY effectiveFrom DESC LIMIT 1",
    )
    suspend fun targetOn(date: String): NutritionTarget?

    @Upsert
    suspend fun upsertTarget(target: NutritionTarget)

    @Query("SELECT * FROM nutrition_targets ORDER BY effectiveFrom")
    suspend fun allTargets(): List<NutritionTarget>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllTargets(targets: List<NutritionTarget>)
}

@Dao
interface BodyDao {

    @Query("SELECT * FROM body_metrics ORDER BY date")
    fun observeAll(): Flow<List<BodyMetric>>

    @Query("SELECT * FROM body_metrics WHERE date = :date")
    suspend fun onDate(date: String): BodyMetric?

    @Query("SELECT * FROM body_metrics WHERE weightKg IS NOT NULL ORDER BY date")
    suspend fun weighIns(): List<BodyMetric>

    @Query("SELECT * FROM body_metrics WHERE weightKg IS NOT NULL ORDER BY date DESC LIMIT 1")
    suspend fun latestWeighIn(): BodyMetric?

    @Upsert
    suspend fun upsert(metric: BodyMetric)

    @Query("DELETE FROM body_metrics WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM body_metrics")
    suspend fun getAll(): List<BodyMetric>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(metrics: List<BodyMetric>)
}

@Dao
interface SupplementDao {

    @Query("SELECT * FROM supplements ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<Supplement>>

    @Query("SELECT * FROM supplements WHERE active = 1 ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeActive(): Flow<List<Supplement>>

    @Query("SELECT * FROM supplements WHERE id = :id")
    suspend fun byId(id: Long): Supplement?

    @Upsert
    suspend fun upsert(supplement: Supplement): Long

    @Query("DELETE FROM supplements WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM supplements")
    suspend fun count(): Int

    // ---- Log -------------------------------------------------------------------------

    @Query("SELECT * FROM supplement_log WHERE date = :date")
    fun observeDay(date: String): Flow<List<SupplementLog>>

    @Query("SELECT * FROM supplement_log WHERE supplementId = :supplementId ORDER BY date")
    fun observeHistory(supplementId: Long): Flow<List<SupplementLog>>

    @Query("SELECT * FROM supplement_log WHERE date = :date AND supplementId = :supplementId")
    suspend fun entryFor(date: String, supplementId: Long): SupplementLog?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun logDose(log: SupplementLog)

    @Query("DELETE FROM supplement_log WHERE date = :date AND supplementId = :supplementId")
    suspend fun unlog(date: String, supplementId: Long)

    @Query("SELECT * FROM supplements")
    suspend fun getAllSupplements(): List<Supplement>

    @Query("SELECT * FROM supplement_log")
    suspend fun getAllLogs(): List<SupplementLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllSupplements(supplements: List<Supplement>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllLogs(logs: List<SupplementLog>)
}
