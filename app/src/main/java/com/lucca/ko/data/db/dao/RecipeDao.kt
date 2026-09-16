package com.lucca.ko.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.lucca.ko.data.db.Recipe
import com.lucca.ko.data.db.RecipeIngredient
import com.lucca.ko.data.db.RecipeStep
import com.lucca.ko.data.db.relations.RecipeWithDetails
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RecipeDao {

    // ---- Recipes -------------------------------------------------------------------

    @Insert
    abstract suspend fun insertRecipe(recipe: Recipe): Long

    @Update
    abstract suspend fun updateRecipe(recipe: Recipe)

    @Query("SELECT * FROM dishes WHERE id = :id")
    abstract suspend fun recipeById(id: Long): Recipe?

    @Query("SELECT * FROM dishes WHERE mealdbId = :mealdbId LIMIT 1")
    abstract suspend fun byMealdbId(mealdbId: String): Recipe?

    @Query("SELECT * FROM dishes WHERE remoteId = :remoteId LIMIT 1")
    abstract suspend fun byRemoteId(remoteId: String): Recipe?

    /** Never pushed, or edited since its last successful push. */
    @Query("SELECT * FROM dishes WHERE syncedAt IS NULL OR updatedAt > syncedAt")
    abstract suspend fun pendingPush(): List<Recipe>

    @Query("UPDATE dishes SET syncedAt = :syncedAt WHERE id = :id")
    abstract suspend fun stampSynced(id: Long, syncedAt: Long)

    /** Backfills a remoteId onto a row that predates sync existing at all (migrated in as NULL). */
    @Query("UPDATE dishes SET remoteId = :remoteId WHERE id = :id")
    abstract suspend fun setRemoteId(id: Long, remoteId: String)

    /**
     * Used only when applying a sync pull: an incoming row is authoritative about its own
     * remoteId/updatedAt, unlike [saveDraft][com.lucca.ko.data.repo.RecipeRepository.saveDraft]
     * (which always stamps `updatedAt = now()` for a normal save) — without this, a pulled recipe
     * would look locally-edited again the moment it lands, and re-push on the very next sync.
     */
    @Query("UPDATE dishes SET remoteId = :remoteId, updatedAt = :updatedAt, syncedAt = :syncedAt WHERE id = :id")
    abstract suspend fun stampSync(id: Long, remoteId: String, updatedAt: Long, syncedAt: Long)

    @Query("DELETE FROM dishes WHERE id = :id")
    abstract suspend fun deleteRecipe(id: Long)

    @Transaction
    @Query("SELECT * FROM dishes WHERE id = :id")
    abstract fun observeRecipe(id: Long): Flow<RecipeWithDetails?>

    @Transaction
    @Query("SELECT * FROM dishes WHERE id = :id")
    abstract suspend fun recipeWithDetailsOnce(id: Long): RecipeWithDetails?

    /**
     * Library search. `LIKE` on the maintained [Recipe.searchBlob] rather than FTS4: for a few
     * hundred recipes it is instantaneous, and FTS adds a shadow table plus triggers that make
     * every future migration harder.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM dishes
        WHERE :query = '' OR searchBlob LIKE '%' || :query || '%'
        ORDER BY isFavourite DESC, updatedAt DESC
        """,
    )
    abstract fun searchLibrary(query: String): Flow<List<RecipeWithDetails>>

    @Query("UPDATE dishes SET isFavourite = :favourite, updatedAt = :now WHERE id = :id")
    abstract suspend fun setFavourite(id: Long, favourite: Boolean, now: Long)

    @Query("UPDATE dishes SET searchBlob = :blob WHERE id = :id")
    abstract suspend fun setSearchBlob(id: Long, blob: String)

    @Query(
        """
        UPDATE dishes
        SET timesCooked = timesCooked + 1, lastCookedAt = :at, updatedAt = :at
        WHERE id = :id
        """,
    )
    abstract suspend fun recordCooked(id: Long, at: Long)

    // ---- Ingredients ---------------------------------------------------------------

    @Insert
    abstract suspend fun insertIngredient(ingredient: RecipeIngredient): Long

    @Insert
    abstract suspend fun insertIngredients(ingredients: List<RecipeIngredient>)

    @Update
    abstract suspend fun updateIngredient(ingredient: RecipeIngredient)

    @Query("SELECT * FROM dish_ingredients WHERE id = :id")
    abstract suspend fun ingredientById(id: Long): RecipeIngredient?

    @Query("SELECT * FROM dish_ingredients WHERE dishId = :dishId ORDER BY sortOrder")
    abstract suspend fun ingredientsFor(dishId: Long): List<RecipeIngredient>

    @Query("DELETE FROM dish_ingredients WHERE id = :id")
    abstract suspend fun deleteIngredient(id: Long)

    @Query("UPDATE dish_ingredients SET sortOrder = :order WHERE id = :id")
    abstract suspend fun setIngredientOrder(id: Long, order: Int)

    @Query("DELETE FROM dish_ingredients WHERE dishId = :dishId")
    abstract suspend fun deleteIngredientsFor(dishId: Long)

    /**
     * Replaces a recipe's ingredient list wholesale, which is how the editor and an accepted AI
     * patch both save. Rows are re-inserted rather than diffed, so ids are not stable across a
     * save; nothing outside the recipe references an ingredient id except `pantryItemId`, which
     * travels with the row.
     */
    @Transaction
    open suspend fun replaceIngredients(dishId: Long, ingredients: List<RecipeIngredient>) {
        deleteIngredientsFor(dishId)
        insertIngredients(
            ingredients.mapIndexed { i, ing -> ing.copy(id = 0, dishId = dishId, sortOrder = i) },
        )
    }

    /** Recipes whose titles collide, for the Find duplicates tool. */
    @Query("SELECT * FROM dishes ORDER BY id")
    abstract suspend fun allRecipesForDuplicateScan(): List<Recipe>

    // ---- Steps ---------------------------------------------------------------------

    @Insert
    abstract suspend fun insertSteps(steps: List<RecipeStep>)

    @Query("SELECT * FROM recipe_steps WHERE dishId = :dishId ORDER BY position")
    abstract suspend fun stepsFor(dishId: Long): List<RecipeStep>

    @Query("DELETE FROM recipe_steps WHERE dishId = :dishId")
    abstract suspend fun deleteStepsFor(dishId: Long)

    /** Replaces a recipe's steps wholesale — the editor and AI patches both save this way. */
    @Transaction
    open suspend fun replaceSteps(dishId: Long, steps: List<RecipeStep>) {
        deleteStepsFor(dishId)
        insertSteps(steps.mapIndexed { i, s -> s.copy(id = 0, dishId = dishId, position = i) })
    }

    @Transaction
    open suspend fun insertRecipeWithIngredients(
        recipe: Recipe,
        ingredients: List<RecipeIngredient>,
    ): Long {
        val id = insertRecipe(recipe)
        insertIngredients(
            ingredients.mapIndexed { i, ing -> ing.copy(dishId = id, sortOrder = i) },
        )
        return id
    }

    // ---- Backup ---------------------------------------------------------------------

    @Query("SELECT * FROM dishes")
    abstract suspend fun getAllRecipes(): List<Recipe>

    @Query("SELECT * FROM dish_ingredients")
    abstract suspend fun getAllIngredients(): List<RecipeIngredient>

    @Query("SELECT * FROM recipe_steps")
    abstract suspend fun getAllSteps(): List<RecipeStep>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAllRecipes(recipes: List<Recipe>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAllIngredients(ingredients: List<RecipeIngredient>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAllSteps(steps: List<RecipeStep>)
}
