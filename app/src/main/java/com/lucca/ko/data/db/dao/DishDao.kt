package com.lucca.ko.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.lucca.ko.data.db.Dish
import com.lucca.ko.data.db.DishIngredient
import com.lucca.ko.data.db.relations.DishWithIngredients
import kotlinx.coroutines.flow.Flow

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
