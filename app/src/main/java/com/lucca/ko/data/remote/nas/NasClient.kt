package com.lucca.ko.data.remote.nas

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/** Something went wrong talking to the brain, with a message fit to show a user. */
class NasException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Talks to the KO brain service on the NAS.
 *
 * Hand-rolled on OkHttp rather than Retrofit: this is about a dozen endpoints, the helpers below
 * are eighty lines, and Retrofit would not help with the one genuinely awkward part — the
 * server-sent-event stream for recipe chat.
 */
class NasClient(
    private val http: OkHttpClient,
    private val streamingHttp: OkHttpClient,
    private val baseUrlProvider: suspend () -> String,
    private val tokenProvider: suspend () -> String,
) {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        // The server's Pydantic models fill in their own defaults; sending explicit nulls for
        // every optional field just makes the request bigger and the logs harder to read.
        explicitNulls = false
    }
    private val jsonMedia = "application/json".toMediaType()

    // ---- Health --------------------------------------------------------------------

    /** Unauthenticated on the server, and given a short timeout: this is the banner's poll. */
    suspend fun health(baseUrlOverride: String? = null): HealthDto =
        get("health", HealthDto.serializer(), timeoutSeconds = 6, baseUrlOverride = baseUrlOverride)

    // ---- Suggestions ---------------------------------------------------------------

    suspend fun suggest(request: SuggestRequestDto): SuggestResponseDto =
        post(
            "v1/suggest",
            SuggestRequestDto.serializer(),
            request,
            SuggestResponseDto.serializer(),
            timeoutSeconds = 180,
        )

    suspend fun translate(request: TranslateRequestDto): TranslateResponseDto =
        post(
            "v1/translate",
            TranslateRequestDto.serializer(),
            request,
            TranslateResponseDto.serializer(),
            timeoutSeconds = 240,
        )

    // ---- Recipes -------------------------------------------------------------------

    suspend fun generateRecipe(request: GenerateRecipeRequestDto): RecipeDtoNas =
        post(
            "v1/recipes/generate",
            GenerateRecipeRequestDto.serializer(),
            request,
            RecipeDtoNas.serializer(),
            timeoutSeconds = 300,
        )

    suspend fun searchRecipes(query: String): MealSearchResponseDto =
        get(
            "v1/recipes/search?q=${query.urlEncoded()}",
            MealSearchResponseDto.serializer(),
            timeoutSeconds = 30,
        )

    // ---- Nutrition -----------------------------------------------------------------

    suspend fun estimate(request: EstimateRequestDto): EstimateResponseDto =
        post(
            "v1/nutrition/estimate",
            EstimateRequestDto.serializer(),
            request,
            EstimateResponseDto.serializer(),
            timeoutSeconds = 240,
        )

    // ---- Foods ---------------------------------------------------------------------

    suspend fun foodByBarcode(barcode: String): FoodDtoNas? =
        getOrNull(
            "v1/foods/barcode/${barcode.urlEncoded()}",
            FoodDtoNas.serializer(),
            timeoutSeconds = 20,
        )

    suspend fun searchFoods(query: String): FoodSearchResponseDto =
        get(
            "v1/foods/search?q=${query.urlEncoded()}",
            FoodSearchResponseDto.serializer(),
            timeoutSeconds = 20,
        )

    suspend fun foodSeed(since: Long = 0): FoodSeedResponseDto =
        get("v1/foods/seed?since=$since", FoodSeedResponseDto.serializer(), timeoutSeconds = 60)

    // ---- Chat ----------------------------------------------------------------------

    /**
     * Streams a recipe chat turn.
     *
     * Uses [streamingHttp], which has no call or read timeout. The shared client's 90-second
     * call timeout would sever a healthy stream mid-answer, and its 60-second read timeout would
     * kill it during any pause the model takes while thinking.
     */
    fun chat(request: ChatRequestDto): Flow<ChatEvent> = callbackFlow {
        val body = json.encodeToString(ChatRequestDto.serializer(), request)
        val httpRequest = Request.Builder()
            .url(baseUrlProvider().normalizeBase() + "v1/recipes/chat")
            .header("Accept", "text/event-stream")
            .apply { tokenProvider().takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .post(body.toRequestBody(jsonMedia))
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val event = runCatching {
                    when (type) {
                        "token" -> ChatEvent.Token(json.decodeFromString(ChatTokenDto.serializer(), data).t)
                        "proposal" -> json.decodeFromString(ChatProposalDto.serializer(), data)
                            .let { ChatEvent.Proposal(it.summary, it.recipe) }
                        "done" -> ChatEvent.Done(json.decodeFromString(ChatDoneDto.serializer(), data).chars)
                        "error" -> ChatEvent.Failed(
                            json.decodeFromString(ChatErrorDto.serializer(), data).message,
                        )
                        else -> null
                    }
                }.getOrNull() ?: return
                trySend(event)
                if (event is ChatEvent.Done || event is ChatEvent.Failed) close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // A stream that just stops leaves the UI spinning forever, so a failure is
                // turned into a terminal event rather than a silent close.
                val message = when {
                    response != null && response.code == 401 -> "The brain rejected the token. Check Settings."
                    t != null -> t.message ?: "The connection dropped."
                    response != null -> "The brain returned HTTP ${response.code}."
                    else -> "The connection dropped."
                }
                trySend(ChatEvent.Failed(message))
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val source = EventSources.createFactory(streamingHttp).newEventSource(httpRequest, listener)
        awaitClose { source.cancel() }
    }

    // ---- Plumbing ------------------------------------------------------------------

    private suspend fun <T> get(
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        timeoutSeconds: Long,
        baseUrlOverride: String? = null,
    ): T = getOrNull(path, deserializer, timeoutSeconds, baseUrlOverride)
        ?: throw NasException("The brain had nothing for that request.")

    private suspend fun <T> getOrNull(
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        timeoutSeconds: Long,
        baseUrlOverride: String? = null,
    ): T? = withContext(Dispatchers.IO) {
        val base = baseUrlOverride ?: baseUrlProvider()
        val request = Request.Builder()
            .url(base.normalizeBase() + path)
            .authorized()
            .get()
            .build()
        execute(request, timeoutSeconds) { text -> json.decodeFromString(deserializer, text) }
    }

    private suspend fun <Req, Res> post(
        path: String,
        requestSerializer: kotlinx.serialization.SerializationStrategy<Req>,
        request: Req,
        responseSerializer: kotlinx.serialization.DeserializationStrategy<Res>,
        timeoutSeconds: Long,
    ): Res = withContext(Dispatchers.IO) {
        val body = json.encodeToString(requestSerializer, request)
        val httpRequest = Request.Builder()
            .url(baseUrlProvider().normalizeBase() + path)
            .authorized()
            .post(body.toRequestBody(jsonMedia))
            .build()
        execute(httpRequest, timeoutSeconds) { text -> json.decodeFromString(responseSerializer, text) }
            ?: throw NasException("The brain returned nothing.")
    }

    private suspend fun Request.Builder.authorized(): Request.Builder = apply {
        tokenProvider().takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
    }

    /**
     * Runs a call with a per-request timeout and turns every failure into a [NasException]
     * carrying something worth showing a person.
     *
     * Per-endpoint timeouts matter here: a 7B model on NAS CPU takes minutes for a recipe, while
     * a health check that has not answered in six seconds is down. One shared timeout cannot be
     * right for both.
     */
    private fun <T> execute(
        request: Request,
        timeoutSeconds: Long,
        parse: (String) -> T,
    ): T? {
        val client = http.newBuilder()
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return null
                if (response.code == 401) {
                    throw NasException("The brain rejected the token. Check it in Settings.")
                }
                if (response.code == 503) {
                    throw NasException("The brain is busy with something else. Try again shortly.")
                }
                if (!response.isSuccessful) {
                    throw NasException("The brain returned HTTP ${response.code}.")
                }
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) return null
                return parse(text)
            }
        } catch (e: NasException) {
            throw e
        } catch (e: IOException) {
            throw NasException(e.message ?: "Couldn't reach the brain.", e)
        } catch (e: Exception) {
            throw NasException("The brain sent something unexpected: ${e.message}", e)
        }
    }
}

private fun String.urlEncoded(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

internal fun String.normalizeBase(): String {
    var s = trim()
    if (s.isEmpty()) return s
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
    if (!s.endsWith("/")) s = "$s/"
    return s
}
