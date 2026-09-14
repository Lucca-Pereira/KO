package com.lucca.ko.data.remote.nas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The wire contract with the KO brain service.
 *
 * Deliberately separate from the Room entities. Serialising entities directly is the mistake
 * the backup format already made once, where adding a column silently changed the on-disk
 * shape; the same trap applies to an API the phone and the server version independently.
 *
 * The server speaks snake_case, so every field carries an explicit @SerialName rather than
 * relying on a global naming strategy — a rename is then a compile-time-visible edit in one
 * place, not a silently broken field.
 */

// ---- Health -------------------------------------------------------------------------

@Serializable
data class HealthDto(
    val ok: Boolean = false,
    val version: String = "",
    val ollama: OllamaHealthDto = OllamaHealthDto(),
    val busy: Boolean = false,
    @SerialName("queue_depth") val queueDepth: Int = 0,
    @SerialName("cache_entries") val cacheEntries: Int = 0,
)

@Serializable
data class OllamaHealthDto(
    val reachable: Boolean = false,
    val models: List<String> = emptyList(),
    val configured: Map<String, String> = emptyMap(),
    /** Models the server is configured to use that are not installed on it. */
    val missing: List<String> = emptyList(),
)

// ---- Pantry -------------------------------------------------------------------------

@Serializable
data class PantryEntryDto(
    val name: String,
    @SerialName("search_name") val searchName: String? = null,
    val status: String = "IN_STOCK",
)

// ---- Suggestions --------------------------------------------------------------------

@Serializable
data class SuggestRequestDto(
    val pantry: List<PantryEntryDto> = emptyList(),
    val count: Int = 5,
    val exclude: List<String> = emptyList(),
    val constraints: String = "",
)

@Serializable
data class IdeaDto(
    val title: String,
    val why: String = "",
    val query: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("est_minutes") val estMinutes: Int? = null,
)

@Serializable
data class SuggestResponseDto(
    val ideas: List<IdeaDto> = emptyList(),
    val model: String = "",
    val note: String? = null,
)

// ---- Translation --------------------------------------------------------------------

@Serializable
data class TranslateRequestDto(val names: List<String>, val target: String = "en")

@Serializable
data class TranslateResponseDto(
    val translations: Map<String, String> = emptyMap(),
    val model: String = "",
)

// ---- Recipes ------------------------------------------------------------------------

@Serializable
data class RecipeIngredientDtoNas(
    val name: String,
    val amount: String = "",
    val optional: Boolean = false,
    val section: String? = null,
)

@Serializable
data class RecipeStepDtoNas(
    val text: String,
    val minutes: Int? = null,
)

@Serializable
data class RecipeDtoNas(
    val title: String,
    val servings: Int = 2,
    @SerialName("prep_minutes") val prepMinutes: Int? = null,
    @SerialName("cook_minutes") val cookMinutes: Int? = null,
    val ingredients: List<RecipeIngredientDtoNas> = emptyList(),
    val steps: List<RecipeStepDtoNas> = emptyList(),
    val tags: List<String> = emptyList(),
    val notes: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("mealdb_id") val mealdbId: String? = null,
)

@Serializable
data class GenerateRecipeRequestDto(
    val prompt: String = "",
    val pantry: List<PantryEntryDto> = emptyList(),
    val servings: Int = 2,
    val constraints: String = "",
)

@Serializable
data class MealSummaryDtoNas(
    val id: String,
    val title: String,
    @SerialName("thumb_url") val thumbUrl: String? = null,
)

@Serializable
data class MealSearchResponseDto(val results: List<MealSummaryDtoNas> = emptyList())

// ---- Nutrition ----------------------------------------------------------------------

@Serializable
data class MacrosDto(
    val kcal: Double = 0.0,
    @SerialName("protein_g") val proteinG: Double = 0.0,
    @SerialName("carbs_g") val carbsG: Double = 0.0,
    @SerialName("fat_g") val fatG: Double = 0.0,
    @SerialName("fiber_g") val fiberG: Double? = null,
)

