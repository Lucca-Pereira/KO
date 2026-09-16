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

    @Query("SELECT * FROM shopping_items WHERE remoteId = :remoteId LIMIT 1")
    suspend fun byRemoteId(remoteId: String): ShoppingListItem?

    /** Shopping items only ever push once, as new rows — no edit-sync, so no updatedAt to check. */
    @Query("SELECT * FROM shopping_items WHERE syncedAt IS NULL")
    suspend fun pendingPush(): List<ShoppingListItem>

    @Query("UPDATE shopping_items SET syncedAt = :syncedAt WHERE id = :id")
    suspend fun stampSynced(id: Long, syncedAt: Long)

    @Query("UPDATE shopping_items SET remoteId = :remoteId, syncedAt = :syncedAt WHERE id = :id")
    suspend fun stampSync(id: Long, remoteId: String, syncedAt: Long)

    @Query("UPDATE shopping_items SET remoteId = :remoteId WHERE id = :id")
    suspend fun setRemoteId(id: Long, remoteId: String)

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
