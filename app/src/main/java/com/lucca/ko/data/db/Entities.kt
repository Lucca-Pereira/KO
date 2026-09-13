package com.lucca.ko.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class StockStatus { IN_STOCK, LOW, OUT }

@Serializable
enum class MealSlot { BREAKFAST, LUNCH, DINNER, OTHER }

/** Where a recipe came from. Drives what the UI offers (re-import, fork, edit warnings). */
@Serializable
enum class RecipeSource { MEALDB, MANUAL, AI, IMPORT }

/** How a recipe's per-serving macros were arrived at. */
@Serializable
enum class MacroSource { AI, COMPUTED, MANUAL }

/** Preset categories offered in the UI; [PantryItem.category] is still a free-text column. */
val PRESET_CATEGORIES = listOf(
    "Produce", "Dairy & Eggs", "Meat & Fish", "Grains & Pasta", "Canned & Jars",
    "Spices & Herbs", "Baking", "Condiments & Oils", "Frozen", "Snacks", "Drinks", "Other",
)

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

/**
 * A recipe. Owned independently of the meal plan: deleting a planned meal leaves the recipe in
 * the library, and one recipe can be planned on any number of days.
 *
 * The table is still called `dishes` and the child key is still `dishId`. Renaming them would
 * force a full rebuild of three tables on SQLite 3.18 (minSdk 26), which cannot rename a column,
 * drop a column, or rewrite a foreign key declared in another table — three chances to lose data
 * for a cosmetic win.
 *
 * Every NOT NULL column added after v2 carries an explicit [ColumnInfo.defaultValue] matching the
 * migration SQL exactly; Room compares defaults when it validates the schema, and a Kotlin default
 * alone is invisible to it.
 */
@Entity(
    tableName = "dishes",
    // NULLs are distinct in a SQLite unique index, so every manual recipe coexists happily while
    // a MealDB recipe can only be imported once.
    indices = [Index(value = ["mealdbId"], unique = true)],
)
data class Recipe(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceUrl: String? = null,
    val imageUrl: String? = null,
    val mealdbId: String? = null,
    /** Original prose instructions. Kept verbatim forever as the fallback for [RecipeStep]. */
    val instructions: String? = null,
    val createdAt: Long = System.currentTimeMillis(),

    /** User-taken photo under `filesDir/recipe_images/`; wins over [imageUrl] when set. */
    val imageLocalPath: String? = null,
    @ColumnInfo(defaultValue = "2") val servings: Int = 2,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val notes: String? = null,
    @ColumnInfo(defaultValue = "0") val isFavourite: Boolean = false,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    val lastCookedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val timesCooked: Int = 0,
    @ColumnInfo(defaultValue = "'MANUAL'") val source: RecipeSource = RecipeSource.MANUAL,
    /** Set when this recipe was forked from another; deliberately not a foreign key. */
    val forkedFromId: Long? = null,

    val kcalPerServing: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val macroSource: MacroSource? = null,
    val macroUpdatedAt: Long? = null,
    /** Confidence / assumptions from the estimator, shown under the nutrition card. */
    val macroNote: String? = null,

    /** Lowercased title + tags + ingredient names; library search LIKEs against this. */
    val searchBlob: String? = null,
)

@Entity(
    tableName = "dish_ingredients",
    foreignKeys = [
        ForeignKey(
            entity = Recipe::class,
            parentColumns = ["id"],
            childColumns = ["dishId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("dishId")],
)
data class RecipeIngredient(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dishId: Long,
    val rawName: String,
    val normalizedName: String,
    /**
     * The original free-text amount ("1 1/2 cups"). Never rewritten: it is what [quantity] and
     * [unit] were parsed from, and the fallback shown when parsing failed.
     */
    val measure: String? = null,
    /** Manual override linking this line to a specific pantry item. */
    val pantryItemId: Long? = null,

    val quantity: Double? = null,
    val unit: String? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "0") val optional: Boolean = false,
    /** Grouping header, e.g. "For the sauce". */
    val section: String? = null,
    val note: String? = null,
)

@Entity(
    tableName = "recipe_steps",
    foreignKeys = [
        ForeignKey(
            entity = Recipe::class,
            parentColumns = ["id"],
            childColumns = ["dishId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("dishId")],
)
data class RecipeStep(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dishId: Long,
    val position: Int,
    val text: String,
    val minutes: Int? = null,
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["normalizedName"], unique = true)],
)
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
)

@Entity(
    tableName = "recipe_tags",
    primaryKeys = ["dishId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = Recipe::class,
            parentColumns = ["id"],
            childColumns = ["dishId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tagId")],
)
data class RecipeTag(
    val dishId: Long,
    val tagId: Long,
)

/**
 * One planned meal. [dishId] is nullable with `ON DELETE SET NULL`: deleting a recipe must not
 * erase the fact that you cooked it, so the entry survives showing [titleSnapshot].
 */
@Entity(
    tableName = "meal_plan",
    foreignKeys = [
        ForeignKey(
            entity = Recipe::class,
            parentColumns = ["id"],
            childColumns = ["dishId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("dishId"), Index("date")],
)
data class MealPlanEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO local date, yyyy-MM-dd. */
    val date: String,
    val slot: MealSlot = MealSlot.DINNER,
    val dishId: Long?,
    /** The recipe title as it was when planned; all that remains if the recipe is deleted. */
    @ColumnInfo(defaultValue = "''") val titleSnapshot: String = "",
    @ColumnInfo(defaultValue = "1.0") val servings: Double = 1.0,
    val note: String? = null,
    @ColumnInfo(defaultValue = "0") val cooked: Boolean = false,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
)

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