@Serializable
data class NutritionIngredientDto(
    val name: String,
    /** The app's own normalisation, so both sides key on the same string. */
    @SerialName("normalized_name") val normalizedName: String = "",
    val quantity: Double? = null,
    val unit: String? = null,
    val raw: String = "",
)

@Serializable
data class EstimateRequestDto(
    val title: String = "",
    val servings: Int = 1,
    val ingredients: List<NutritionIngredientDto> = emptyList(),
)

@Serializable
data class IngredientEstimateDto(
    val name: String,
    val grams: Double? = null,
    val macros: MacrosDto = MacrosDto(),
    /** TABLE, AI or UNKNOWN — what produced this line. */
    val method: String = "UNKNOWN",
    val confidence: Double = 0.0,
)

@Serializable
data class EstimateResponseDto(
    @SerialName("per_serving") val perServing: MacrosDto = MacrosDto(),
    val total: MacrosDto = MacrosDto(),
    @SerialName("per_ingredient") val perIngredient: List<IngredientEstimateDto> = emptyList(),
    /** Fraction of the ingredients actually accounted for, 0..1. */
    val coverage: Double = 0.0,
    val note: String = "",
)

// ---- Foods --------------------------------------------------------------------------

@Serializable
data class FoodDtoNas(
    val barcode: String? = null,
    val name: String,
    val brand: String? = null,
    val source: String = "LOCAL",
    @SerialName("serving_label") val servingLabel: String? = null,
    @SerialName("serving_grams") val servingGrams: Double? = null,
    @SerialName("kcal_per_100") val kcalPer100: Double = 0.0,
    @SerialName("protein_per_100") val proteinPer100: Double = 0.0,
    @SerialName("carbs_per_100") val carbsPer100: Double = 0.0,
    @SerialName("fat_per_100") val fatPer100: Double = 0.0,
    @SerialName("fiber_per_100") val fiberPer100: Double? = null,
    @SerialName("sugar_per_100") val sugarPer100: Double? = null,
    @SerialName("sat_fat_per_100") val satFatPer100: Double? = null,
    @SerialName("sodium_mg_per_100") val sodiumMgPer100: Double? = null,
    @SerialName("is_supplement") val isSupplement: Boolean = false,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
data class FoodSearchResponseDto(val results: List<FoodDtoNas> = emptyList())

@Serializable
data class FoodSeedResponseDto(
    @SerialName("updated_at") val updatedAt: Long = 0,
    val foods: List<FoodDtoNas> = emptyList(),
)

// ---- Chat ---------------------------------------------------------------------------

@Serializable
data class ChatMessageDto(val role: String, val content: String)

@Serializable
data class RecipeSnapshotDto(
    val title: String,
    val servings: Int = 2,
    val ingredients: List<RecipeIngredientDtoNas> = emptyList(),
    val steps: List<RecipeStepDtoNas> = emptyList(),
    val notes: String? = null,
)

@Serializable
data class ChatRequestDto(
    val recipe: RecipeSnapshotDto,
    val history: List<ChatMessageDto> = emptyList(),
    val message: String,
    val pantry: List<PantryEntryDto> = emptyList(),
)

@Serializable
data class ChatTokenDto(val t: String = "")

@Serializable
data class ChatProposalDto(val summary: String = "", val recipe: RecipeDtoNas)

@Serializable
data class ChatDoneDto(val chars: Int = 0)

@Serializable
data class ChatErrorDto(val message: String = "")

/** One frame of a recipe-chat stream. */
sealed interface ChatEvent {
    data class Token(val text: String) : ChatEvent

    data class Proposal(val summary: String, val recipe: RecipeDtoNas) : ChatEvent

    data class Done(val chars: Int) : ChatEvent

    data class Failed(val message: String) : ChatEvent
}
