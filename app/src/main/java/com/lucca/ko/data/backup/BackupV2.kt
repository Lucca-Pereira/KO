package com.lucca.ko.data.backup

import com.lucca.ko.data.db.BodyMetric
import com.lucca.ko.data.db.FoodItem
import com.lucca.ko.data.db.FoodSource
import com.lucca.ko.data.db.LogSlot
import com.lucca.ko.data.db.LogSource
import com.lucca.ko.data.db.MacroSource
import com.lucca.ko.data.db.NutritionEntry
import com.lucca.ko.data.db.NutritionTarget
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
import com.lucca.ko.data.db.Supplement
import com.lucca.ko.data.db.SupplementKind
import com.lucca.ko.data.db.SupplementLog
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

@Serializable
data class FoodItemV2(
    val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val brand: String? = null,
    val barcode: String? = null,
    val source: FoodSource = FoodSource.MANUAL,
    val readOnly: Boolean = false,
    val servingLabel: String? = null,
    val servingGrams: Double? = null,
    val kcalPer100: Double = 0.0,
    val proteinPer100: Double = 0.0,
    val carbsPer100: Double = 0.0,
    val fatPer100: Double = 0.0,
    val fiberPer100: Double? = null,
    val sugarPer100: Double? = null,
    val satFatPer100: Double? = null,
    val sodiumMgPer100: Double? = null,
    val isSupplement: Boolean = false,
    val isFavourite: Boolean = false,
    val imageUrl: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class NutritionEntryV2(
    val id: Long = 0,
    val date: String,
    val slot: LogSlot = LogSlot.SNACK,
    val loggedAt: Long = 0,
    val sourceType: LogSource = LogSource.QUICK,
    val foodItemId: Long? = null,
    val dishId: Long? = null,
    val supplementId: Long? = null,
    val label: String,
    val grams: Double? = null,
    val servings: Double? = null,
    val kcal: Double = 0.0,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val fiberG: Double? = null,
    val note: String? = null,
)

@Serializable
data class NutritionTargetV2(
    val id: Long = 0,
    val effectiveFrom: String,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val source: String = "FORMULA",
)

@Serializable
data class BodyMetricV2(
    val id: Long = 0,
    val date: String,
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    val waistCm: Double? = null,
    val chestCm: Double? = null,
    val hipCm: Double? = null,
    val armCm: Double? = null,
    val thighCm: Double? = null,
    val neckCm: Double? = null,
    val note: String? = null,
    val recordedAt: Long = 0,
)

@Serializable
data class SupplementV2(
    val id: Long = 0,
    val name: String,
    val kind: SupplementKind = SupplementKind.OTHER,
    val doseAmount: Double = 1.0,
    val doseUnit: String = "g",
    val kcalPerDose: Double = 0.0,
    val proteinPerDose: Double = 0.0,
    val carbsPerDose: Double = 0.0,
    val fatPerDose: Double = 0.0,
    val dosesPerDay: Int = 1,
    val active: Boolean = true,
    val foodItemId: Long? = null,
    val sortOrder: Int = 0,
)

@Serializable
data class SupplementLogV2(
    val id: Long = 0,
    val date: String,
    val supplementId: Long,
    val doses: Double = 1.0,
    val takenAt: Long = 0,
    val note: String? = null,
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
    // The gym side. All default to empty, so a file written before v0.7.0 still reads.
    val foods: List<FoodItemV2> = emptyList(),
    val nutritionEntries: List<NutritionEntryV2> = emptyList(),
    val nutritionTargets: List<NutritionTargetV2> = emptyList(),
    val bodyMetrics: List<BodyMetricV2> = emptyList(),
    val supplements: List<SupplementV2> = emptyList(),
    val supplementLog: List<SupplementLogV2> = emptyList(),
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

fun FoodItem.toV2() = FoodItemV2(
    id, name, normalizedName, brand, barcode, source, readOnly, servingLabel, servingGrams,
    kcalPer100, proteinPer100, carbsPer100, fatPer100, fiberPer100, sugarPer100, satFatPer100,
    sodiumMgPer100, isSupplement, isFavourite, imageUrl, createdAt, updatedAt,
)

fun FoodItemV2.toEntity() = FoodItem(
    id, name, normalizedName, brand, barcode, source, readOnly, servingLabel, servingGrams,
    kcalPer100, proteinPer100, carbsPer100, fatPer100, fiberPer100, sugarPer100, satFatPer100,
    sodiumMgPer100, isSupplement, isFavourite, imageUrl, createdAt, updatedAt,
)

fun NutritionEntry.toV2() = NutritionEntryV2(
    id, date, slot, loggedAt, sourceType, foodItemId, dishId, supplementId, label, grams,
    servings, kcal, proteinG, carbsG, fatG, fiberG, note,
)

fun NutritionEntryV2.toEntity() = NutritionEntry(
    id, date, slot, loggedAt, sourceType, foodItemId, dishId, supplementId, label, grams,
    servings, kcal, proteinG, carbsG, fatG, fiberG, note,
)

fun NutritionTarget.toV2() =
    NutritionTargetV2(id, effectiveFrom, kcal, proteinG, carbsG, fatG, source)

fun NutritionTargetV2.toEntity() =
    NutritionTarget(id, effectiveFrom, kcal, proteinG, carbsG, fatG, source)

fun BodyMetric.toV2() = BodyMetricV2(
    id, date, weightKg, bodyFatPct, waistCm, chestCm, hipCm, armCm, thighCm, neckCm, note,
    recordedAt,
)

fun BodyMetricV2.toEntity() = BodyMetric(
    id, date, weightKg, bodyFatPct, waistCm, chestCm, hipCm, armCm, thighCm, neckCm, note,
    recordedAt,
)

fun Supplement.toV2() = SupplementV2(
    id, name, kind, doseAmount, doseUnit, kcalPerDose, proteinPerDose, carbsPerDose, fatPerDose,
    dosesPerDay, active, foodItemId, sortOrder,
)

fun SupplementV2.toEntity() = Supplement(
    id, name, kind, doseAmount, doseUnit, kcalPerDose, proteinPerDose, carbsPerDose, fatPerDose,
    dosesPerDay, active, foodItemId, sortOrder,
)

fun SupplementLog.toV2() = SupplementLogV2(id, date, supplementId, doses, takenAt, note)

fun SupplementLogV2.toEntity() = SupplementLog(id, date, supplementId, doses, takenAt, note)
