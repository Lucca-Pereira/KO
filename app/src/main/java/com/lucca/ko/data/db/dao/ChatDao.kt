package com.lucca.ko.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.lucca.ko.data.db.RecipeChatMessage
import com.lucca.ko.data.db.RecipeRevision
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM recipe_chat_messages WHERE dishId = :dishId ORDER BY createdAt, id")
    fun observeMessages(dishId: Long): Flow<List<RecipeChatMessage>>

    @Query("SELECT * FROM recipe_chat_messages WHERE dishId = :dishId ORDER BY createdAt, id")
    suspend fun messagesFor(dishId: Long): List<RecipeChatMessage>

    @Query("SELECT * FROM recipe_chat_messages WHERE id = :id")
    suspend fun messageById(id: Long): RecipeChatMessage?

    @Insert
    suspend fun insertMessage(message: RecipeChatMessage): Long

    @Update
    suspend fun updateMessage(message: RecipeChatMessage)

    @Query("DELETE FROM recipe_chat_messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("DELETE FROM recipe_chat_messages WHERE dishId = :dishId")
    suspend fun clearChat(dishId: Long)

    // ---- Revisions -------------------------------------------------------------------

    @Insert
    suspend fun insertRevision(revision: RecipeRevision): Long

    @Query("SELECT * FROM recipe_revisions WHERE dishId = :dishId ORDER BY createdAt DESC, id DESC")
    fun observeRevisions(dishId: Long): Flow<List<RecipeRevision>>

    @Query("SELECT * FROM recipe_revisions WHERE dishId = :dishId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latestRevision(dishId: Long): RecipeRevision?

    @Query("DELETE FROM recipe_revisions WHERE id = :id")
    suspend fun deleteRevision(id: Long)

    /**
     * Keeps only the [keep] most recent snapshots for a recipe.
     *
     * This is an undo stack, not a history feature: a snapshot is a whole serialized recipe, and
     * an unbounded pile of them would quietly become the largest thing in the database.
     */
    @Query(
        """
        DELETE FROM recipe_revisions
        WHERE dishId = :dishId
          AND id NOT IN (
              SELECT id FROM recipe_revisions
              WHERE dishId = :dishId
              ORDER BY createdAt DESC, id DESC
              LIMIT :keep
          )
        """,
    )
    suspend fun trimRevisions(dishId: Long, keep: Int)
}
