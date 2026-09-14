package com.lucca.ko.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lucca.ko.data.db.RecipeTag
import com.lucca.ko.data.db.Tag
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Tag>>

    @Query("SELECT * FROM tags WHERE normalizedName = :normalized LIMIT 1")
    suspend fun byNormalized(normalized: String): Tag?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: Tag): Long

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: Long)

    /** Tags with no recipe left attached — swept after a recipe is deleted or retagged. */
    @Query("DELETE FROM tags WHERE id NOT IN (SELECT tagId FROM recipe_tags)")
    suspend fun deleteUnusedTags()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(link: RecipeTag)

    @Query("DELETE FROM recipe_tags WHERE dishId = :dishId")
    suspend fun unlinkAllFor(dishId: Long)

    @Query("SELECT t.* FROM tags t JOIN recipe_tags rt ON rt.tagId = t.id WHERE rt.dishId = :dishId")
    suspend fun tagsFor(dishId: Long): List<Tag>

    @Query("SELECT tagId FROM recipe_tags WHERE dishId = :dishId")
    suspend fun tagIdsFor(dishId: Long): List<Long>

    // ---- Backup ---------------------------------------------------------------------

    @Query("SELECT * FROM tags")
    suspend fun getAllTags(): List<Tag>

    @Query("SELECT * FROM recipe_tags")
    suspend fun getAllLinks(): List<RecipeTag>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllTags(tags: List<Tag>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllLinks(links: List<RecipeTag>)
}
