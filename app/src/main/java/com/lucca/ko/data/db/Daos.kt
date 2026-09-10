package com.lucca.ko.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class DishWithIngredients(
    @Embedded val dish: Dish,
    @Relation(parentColumn = "id", entityColumn = "dishId")
    val ingredients: List<DishIngredient>,
)

data class PlannedDish(
    @Embedded val entry: MealPlanEntry,
    @Relation(parentColumn = "dishId", entityColumn = "id")
    val dish: Dish,
)

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

@Dao
abstract class DishDao {
    @Insert
    abstract suspend fun insertDish(dish: Dish): Long

    @Insert
    abstract suspend fun insertIngredients(ingredients: List<DishIngredient>)

    @Transaction
    open suspend fun insertDishWithIngredients(dish: Dish, ingredients: List<DishIngredient>): Long {
        val dishId = insertDish(dish)
        insertIngredients(ingredients.map { it.copy(dishId = dishId) })
        return dishId
    }

    @Transaction
    @Query("SELECT * FROM dishes WHERE id = :id")
    abstract fun observeDishWithIngredients(id: Long): Flow<DishWithIngredients?>

    @Transaction
    @Query("SELECT * FROM dishes WHERE id = :id")
    abstract suspend fun dishWithIngredientsOnce(id: Long): DishWithIngredients?

    @Query("SELECT * FROM dish_ingredients WHERE id = :id")
    abstract suspend fun ingredientById(id: Long): DishIngredient?

    @Update
    abstract suspend fun updateIngredient(ingredient: DishIngredient)

    @Query("DELETE FROM dishes WHERE id = :id")
    abstract suspend fun deleteDish(id: Long)

    @Query("DELETE FROM dishes WHERE id NOT IN (SELECT dishId FROM meal_plan)")
    abstract suspend fun deleteOrphanDishes()

    @Query("SELECT * FROM dishes")
    abstract suspend fun getAllDishes(): List<Dish>

    @Query("SELECT * FROM dish_ingredients")
    abstract suspend fun getAllIngredients(): List<DishIngredient>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAllDishes(dishes: List<Dish>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAllIngredients(ingredients: List<DishIngredient>)
}

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
