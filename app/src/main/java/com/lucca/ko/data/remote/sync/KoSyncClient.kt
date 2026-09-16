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
data class SyncPush(
    val recipes: List<RecipeWire> = emptyList(),
    val pantryItems: List<PantryItemWire> = emptyList(),
    val shoppingItems: List<ShoppingItemWire> = emptyList(),
    val mealPlanEntries: List<MealPlanEntryWire> = emptyList(),
)

@Serializable
private data class SyncRequest(val lastSyncedAt: Long = 0, val push: SyncPush = SyncPush())

@Serializable
data class SyncResponse(
    val serverTime: Long,
    val recipes: List<RecipeWire> = emptyList(),
    val pantryItems: List<PantryItemWire> = emptyList(),
    val shoppingItems: List<ShoppingItemWire> = emptyList(),
    val mealPlanEntries: List<MealPlanEntryWire> = emptyList(),
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

    suspend fun sync(baseUrl: String, lastSyncedAt: Long, push: SyncPush, token: String): SyncResponse =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(SyncRequest.serializer(), SyncRequest(lastSyncedAt, push))
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
