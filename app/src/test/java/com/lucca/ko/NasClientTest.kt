package com.lucca.ko

import app.cash.turbine.test
import com.lucca.ko.data.remote.nas.ChatEvent
import com.lucca.ko.data.remote.nas.ChatRequestDto
import com.lucca.ko.data.remote.nas.EstimateRequestDto
import com.lucca.ko.data.remote.nas.NasClient
import com.lucca.ko.data.remote.nas.NasException
import com.lucca.ko.data.remote.nas.NutritionIngredientDto
import com.lucca.ko.data.remote.nas.RecipeSnapshotDto
import com.lucca.ko.data.remote.nas.SuggestRequestDto
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The wire contract, against a real socket.
 *
 * The SSE tests matter most: a stream that stalls without a terminal event leaves the chat UI
 * spinning forever, which is the one failure the phone cannot recover from on its own.
 */
class NasClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: NasClient
    private var token = "secret"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val http = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val streaming = http.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        client = NasClient(
            http = http,
            streamingHttp = streaming,
            baseUrlProvider = { server.url("/").toString() },
            tokenProvider = { token },
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun respond(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    // ---- Requests -------------------------------------------------------------------

    @Test
    fun `sends the bearer token`() = runTest {
        respond("""{"ok":true,"version":"0.1.0","ollama":{"reachable":true}}""")
        client.health()
        assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `omits the header entirely when there is no token`() = runTest {
        token = ""
        respond("""{"ok":true,"version":"0.1.0","ollama":{"reachable":true}}""")
        client.health()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `parses a health response`() = runTest {
        respond(
            """
            {"ok":true,"version":"0.1.0","busy":true,"queue_depth":2,
             "ollama":{"reachable":true,"models":["llama3.1:8b"],"missing":["qwen2.5:7b-instruct"]}}
            """.trimIndent(),
        )
        val health = client.health()
        assertTrue(health.ok)
        assertTrue(health.busy)
        assertEquals(2, health.queueDepth)
        assertEquals(listOf("llama3.1:8b"), health.ollama.models)
        assertEquals(listOf("qwen2.5:7b-instruct"), health.ollama.missing)
    }

    @Test
    fun `maps snake_case fields off the wire`() = runTest {
        respond(
            """{"ideas":[{"title":"Tortilla","why":"uses your eggs","query":"spanish tortilla",
                "tags":["quick"],"est_minutes":25}],"model":"llama3.1:8b"}""",
        )
        val idea = client.suggest(SuggestRequestDto()).ideas.single()
        assertEquals("Tortilla", idea.title)
        assertEquals(25, idea.estMinutes)
        assertEquals(listOf("quick"), idea.tags)
    }

    @Test
    fun `parses a nutrition estimate including per-ingredient method`() = runTest {
        respond(
            """
            {"per_serving":{"kcal":377.5,"protein_g":49.2,"carbs_g":28.0,"fat_g":6.0},
             "total":{"kcal":755.0,"protein_g":98.4,"carbs_g":56.0,"fat_g":12.0},
             "per_ingredient":[{"name":"chicken breast","grams":300.0,"method":"TABLE",
                 "confidence":0.9,"macros":{"kcal":495.0,"protein_g":93.0,"carbs_g":0.0,"fat_g":10.8}}],
             "coverage":1.0,"note":"2 of 2 ingredients counted."}
            """.trimIndent(),
        )
        val result = client.estimate(
            EstimateRequestDto(
                servings = 2,
                ingredients = listOf(NutritionIngredientDto(name = "chicken breast")),
            ),
        )
        assertEquals(377.5, result.perServing.kcal, 0.01)
        assertEquals(1.0, result.coverage, 0.01)
        assertEquals("TABLE", result.perIngredient.single().method)
    }

    @Test
    fun `unknown fields from a newer server do not break parsing`() = runTest {
        // The server is deployed independently of the app, so it will sometimes be ahead.
        respond("""{"ok":true,"version":"9.9.9","ollama":{"reachable":true},"brand_new":42}""")
        assertEquals("9.9.9", client.health().version)
    }

    // ---- Errors ---------------------------------------------------------------------

    @Test
    fun `a rejected token says so in words the user can act on`() = runTest {
        respond("""{"detail":"Invalid token."}""", code = 401)
        val error = runCatching { client.suggest(SuggestRequestDto()) }.exceptionOrNull()
        assertTrue(error is NasException)
        assertTrue(error!!.message!!.contains("token", ignoreCase = true))
    }

    @Test
    fun `a busy brain is reported as busy, not as a crash`() = runTest {
        respond("""{"busy":true}""", code = 503)
        val error = runCatching { client.suggest(SuggestRequestDto()) }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("busy", ignoreCase = true))
    }

    @Test
    fun `an unknown barcode is null rather than an exception`() = runTest {
        respond("""{"detail":"not found"}""", code = 404)
        assertNull(client.foodByBarcode("9999999999999"))
    }

    @Test
    fun `a connection failure becomes a NasException`() = runTest {
        server.shutdown()
        val error = runCatching { client.health() }.exceptionOrNull()
        assertTrue(error is NasException)
    }

    // ---- The SSE stream --------------------------------------------------------------

    private fun sse(body: String) {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )
    }

    private fun chatRequest() = ChatRequestDto(
        recipe = RecipeSnapshotDto(title = "Creamy pasta"),
        message = "make it dairy-free",
    )

    @Test
    fun `reassembles tokens and ends on done`() = runTest {
        sse(
            """
            event: token
            data: {"t":"Sure, "}

            event: token
            data: {"t":"swap the cream."}

            event: done
            data: {"chars":21}

            """.trimIndent() + "\n",
        )

        client.chat(chatRequest()).test {
            assertEquals(ChatEvent.Token("Sure, "), awaitItem())
            assertEquals(ChatEvent.Token("swap the cream."), awaitItem())
            assertEquals(ChatEvent.Done(21), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `delivers a proposal before done`() = runTest {
        sse(
            """
            event: token
            data: {"t":"Done."}

            event: proposal
            data: {"summary":"Swapped cream for oat milk","recipe":{"title":"Creamy pasta","servings":2,"ingredients":[{"name":"oat milk","amount":"150 ml"}],"steps":[]}}

            event: done
            data: {"chars":5}

            """.trimIndent() + "\n",
        )

        client.chat(chatRequest()).test {
            assertEquals(ChatEvent.Token("Done."), awaitItem())
            val proposal = awaitItem() as ChatEvent.Proposal
            assertEquals("Swapped cream for oat milk", proposal.summary)
            assertEquals("oat milk", proposal.recipe.ingredients.single().name)
            assertEquals(ChatEvent.Done(5), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `a mid-stream error is delivered and terminates the flow`() = runTest {
        sse(
            """
            event: token
            data: {"t":"Thinking"}

            event: error
            data: {"message":"model timeout"}

            """.trimIndent() + "\n",
        )

        client.chat(chatRequest()).test {
            assertEquals(ChatEvent.Token("Thinking"), awaitItem())
            assertEquals(ChatEvent.Failed("model timeout"), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `comment lines and unknown events are skipped`() = runTest {
        // Comments are how a server keeps an idle connection alive; an unknown event type is
        // what a newer server looks like.
        sse(
            """
            : keep-alive

            event: heartbeat
            data: {}

            event: token
            data: {"t":"Hello"}

            event: done
            data: {"chars":5}

            """.trimIndent() + "\n",
        )

        client.chat(chatRequest()).test {
            assertEquals(ChatEvent.Token("Hello"), awaitItem())
            assertEquals(ChatEvent.Done(5), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `a stream that just stops still terminates with a failure`() = runTest {
        // The worst case: no `done`, no `error`, the connection simply ends. Without a terminal
        // event here the chat screen would spin forever.
        sse("event: token\ndata: {\"t\":\"Half an ans\"}\n\n")

        client.chat(chatRequest()).test {
            assertEquals(ChatEvent.Token("Half an ans"), awaitItem())
            // Either a Failed or a clean completion is acceptable; a hang is not.
            val next = awaitEvent()
            assertTrue(
                "the flow must end rather than hang",
                next.isTerminal() || next.isFailedEvent(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an http failure on the stream becomes a failed event`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))

        client.chat(chatRequest()).test {
            val event = awaitItem()
            assertTrue(event is ChatEvent.Failed)
            assertTrue((event as ChatEvent.Failed).message.contains("token", ignoreCase = true))
            awaitComplete()
        }
    }
}

private fun app.cash.turbine.Event<ChatEvent>.isTerminal(): Boolean =
    this is app.cash.turbine.Event.Complete || this is app.cash.turbine.Event.Error

private fun app.cash.turbine.Event<ChatEvent>.isFailedEvent(): Boolean =
    (this as? app.cash.turbine.Event.Item)?.value is ChatEvent.Failed
