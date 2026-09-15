package com.lucca.ko.data.repo

import com.lucca.ko.data.db.FoodItem
import com.lucca.ko.data.db.FoodSource
import com.lucca.ko.data.db.LogSlot
import com.lucca.ko.data.db.LogSource
import com.lucca.ko.data.db.NutritionEntry
import com.lucca.ko.data.db.NutritionTarget
import com.lucca.ko.data.db.dao.BodyDao
import com.lucca.ko.data.db.dao.DayTotals
import com.lucca.ko.data.db.dao.FoodDao
import com.lucca.ko.data.db.dao.NutritionDao
import com.lucca.ko.data.db.dao.RecipeDao
import com.lucca.ko.data.prefs.ProfileRepository
import com.lucca.ko.data.remote.OpenFoodFactsClient
import com.lucca.ko.data.remote.claude.ClaudeClient
import com.lucca.ko.data.remote.claude.ClaudeToolChoice
import com.lucca.ko.data.remote.claude.firstToolUse
import com.lucca.ko.data.remote.claude.textMessage
import com.lucca.ko.data.db.MacroSource
import com.lucca.ko.domain.IngredientMatcher
import com.lucca.ko.domain.nutrition.AdaptiveTdee
import com.lucca.ko.domain.nutrition.DatedValue
import com.lucca.ko.domain.nutrition.EnergyCalculator
import com.lucca.ko.domain.nutrition.MacroTargets
import com.lucca.ko.domain.nutrition.Per100g
import com.lucca.ko.domain.nutrition.PortionMath
import com.lucca.ko.domain.nutrition.TargetSource
import com.lucca.ko.domain.nutrition.UserProfile
import com.lucca.ko.domain.nutrition.WeightTrend
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.lucca.ko.data.remote.claude.ClaudeTool

/** What a recipe's macro estimate came back as. */
data class RecipeEstimate(
    val perServingKcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    /** 0..1 — how much of the recipe the estimate actually accounts for. */
    val coverage: Double,
    val note: String,
)

/** The day's target plus how it was arrived at. */
data class TargetWithProvenance(
    val targets: MacroTargets,
    val explanation: String,
)

