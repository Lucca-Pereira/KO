package com.lucca.ko.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** Where a food's numbers came from. */
@Serializable
enum class FoodSource { OFF, LOCAL, AI, MANUAL }

/** What a logged line actually is. */
@Serializable
enum class LogSource { FOOD, RECIPE, QUICK, SUPPLEMENT }

/** When it was eaten. Distinct from [MealSlot], which is about planning, not logging. */
@Serializable
enum class LogSlot { BREAKFAST, LUNCH, DINNER, SNACK, SUPPLEMENT }

/** The kinds of supplement worth treating differently. */
@Serializable
enum class SupplementKind { CREATINE, PROTEIN, OTHER }

/**
 * A food, with macros per 100 g.
 *
 * Everything is canonicalised to per-100 g — including Open Food Facts, which reports that way —
 * so portion maths is one function rather than a special case per source. [servingGrams] is what
 * makes "2 scoops" or "1 egg" weighable; without it the estimator refuses rather than guessing.
 */
@Entity(
    tableName = "food_items",
    indices = [
        Index(value = ["barcode"], unique = true),
        Index(value = ["normalizedName"]),
    ],
)
data class FoodItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val brand: String? = null,
    val barcode: String? = null,
    val source: FoodSource = FoodSource.MANUAL,
    /** Bundled seed rows, so a future re-seed can replace them without touching your edits. */
    @ColumnInfo(defaultValue = "0") val readOnly: Boolean = false,
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
    @ColumnInfo(defaultValue = "0") val isSupplement: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isFavourite: Boolean = false,
    val imageUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * One thing eaten, on one day.
 *
 * **The row carries its own macros and its own label**, and only *additionally* references the
 * food or recipe it came from. Three reasons, all of which bite otherwise: correcting a food's
 * macros next month must not silently rewrite last month's history; deleting a food must not
 * orphan a day's total; and the day total stays a plain SUM with no joins.
 *
 * The references exist so "log again" can prefill and so the UI can show provenance — which is
 * why they are `ON DELETE SET NULL` rather than CASCADE.
 */
@Entity(
    tableName = "nutrition_entries",
    foreignKeys = [
        ForeignKey(
            entity = FoodItem::class,
            parentColumns = ["id"],
            childColumns = ["foodItemId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = Recipe::class,
            parentColumns = ["id"],
            childColumns = ["dishId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("date"), Index("foodItemId"), Index("dishId")],
)
data class NutritionEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO local date, yyyy-MM-dd — the same convention as the meal plan. */
    val date: String,
    val slot: LogSlot = LogSlot.SNACK,
    val loggedAt: Long = System.currentTimeMillis(),
    val sourceType: LogSource = LogSource.QUICK,
    val foodItemId: Long? = null,
    val dishId: Long? = null,
    /** No foreign key: `supplements` does not exist until the next migration. */
    val supplementId: Long? = null,
    /** A snapshot of the name, so a deleted food leaves a readable line. */
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

/**
 * The target in force from a given date.
 *
 * Point-in-time rather than a single current value, so a day in February is judged against
 * February's target rather than against whatever you changed it to in June.
 */
@Entity(
    tableName = "nutrition_targets",
    indices = [Index(value = ["effectiveFrom"], unique = true)],
)
data class NutritionTarget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val effectiveFrom: String,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    /** FORMULA, ADAPTIVE or MANUAL. */
    val source: String = "FORMULA",
)

/**
 * A weigh-in, and whatever else was measured that day.
 *
 * One row per date — a second weigh-in replaces the first, because two numbers for one morning
 * is not information, it is noise.
 */
@Entity(
    tableName = "body_metrics",
    indices = [Index(value = ["date"], unique = true)],
)
data class BodyMetric(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val recordedAt: Long = System.currentTimeMillis(),
)

/**
 * Something taken daily.
 *
 * Kept separate from [FoodItem] because the question is different: for a protein shake you want
 * the macros, for creatine you want to know whether you took it. A supplement that carries
 * macros points at a food item; creatine does not need one.
 */
@Entity(tableName = "supplements")
data class Supplement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: SupplementKind = SupplementKind.OTHER,
    val doseAmount: Double = 1.0,
    /** g, scoop, capsule… */
    val doseUnit: String = "g",
    @ColumnInfo(defaultValue = "0") val kcalPerDose: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val proteinPerDose: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val carbsPerDose: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val fatPerDose: Double = 0.0,
    @ColumnInfo(defaultValue = "1") val dosesPerDay: Int = 1,
    @ColumnInfo(defaultValue = "1") val active: Boolean = true,
    val foodItemId: Long? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
) {
    /** Whether logging this should also put a line in the food diary. */
    val affectsMacros: Boolean
        get() = kcalPerDose > 0 || proteinPerDose > 0 || carbsPerDose > 0 || fatPerDose > 0
}

/** One day's dose. Unique per supplement per day: this is adherence, not a running tally. */
@Entity(
    tableName = "supplement_log",
    foreignKeys = [
        ForeignKey(
            entity = Supplement::class,
            parentColumns = ["id"],
            childColumns = ["supplementId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["date", "supplementId"], unique = true), Index("supplementId")],
)
data class SupplementLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val supplementId: Long,
    @ColumnInfo(defaultValue = "1") val doses: Double = 1.0,
    val takenAt: Long = System.currentTimeMillis(),
    val note: String? = null,
)
