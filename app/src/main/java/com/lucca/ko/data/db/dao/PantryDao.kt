package com.lucca.ko.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.StockStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface PantryDao {
    @Query("SELECT * FROM pantry_items ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PantryItem>>

    @Query("SELECT * FROM pantry_items WHERE id = :id")
    suspend fun byId(id: Long): PantryItem?

    @Query("SELECT * FROM pantry_items WHERE normalizedName = :normalized LIMIT 1")
    suspend fun byNormalized(normalized: String): PantryItem?

    @Upsert
    suspend fun upsert(item: PantryItem): Long

    @Update
    suspend fun update(item: PantryItem)

    @Query("SELECT * FROM pantry_items")
    suspend fun getAll(): List<PantryItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PantryItem>)

    @Query("UPDATE pantry_items SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStatus(id: Long, status: StockStatus, updatedAt: Long)

    @Query("DELETE FROM pantry_items WHERE id = :id")
    suspend fun delete(id: Long)
}
