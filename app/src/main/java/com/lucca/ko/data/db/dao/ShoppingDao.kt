package com.lucca.ko.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lucca.ko.data.db.ShoppingListItem
import kotlinx.coroutines.flow.Flow

@Dao
interface ShoppingDao {
    @Query("SELECT * FROM shopping_items ORDER BY checked, category COLLATE NOCASE, name COLLATE NOCASE")
    fun observeAll(): Flow<List<ShoppingListItem>>

    @Query("SELECT * FROM shopping_items WHERE normalizedName = :normalized LIMIT 1")
    suspend fun byNormalized(normalized: String): ShoppingListItem?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: ShoppingListItem): Long

    @Update
    suspend fun update(item: ShoppingListItem)

    @Query("DELETE FROM shopping_items WHERE normalizedName = :normalized AND checked = 0")
    suspend fun deleteUncheckedByNormalized(normalized: String)

    @Query("DELETE FROM shopping_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM shopping_items WHERE checked = 1")
    suspend fun clearChecked()

    @Query("SELECT * FROM shopping_items")
    suspend fun getAll(): List<ShoppingListItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ShoppingListItem>)
}
