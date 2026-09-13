package com.lucca.ko.data.db.relations

import androidx.room.Embedded
import androidx.room.Relation
import com.lucca.ko.data.db.Dish
import com.lucca.ko.data.db.DishIngredient
import com.lucca.ko.data.db.MealPlanEntry

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
