package com.lucca.ko.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
data class RecipeIdea(
    val dish: String,
    val query: String = "",
    val reason: String = "",
)

class OllamaException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Talks to a user-hosted Ollama server on the LAN (e.g. http://192.168.1.20:11434). */
class OllamaClient(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    /** Returns the list of installed model names, or throws with a friendly message. */
    suspend fun listModels(baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        val url = baseUrl.normalizeBase() + "api/tags"
        val req = Request.Builder().url(url).build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw OllamaException("Server responded HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                json.decodeFromString<TagsResponse>(body).models.map { it.name }
            }
        } catch (e: IOException) {
            throw OllamaException(e.message ?: "Could not reach Ollama at $baseUrl", e)
        }
    }

    /**
     * Ask the model for recipe ideas that use the given pantry item names.
     * Returns an empty list if the model produced nothing usable.
     */
    suspend fun suggestRecipes(
        baseUrl: String,
        model: String,
        pantry: List<String>,
        count: Int,
    ): List<RecipeIdea> = withContext(Dispatchers.IO) {
        val system =
            "You are a kitchen assistant. Given a list of ingredients the user already has, " +
                "suggest realistic dishes they can mostly make now. Reply ONLY with JSON of the form " +
                "{\"suggestions\":[{\"dish\":\"...\",\"query\":\"<2-3 word search term>\",\"reason\":\"<short>\"}]}. " +
                "The query must be a common dish name suitable for a recipe search."
        val user = buildString {
            append("I have: ")
            append(pantry.joinToString(", ").ifBlank { "(pantry is empty, suggest popular easy dishes)" })
            append(". Suggest ")
            append(count)
            append(" dishes.")
        }
        val payload = ChatRequest(
            model = model,
            stream = false,
            format = "json",
            options = ChatOptions(temperature = 0.5),
            messages = listOf(
                ChatMessage("system", system),
                ChatMessage("user", user),
            ),
        )
        val url = baseUrl.normalizeBase() + "api/chat"
        val req = Request.Builder()
            .url(url)
            .post(json.encodeToString(ChatRequest.serializer(), payload).toRequestBody(jsonMedia))
            .build()
        val content = try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw OllamaException("Server responded HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                json.decodeFromString<ChatResponse>(body).message?.content.orEmpty()
            }
        } catch (e: IOException) {
            throw OllamaException(e.message ?: "Could not reach Ollama at $baseUrl", e)
        }
        parseIdeas(content)
    }

    private fun parseIdeas(content: String): List<RecipeIdea> {
        if (content.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<SuggestionsWrapper>(content).suggestions
        }.recoverCatching {
            json.decodeFromString<List<RecipeIdea>>(content)
        }.getOrDefault(emptyList())
            .filter { it.dish.isNotBlank() }
            .map { if (it.query.isBlank()) it.copy(query = it.dish) else it }
    }

    @Serializable private data class TagsResponse(val models: List<TagModel> = emptyList())
    @Serializable private data class TagModel(val name: String)

    @Serializable private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val stream: Boolean,
        val format: String? = null,
        val options: ChatOptions? = null,
    )
    @Serializable private data class ChatOptions(val temperature: Double)
    @Serializable private data class ChatMessage(val role: String, val content: String)
    @Serializable private data class ChatResponse(val message: ChatMessage? = null)

    @Serializable private data class SuggestionsWrapper(
        @SerialName("suggestions") val suggestions: List<RecipeIdea> = emptyList(),
    )
}

private fun String.normalizeBase(): String {
    var s = trim()
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
    if (!s.endsWith("/")) s = "$s/"
    return s
}
