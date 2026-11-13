package com.lucca.ko

import com.lucca.ko.data.remote.RecipePageClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RecipePageClientTest {
    private lateinit var server: MockWebServer
    private val client = RecipePageClient(OkHttpClient())

    @Before fun setUp() { server = MockWebServer().also { it.start() } }

    @After fun tearDown() { server.shutdown() }

    @Test
    fun `fetches a page and parses its recipe`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "<script type=\"application/ld+json\">{\"@type\":\"Recipe\",\"name\":\"Dal\"," +
                    "\"recipeIngredient\":[\"1 cup lentils\"]}</script>",
            ),
        )
        val url = server.url("/dal").toString()
        val draft = client.fetch(url)
        assertEquals("Dal", draft.title)
        assertEquals(url, draft.sourceUrl)
        assertEquals("lentils", draft.ingredients.single().name)
        assertTrue(server.takeRequest().getHeader("User-Agent")!!.isNotBlank())
    }

    @Test
    fun `http error and missing recipe become import errors`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("<html>no data</html>"))
        for (expected in listOf("404", "No recipe data")) {
            try {
                client.fetch(server.url("/x").toString())
                fail("expected ImportException")
            } catch (e: RecipePageClient.ImportException) {
                assertTrue(e.message!!.contains(expected))
            }
        }
    }
}
