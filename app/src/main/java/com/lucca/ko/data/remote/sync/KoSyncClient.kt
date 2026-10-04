package com.lucca.ko.data.remote.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/*
 * Wire DTOs for POST /v1/sync — mirror `server/ko_sync/schemas.py` field-for-field. Kept separate
 * from `AgentImportRepository`'s file-import DTOs even though the shapes overlap: those omit
 * `remoteId`/`updatedAt`, these require them, and conflating the two would make an optional field
 * on one side silently become a required field on the other the moment either file changes.
 */

@Serializable
data class RecipeIngredientWire(val name: String, val amount: String = "", val optional: Boolean = false)

@Serializable
data class RecipeStepWire(val text: String, val minutes: Int? = null)

@Serializable
data class RecipeWire(
    val remoteId: String,
    val title: String,
    val servings: Int = 2,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    val kcalPerServing: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val macroNote: String? = null,
    val ingredients: List<RecipeIngredientWire> = emptyList(),
    val steps: List<RecipeStepWire> = emptyList(),
    /** Epoch millis. Last-write-wins against the stored row's own `updatedAt` on conflict. */
    val updatedAt: Long,
)

@Serializable
data class PantryItemWire(
    val remoteId: String,
    val name: String,
    val status: String = "IN_STOCK",
    val category: String? = null,
    val quantity: String? = null,
    val note: String? = null,
    val updatedAt: Long,
)

@Serializable
data class ShoppingItemWire(val remoteId: String, val name: String)

@Serializable
data class MealPlanEntryWire(
    val remoteId: String,
    val recipeTitle: String,
    val date: String,
    val slot: String,
    val servings: Double? = null,
)

@Serializable
data class NutritionEntryWire(
    val remoteId: String,
    val date: String,
    val slot: String = "SNACK",
    val sourceType: String = "QUICK",
    /** Resolved to a local supplement by name; the NAS has no supplement ids. */
    val supplementName: String? = null,
    val label: String,
    val grams: Double? = null,
    val servings: Double? = null,
    val kcal: Double = 0.0,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val fiberG: Double? = null,
    val note: String? = null,
    /** A tombstone from Claude: delete the local row with this remoteId. */
    val deleted: Boolean = false,
    val updatedAt: Long,
)

@Serializable
data class SupplementLogWire(
    val remoteId: String,
    val date: String,
    val supplementName: String,
    val doses: Double = 1.0,
    val deleted: Boolean = false,
    val updatedAt: Long,
)

@Serializable
data class BodyMetricWire(
    /** The key, on both sides: one row per date. */
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
    val updatedAt: Long,
)

/** Phone-owned: sent whole every sync so Claude knows what can be ticked off. */
@Serializable
data class SupplementWire(
    val name: String,
    val kind: String,
    val doseAmount: Double,
    val doseUnit: String,
    val kcalPerDose: Double,
    val proteinPerDose: Double,
    val carbsPerDose: Double,
    val fatPerDose: Double,
    val dosesPerDay: Int,
    val active: Boolean,
)

/** Phone-owned: the target history, so Claude can answer "how much protein is left?". */
@Serializable
data class TargetWire(
    val effectiveFrom: String,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val source: String,
)

/** How the meal-plan screen is laid out. Last-write-wins on [updatedAt], phone or Claude. */
@Serializable
data class PlanViewWire(val weeks: Int = 1, val calendar: Boolean = true, val updatedAt: Long)

/** remoteIds Claude deleted, per kitchen collection: the phone drops them on pull. */
@Serializable
data class DeletedWire(
    val recipes: List<String> = emptyList(),
    val pantryItems: List<String> = emptyList(),
    val shoppingItems: List<String> = emptyList(),
    val mealPlanEntries: List<String> = emptyList(),
)

@Serializable
data class SyncPush(
    val recipes: List<RecipeWire> = emptyList(),
    val pantryItems: List<PantryItemWire> = emptyList(),
    val shoppingItems: List<ShoppingItemWire> = emptyList(),
    val mealPlanEntries: List<MealPlanEntryWire> = emptyList(),
    val nutritionEntries: List<NutritionEntryWire> = emptyList(),
    val supplementLogs: List<SupplementLogWire> = emptyList(),
    val bodyMetrics: List<BodyMetricWire> = emptyList(),
    val planView: PlanViewWire? = null,
)

/** Every remoteId the phone currently has, per collection: how deletions made here reach the NAS
 *  (it drops rows it knows the phone has already seen but no longer lists). */
@Serializable
data class SyncPresent(
    val recipes: List<String> = emptyList(),
    val pantryItems: List<String> = emptyList(),
    val shoppingItems: List<String> = emptyList(),
    val mealPlanEntries: List<String> = emptyList(),
    val nutritionEntries: List<String> = emptyList(),
    val supplementLogs: List<String> = emptyList(),
    /** Dates — a body metric's key. */
    val bodyMetrics: List<String> = emptyList(),
)

/** The phone-owned gym context the NAS keeps a copy of. */
data class GymSnapshot(val supplements: List<SupplementWire>, val targets: List<TargetWire>)

@Serializable
private data class SyncRequest(
    val lastSyncedAt: Long = 0,
    val push: SyncPush = SyncPush(),
    val present: SyncPresent? = null,
    val supplements: List<SupplementWire>? = null,
    val targets: List<TargetWire>? = null,
)

@Serializable
data class SyncResponse(
    val serverTime: Long,
    val recipes: List<RecipeWire> = emptyList(),
    val pantryItems: List<PantryItemWire> = emptyList(),
    val shoppingItems: List<ShoppingItemWire> = emptyList(),
    val mealPlanEntries: List<MealPlanEntryWire> = emptyList(),
    val nutritionEntries: List<NutritionEntryWire> = emptyList(),
    val supplementLogs: List<SupplementLogWire> = emptyList(),
    val bodyMetrics: List<BodyMetricWire> = emptyList(),
    val planView: PlanViewWire? = null,
    val deleted: DeletedWire = DeletedWire(),
)

class KoSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thin client for the NAS sync server's one push-then-pull endpoint, `POST /v1/sync`.
 *
 * Unlike [com.lucca.ko.data.remote.OpenFoodFactsClient], `baseUrl` is a per-call parameter here
 * rather than a constructor field: the NAS address is something the user sets and can change in
 * Settings at any time, not a fixed public API endpoint. Tests pass MockWebServer's URL the same
 * way a real caller passes the NAS's.
 */
class KoSyncClient(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun sync(
        baseUrl: String,
        lastSyncedAt: Long,
        push: SyncPush,
        token: String,
        present: SyncPresent? = null,
        gym: GymSnapshot? = null,
    ): SyncResponse =
        withContext(Dispatchers.IO) {
            val payload = SyncRequest(lastSyncedAt, push, present, gym?.supplements, gym?.targets)
            val body = json.encodeToString(SyncRequest.serializer(), payload)
            val url = baseUrl.trimEnd('/') + "/v1/sync"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody(jsonMediaType))
                .build()
            http.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw KoSyncException("Sync failed: HTTP ${response.code} $responseBody")
                }
                try {
                    json.decodeFromString(SyncResponse.serializer(), responseBody)
                } catch (e: Exception) {
                    throw KoSyncException("Sync failed: couldn't parse the server's response.", e)
                }
            }
        }
}
