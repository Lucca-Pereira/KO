package com.lucca.ko.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class StockStatus { IN_STOCK, LOW, OUT }

@Serializable
enum class MealSlot { BREAKFAST, LUNCH, DINNER, OTHER }

/** Preset categories offered in the UI; [category] is still a free-text column. */
val PRESET_CATEGORIES = listOf(
    "Produce", "Dairy & Eggs", "Meat & Fish", "Grains & Pasta", "Canned & Jars",
    "Spices & Herbs", "Baking", "Condiments & Oils", "Frozen", "Snacks", "Drinks", "Other",
)

@Serializable
@Entity(
    tableName = "pantry_items",
    indices = [Index(value = ["normalizedName"], unique = true)],
)
data class PantryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val category: String = "Other",
    val status: StockStatus = StockStatus.IN_STOCK,
    val quantity: String? = null,
    val note: String? = null,
    /** English name for recipe search / matching, e.g. "mantequilla" -> "butter".
     *  Null until the user runs "Translate pantry" in Settings. */
    val searchName: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "dishes")
data class Dish(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceUrl: String? = null,
    val imageUrl: String? = null,
    val mealdbId: String? = null,
    val instructions: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(
    tableName = "dish_ingredients",
    foreignKeys = [
        ForeignKey(
            entity = Dish::class,
            parentColumns = ["id"],
            childColumns = ["dishId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("dishId")],
)
data class DishIngredient(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dishId: Long,
    val rawName: String,
    val normalizedName: String,
    val measure: String? = null,
    /** Manual override linking this line to a specific pantry item. */
    val pantryItemId: Long? = null,
)

@Serializable
@Entity(
    tableName = "meal_plan",
    foreignKeys = [
        ForeignKey(
            entity = Dish::class,
            parentColumns = ["id"],
            childColumns = ["dishId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("dishId"), Index("date")],
)
data class MealPlanEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO local date, yyyy-MM-dd. */
    val date: String,
    val slot: MealSlot = MealSlot.DINNER,
    val dishId: Long,
)

@Serializable
@Entity(
    tableName = "shopping_items",
    indices = [Index(value = ["normalizedName"], unique = true)],
)
data class ShoppingListItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val category: String = "Other",
    val pantryItemId: Long? = null,
    val checked: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
)