class NutritionRepository(
    private val foodDao: FoodDao,
    private val nutritionDao: NutritionDao,
    private val bodyDao: BodyDao,
    private val recipeDao: RecipeDao,
    private val profileRepo: ProfileRepository,
    private val offClient: OpenFoodFactsClient,
    private val claude: ClaudeClient,
) {
    // ---- Reading ---------------------------------------------------------------------

    fun observeDay(date: LocalDate): Flow<List<NutritionEntry>> =
        nutritionDao.observeDay(date.toString())

    fun observeDayTotals(date: LocalDate): Flow<DayTotals> =
        nutritionDao.observeDayTotals(date.toString())

    fun observeTarget(date: LocalDate): Flow<NutritionTarget?> =
        nutritionDao.observeTargetOn(date.toString())

    fun searchFoods(query: String): Flow<List<FoodItem>> = foodDao.search(query.trim())

    fun observeRecentFoods(): Flow<List<FoodItem>> = foodDao.observeRecent()

    suspend fun foodById(id: Long): FoodItem? = foodDao.byId(id)

    // ---- Logging ---------------------------------------------------------------------

    /**
     * Logs a food by weight.
     *
     * The entry stores its own macros, not a pointer to the food's current ones: correcting a
     * food next month must not silently rewrite this line.
     */
    suspend fun logFood(
        food: FoodItem,
        grams: Double,
        date: LocalDate,
        slot: LogSlot,
    ): Long {
        val macros = PortionMath.macrosFor(food.toPer100g(), grams)
        return nutritionDao.insert(
            NutritionEntry(
                date = date.toString(),
                slot = slot,
                sourceType = if (food.isSupplement) LogSource.SUPPLEMENT else LogSource.FOOD,
                foodItemId = food.id,
                label = listOfNotNull(food.brand, food.name).joinToString(" "),
                grams = grams,
                servings = PortionMath.servingsFor(grams, food.servingGrams),
                kcal = macros.kcal,
                proteinG = macros.proteinG,
                carbsG = macros.carbsG,
                fatG = macros.fatG,
                fiberG = macros.fiberG.takeIf { it > 0 },
            ),
        )
    }

    /** Logs a portion of a recipe, using its stored per-serving estimate. */
    suspend fun logRecipe(
        recipeId: Long,
        servings: Double,
        date: LocalDate,
        slot: LogSlot,
    ): Long? {
        val recipe = recipeDao.recipeById(recipeId) ?: return null
        val kcal = recipe.kcalPerServing ?: return null
        return nutritionDao.insert(
            NutritionEntry(
                date = date.toString(),
                slot = slot,
                sourceType = LogSource.RECIPE,
                dishId = recipeId,
                label = recipe.title,
                servings = servings,
                kcal = kcal * servings,
                proteinG = (recipe.proteinG ?: 0.0) * servings,
                carbsG = (recipe.carbsG ?: 0.0) * servings,
                fatG = (recipe.fatG ?: 0.0) * servings,
            ),
        )
    }

    /** A calorie figure typed straight in, for anything not worth looking up. */
    suspend fun logQuick(
        label: String,
        kcal: Double,
        proteinG: Double = 0.0,
        carbsG: Double = 0.0,
        fatG: Double = 0.0,
        date: LocalDate,
        slot: LogSlot,
    ): Long = nutritionDao.insert(
        NutritionEntry(
            date = date.toString(),
            slot = slot,
            sourceType = LogSource.QUICK,
            label = label.trim().ifEmpty { "Quick entry" },
            kcal = kcal,
            proteinG = proteinG,
            carbsG = carbsG,
            fatG = fatG,
        ),
    )

    suspend fun deleteEntry(id: Long) = nutritionDao.delete(id)

    suspend fun updateEntry(entry: NutritionEntry) = nutritionDao.update(entry)

    // ---- Foods ------------------------------------------------------------------------

    suspend fun saveFood(food: FoodItem): Long = foodDao.upsert(
        food.copy(
            normalizedName = IngredientMatcher.normalize(food.name)
                .ifBlank { food.name.lowercase() },
            updatedAt = System.currentTimeMillis(),
        ),
    )

    suspend fun toggleFoodFavourite(food: FoodItem) =
        foodDao.update(food.copy(isFavourite = !food.isFavourite))

    suspend fun deleteFood(id: Long) = foodDao.delete(id)

    /**
     * Looks a barcode up: the local table first, then Open Food Facts directly.
     *
     * Called straight from the phone rather than through the recipe agent — the failure case
     * here is standing in a shop with no result, which is exactly when this needs to be fast.
     */
    suspend fun lookupBarcode(barcode: String): FoodItem? {
        foodDao.byBarcode(barcode)?.let { return it }

        val remote = runCatching { offClient.byBarcode(barcode) }.getOrNull() ?: return null

        val food = FoodItem(
            name = remote.name,
            normalizedName = IngredientMatcher.normalize(remote.name).ifBlank { remote.name.lowercase() },
            brand = remote.brand,
            barcode = barcode,
            source = FoodSource.OFF,
            servingLabel = remote.servingLabel,
            servingGrams = remote.servingGrams,
            kcalPer100 = remote.kcalPer100,
            proteinPer100 = remote.proteinPer100,
            carbsPer100 = remote.carbsPer100,
            fatPer100 = remote.fatPer100,
            fiberPer100 = remote.fiberPer100,
            sugarPer100 = remote.sugarPer100,
            satFatPer100 = remote.satFatPer100,
            sodiumMgPer100 = remote.sodiumMgPer100,
            imageUrl = remote.imageUrl,
        )
        // Cached locally so the next scan of the same packet is instant and works offline.
        return food.copy(id = foodDao.upsert(food))
    }

    // ---- Recipe macros -------------------------------------------------------------------

    /**
     * Asks Claude to estimate a recipe's per-serving macros from its ingredients, and stores the
     * result on the recipe.
     *
     * Forced to call [MACRO_REPORT_TOOL] rather than asked to answer in prose: reading the number
     * back out of a tool call's arguments is reliable, where parsing it out of a sentence is not.
     */
    suspend fun estimateRecipe(recipeId: Long): RecipeEstimate? {
        val details = recipeDao.recipeWithDetailsOnce(recipeId) ?: return null

        val ingredientLines = details.orderedIngredients.joinToString("\n") {
            "- ${listOf(it.measure.orEmpty(), it.rawName).filter(String::isNotBlank).joinToString(" ")}"
        }
        val prompt = "Recipe: ${details.recipe.title}\n" +
            "Servings: ${details.recipe.servings}\n" +
            "Ingredients:\n$ingredientLines"

        val response = runCatching {
            claude.send(
                messages = listOf(textMessage("user", prompt)),
                tools = listOf(MACRO_REPORT_TOOL),
                system = "You estimate recipe nutrition from general knowledge of common " +
                    "ingredients and portion sizes. Call report_macros exactly once with the " +
                    "PER-SERVING macros for the whole recipe (i.e. the total divided by its " +
                    "servings), not the total.",
                toolChoice = ClaudeToolChoice(type = "tool", name = "report_macros"),
                maxTokens = 400,
            )
        }.getOrNull() ?: return null

        val toolUse = response.firstToolUse() ?: return null
        val report = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString(MacroReport.serializer(), toolUse.input.toString())
        }.getOrNull() ?: return null

        recipeDao.updateRecipe(
            details.recipe.copy(
                kcalPerServing = report.kcal,
                proteinG = report.proteinG,
                carbsG = report.carbsG,
                fatG = report.fatG,
                macroSource = MacroSource.AI,
                macroUpdatedAt = System.currentTimeMillis(),
                macroNote = report.note,
                updatedAt = System.currentTimeMillis(),
            ),
        )

        return RecipeEstimate(
            perServingKcal = report.kcal,
            proteinG = report.proteinG,
            carbsG = report.carbsG,
            fatG = report.fatG,
            coverage = 1.0,
            note = report.note,
        )
    }

    // ---- Targets ---------------------------------------------------------------------------

    /**
     * The target for a date, recomputed from the profile and — when there is enough data — from
     * what your weight has actually been doing.
     */
    suspend fun computeTarget(date: LocalDate = LocalDate.now()): TargetWithProvenance? {
        val profile = profileRepo.current()
        val formula = EnergyCalculator.targetsFor(profile, date.year) ?: return null

        if (!profile.useAdaptiveTdee || profile.manualKcalTarget != null) {
            return TargetWithProvenance(
                targets = formula,
                explanation = if (profile.manualKcalTarget != null) {
                    "Your own calorie target, with macros split out of it."
                } else {
                    "From your height, weight and activity level."
                },
            )
        }

        val basal = EnergyCalculator.bmr(
            profile.sex, profile.weightKg, profile.heightCm, profile.ageAt(date.year),
        )
        val formulaTdee = EnergyCalculator.tdee(basal, profile.activity)

        val weighIns = bodyDao.weighIns().mapNotNull { metric ->
            metric.weightKg?.let { DatedValue(LocalDate.parse(metric.date), it) }
        }
        val slope = WeightTrend.slopeKgPerDay(WeightTrend.ema(weighIns))

        val since = date.minusDays(ADAPTIVE_WINDOW_DAYS)
        val logged = nutritionDao.dailyKcal(since.toString(), date.toString())
            .map { AdaptiveTdee.DayIntake(LocalDate.parse(it.date), it.kcal) }
        val complete = AdaptiveTdee.completeDays(logged, basal)

        val adaptive = AdaptiveTdee.estimate(
            formulaTdee = formulaTdee,
            trendSlopeKgPerDay = slope,
            completeDays = complete,
            totalDaysAvailable = logged.size,
        )

        if (!adaptive.isEstimate) {
            return TargetWithProvenance(formula, AdaptiveTdee.describe(adaptive))
        }

        return TargetWithProvenance(
            targets = EnergyCalculator.targetsFor(
                tdee = adaptive.kcal,
                bmr = basal,
                goal = profile.goal,
                weightKg = profile.weightKg,
                bodyFatPct = profile.bodyFatPct,
                proteinPerKgOverride = profile.proteinPerKgOverride,
                source = TargetSource.ADAPTIVE,
            ),
            explanation = AdaptiveTdee.describe(adaptive),
        )
    }

    /**
     * Stores the target as effective from [date].
     *
     * Point-in-time rather than a single current value, so a day in February is judged against
     * February's target rather than against whatever it became in June.
     */
    suspend fun persistTarget(targets: MacroTargets, date: LocalDate = LocalDate.now()) {
        val existing = nutritionDao.targetOn(date.toString())
        if (existing != null &&
            existing.effectiveFrom == date.toString() &&
            existing.kcal == targets.kcal &&
            existing.proteinG == targets.proteinG
        ) {
            return
        }
        nutritionDao.upsertTarget(
            NutritionTarget(
                id = existing?.takeIf { it.effectiveFrom == date.toString() }?.id ?: 0,
                effectiveFrom = date.toString(),
                kcal = targets.kcal,
                proteinG = targets.proteinG,
                carbsG = targets.carbsG,
                fatG = targets.fatG,
                source = targets.source.name,
            ),
        )
    }

    /** Recomputes and stores today's target — called after a profile edit or a weigh-in. */
    suspend fun refreshTodaysTarget(date: LocalDate = LocalDate.now()): TargetWithProvenance? =
        computeTarget(date)?.also { persistTarget(it.targets, date) }

    suspend fun profile(): UserProfile = profileRepo.current()

    private companion object {
        /** How far back the adaptive estimate looks. Four weeks is the usual settling time. */
        const val ADAPTIVE_WINDOW_DAYS = 28L

        val MACRO_REPORT_TOOL = ClaudeTool(
            name = "report_macros",
            description = "Reports a recipe's estimated per-serving macros.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put("kcal", buildJsonObject { put("type", "number") })
                        put("proteinG", buildJsonObject { put("type", "number") })
                        put("carbsG", buildJsonObject { put("type", "number") })
                        put("fatG", buildJsonObject { put("type", "number") })
                        put(
                            "note",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "One short sentence on any assumptions made.")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add("kcal"); add("proteinG"); add("carbsG"); add("fatG") })
            },
        )
    }
}

@Serializable
private data class MacroReport(
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val note: String = "",
)

fun FoodItem.toPer100g(): Per100g = Per100g(
    kcal = kcalPer100,
    proteinG = proteinPer100,
    carbsG = carbsPer100,
    fatG = fatPer100,
    fiberG = fiberPer100,
)

/** The whole day's totals, for the rings. */
fun DayTotals.asTotals() = com.lucca.ko.domain.nutrition.MacroTotals(
    kcal = kcal, proteinG = proteinG, carbsG = carbsG, fatG = fatG, fiberG = fiberG,
)

/** Reading a stored target back into the domain shape. */
fun NutritionTarget.toMacroTargets(): MacroTargets = MacroTargets(
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    source = runCatching { TargetSource.valueOf(source) }.getOrDefault(TargetSource.FORMULA),
)
