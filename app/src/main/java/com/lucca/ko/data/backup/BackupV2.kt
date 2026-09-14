package com.lucca.ko.data.backup

import com.lucca.ko.data.db.MacroSource
import com.lucca.ko.data.db.MealPlanEntry
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.Recipe
import com.lucca.ko.data.db.RecipeIngredient
import com.lucca.ko.data.db.RecipeSource
import com.lucca.ko.data.db.RecipeStep
import com.lucca.ko.data.db.RecipeTag
import com.lucca.ko.data.db.ShoppingListItem
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.data.db.Tag
import kotlinx.serialization.Serializable

/*
 * The schema-2 backup format: wire DTOs that mirror the current schema but are versioned
 * independently of it, so adding a Room column is a deliberate decision here rather than a
 * silent change to the file format.
 */

@Serializable
data class PantryItemV2(
    val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val category: String = "Other",
    val status: StockStatus = StockStatus.IN_STOCK,
    val quantity: String? = null,
    val note: String? = null,
    val searchName: String? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class RecipeV2(
    val id: Long = 0,
    val title: String,
    val sourceUrl: String? = null,
    val imageUrl: String? = null,
    val mealdbId: String? = null,
    val instructions: String? = null,
    val createdAt: Long = 0,
    val imageLocalPath: String? = null,
    val servings: Int = 2,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val notes: String? = null,
    val isFavourite: Boolean = false,
    val updatedAt: Long = 0,
    val lastCookedAt: Long? = null,
    val timesCooked: Int = 0,
    val source: RecipeSource = RecipeSource.MANUAL,
    val forkedFromId: Long? = null,
    val kcalPerServing: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val macroSource: MacroSource? = null,
    val macroUpdatedAt: Long? = null,
    val macroNote: String? = null,
)

@Serializable
data class RecipeIngredientV2(
    val id: Long = 0,
    val dishId: Long,
    val rawName: String,
    val normalizedName: String,
    val measure: String? = null,
    val pantryItemId: Long? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val sortOrder: Int = 0,
    val optional: Boolean = false,
    val section: String? = null,
    val note: String? = null,
)

@Serializable
data class RecipeStepV2(
    val id: Long = 0,
    val dishId: Long,
    val position: Int,
    val text: String,
    val minutes: Int? = null,
)

@Serializable
data class TagV2(val id: Long = 0, val name: String, val normalizedName: String)

@Serializable
data class RecipeTagV2(val dishId: Long, val tagId: Long)

@Serializable
data class MealPlanEntryV2(
    val id: Long = 0,
    val date: String,
    val slot: MealSlot = MealSlot.DINNER,
    val dishId: Long? = null,
    val titleSnapshot: String = "",
    val servings: Double = 1.0,
    val note: String? = null,
    val cooked: Boolean = false,
    val sortOrder: Int = 0,
)

@Serializable
data class ShoppingListItemV2(
    val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val category: String = "Other",
    val pantryItemId: Long? = null,
    val checked: Boolean = false,
    val addedAt: Long = 0,
)

/**
 * Settings carried in a backup.
 *
 * Note what is missing: the NAS bearer token. Secrets live in their own DataStore file and are
 * excluded from both the JSON export and Android's cloud backup — restoring onto a new phone
 * should ask for the token, not carry it around in a file you might email yourself.
 */
@Serializable
data class SettingsV2(
    val nasBaseUrl: String = "",
    val suggestionCount: Int = 5,
)

@Serializable
data class KoBackupV2(
    val schema: Int = 2,
    val app: String = "KO Kitchen",
    val exportedAt: Long = 0,
    val settings: SettingsV2 = SettingsV2(),
    val pantry: List<PantryItemV2> = emptyList(),
    val recipes: List<RecipeV2> = emptyList(),
    val recipeIngredients: List<RecipeIngredientV2> = emptyList(),
    val recipeSteps: List<RecipeStepV2> = emptyList(),
    val tags: List<TagV2> = emptyList(),
    val recipeTags: List<RecipeTagV2> = emptyList(),
    val mealPlan: List<MealPlanEntryV2> = emptyList(),
    val shopping: List<ShoppingListItemV2> = emptyList(),
)

// ---- Entity <-> DTO -----------------------------------------------------------------

fun PantryItem.toV2() = PantryItemV2(
    id, name, normalizedName, category, status, quantity, note, searchName, updatedAt,
)

fun PantryItemV2.toEntity() = PantryItem(
    id, name, normalizedName, category, status, quantity, note, searchName, updatedAt,
)

fun Recipe.toV2() = RecipeV2(
    id = id, title = title, sourceUrl = sourceUrl, imageUrl = imageUrl, mealdbId = mealdbId,
    instructions = instructions, createdAt = createdAt, imageLocalPath = imageLocalPath,
    servings = servings, prepMinutes = prepMinutes, cookMinutes = cookMinutes, notes = notes,
    isFavourite = isFavourite, updatedAt = updatedAt, lastCookedAt = lastCookedAt,
    timesCooked = timesCooked, source = source, forkedFromId = forkedFromId,
    kcalPerServing = kcalPerServing, proteinG = proteinG, carbsG = carbsG, fatG = fatG,
    macroSource = macroSource, macroUpdatedAt = macroUpdatedAt, macroNote = macroNote,
)

fun RecipeV2.toEntity() = Recipe(
    id = id, title = title, sourceUrl = sourceUrl, imageUrl = imageUrl, mealdbId = mealdbId,
    instructions = instructions, createdAt = createdAt, imageLocalPath = imageLocalPath,
    servings = servings, prepMinutes = prepMinutes, cookMinutes = cookMinutes, notes = notes,
    isFavourite = isFavourite, updatedAt = updatedAt, lastCookedAt = lastCookedAt,
    timesCooked = timesCooked, source = source, forkedFromId = forkedFromId,
    kcalPerServing = kcalPerServing, proteinG = proteinG, carbsG = carbsG, fatG = fatG,
    macroSource = macroSource, macroUpdatedAt = macroUpdatedAt, macroNote = macroNote,
    // Rebuilt on import from the restored title / ingredients / tags.
    searchBlob = null,
)

fun RecipeIngredient.toV2() = RecipeIngredientV2(
    id, dishId, rawName, normalizedName, measure, pantryItemId, quantity, unit, sortOrder,
    optional, section, note,
)

fun RecipeIngredientV2.toEntity() = RecipeIngredient(
    id, dishId, rawName, normalizedName, measure, pantryItemId, quantity, unit, sortOrder,
    optional, section, note,
)

fun RecipeStep.toV2() = RecipeStepV2(id, dishId, position, text, minutes)
fun RecipeStepV2.toEntity() = RecipeStep(id, dishId, position, text, minutes)

fun Tag.toV2() = TagV2(id, name, normalizedName)
fun TagV2.toEntity() = Tag(id, name, normalizedName)

fun RecipeTag.toV2() = RecipeTagV2(dishId, tagId)
fun RecipeTagV2.toEntity() = RecipeTag(dishId, tagId)

fun MealPlanEntry.toV2() =
    MealPlanEntryV2(id, date, slot, dishId, titleSnapshot, servings, note, cooked, sortOrder)

fun MealPlanEntryV2.toEntity() =
    MealPlanEntry(id, date, slot, dishId, titleSnapshot, servings, note, cooked, sortOrder)

fun ShoppingListItem.toV2() =
    ShoppingListItemV2(id, name, normalizedName, category, pantryItemId, checked, addedAt)

fun ShoppingListItemV2.toEntity() =
    ShoppingListItem(id, name, normalizedName, category, pantryItemId, checked, addedAt)
