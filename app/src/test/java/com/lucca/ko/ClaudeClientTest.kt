package com.lucca.ko

import com.lucca.ko.data.remote.claude.ClaudeBlock
import com.lucca.ko.data.remote.claude.ClaudeClient
import com.lucca.ko.data.remote.claude.ClaudeException
import com.lucca.ko.data.remote.claude.firstToolUse
import com.lucca.ko.data.remote.claude.text
import com.lucca.ko.data.remote.claude.textMessage
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
 * The wire contract with the Anthropic Messages API, against a real socket.
 *
 * What matters most here is the tool-use shape: the agent's whole safety story (nothing writes
 * without a review) depends on a `tool_use` block being told apart from plain text reliably.
 */
class ClaudeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ClaudeClient
    private var apiKey = "sk-ant-test"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val http = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        client = ClaudeClient(
            http = http,
            apiKeyProvider = { apiKey },
            model = "claude-test",
            baseUrl = server.url("/v1/messages").toString(),
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

    @Test
    fun `sends the api key and version headers`() = runTest {
        respond("""{"role":"assistant","content":[{"type":"text","text":"hi"}]}""")
        client.send(messages = listOf(textMessage("user", "hi")))
        val recorded = server.takeRequest()
        assertEquals("sk-ant-test", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
    }

    @Test
    fun `parses plain text`() = runTest {
        respond("""{"role":"assistant","content":[{"type":"text","text":"Try a stir-fry."}]}""")
        val response = client.send(messages = listOf(textMessage("user", "idea?")))
        assertEquals("Try a stir-fry.", response.text())
        assertNull(response.firstToolUse())
    }

    @Test
    fun `parses a tool_use block`() = runTest {
        respond(
            """{"role":"assistant","content":[
                {"type":"text","text":"Sure, I'll save that."},
                {"type":"tool_use","id":"call_1","name":"save_recipe","input":{"title":"Stew"}}
            ]}""",
        )
        val response = client.send(messages = listOf(textMessage("user", "save it")))
        val toolUse = response.firstToolUse()
        assertTrue(toolUse is ClaudeBlock.ToolUse)
        assertEquals("save_recipe", toolUse!!.name)
        assertEquals("call_1", toolUse.id)
    }

    @Test
    fun `a rejected key says so in words the user can act on`() = runTest {
        respond("""{"error":{"type":"authentication_error","message":"invalid x-api-key"}}""", code = 401)
        val error = runCatching { client.send(messages = listOf(textMessage("user", "hi"))) }.exceptionOrNull()
        assertTrue(error is ClaudeException)
        assertTrue(error!!.message!!.contains("key", ignoreCase = true))
    }

    @Test
    fun `a server error surfaces the body's own message`() = runTest {
        respond("""{"error":{"type":"overloaded_error","message":"Servers are overloaded."}}""", code = 529)
        val error = runCatching { client.send(messages = listOf(textMessage("user", "hi"))) }.exceptionOrNull()
        assertEquals("Servers are overloaded.", error?.message)
    }

    @Test
    fun `unknown fields from a newer api do not break parsing`() = runTest {
        respond("""{"role":"assistant","content":[{"type":"text","text":"ok"}],"brand_new_field":42}""")
        assertEquals("ok", client.send(messages = listOf(textMessage("user", "hi"))).text())
    }

    @Test
    fun `a connection failure becomes a ClaudeException`() = runTest {
        server.shutdown()
        val error = runCatching { client.send(messages = listOf(textMessage("user", "hi"))) }.exceptionOrNull()
        assertTrue(error is ClaudeException)
    }
}
