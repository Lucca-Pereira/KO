package com.lucca.ko.data.remote.claude

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Something went wrong talking to Claude, with a message fit to show a user. */
class ClaudeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The Anthropic model this app talks to. Sonnet is the balance of quality and cost for a mobile agent. */
const val DEFAULT_CLAUDE_MODEL = "claude-sonnet-5"

/** Thin client for the Anthropic Messages API. One endpoint, no SDK needed. */
class ClaudeClient(
    private val http: OkHttpClient,
    private val apiKeyProvider: suspend () -> String,
    private val model: String = DEFAULT_CLAUDE_MODEL,
    private val baseUrl: String = "https://api.anthropic.com/v1/messages",
) {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val jsonMedia = "application/json".toMediaType()

    suspend fun send(
        messages: List<ClaudeMessage>,
        tools: List<ClaudeTool> = emptyList(),
        system: String? = null,
        toolChoice: ClaudeToolChoice? = null,
        maxTokens: Int = 1536,
    ): ClaudeResponse = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            throw ClaudeException("Add your Anthropic API key in Settings first.")
        }

        val body = json.encodeToString(
            ClaudeRequest.serializer(),
            ClaudeRequest(
                model = model,
                maxTokens = maxTokens,
                system = system,
                messages = messages,
                tools = tools,
                toolChoice = toolChoice,
            ),
        )
        val request = Request.Builder()
            .url(baseUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toRequestBody(jsonMedia))
            .build()

        val client = http.newBuilder()
            .callTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ClaudeException(errorMessage(response.code, text))
                }
                runCatching { json.decodeFromString(ClaudeResponse.serializer(), text) }
                    .getOrElse { throw ClaudeException("Claude sent something unexpected.", it) }
            }
        } catch (e: ClaudeException) {
            throw e
        } catch (e: IOException) {
            throw ClaudeException(e.message ?: "Couldn't reach Claude.", e)
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val fromBody = runCatching {
            json.decodeFromString(ClaudeErrorEnvelope.serializer(), body).error.message
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return when (code) {
            401 -> "That API key was rejected. Check it in Settings."
            429 -> "Claude is rate-limiting this key — try again shortly."
            else -> fromBody ?: "Claude returned HTTP $code."
        }
    }
}
