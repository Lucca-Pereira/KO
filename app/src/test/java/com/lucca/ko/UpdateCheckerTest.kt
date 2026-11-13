package com.lucca.ko

import com.lucca.ko.data.remote.UpdateChecker
import com.lucca.ko.data.remote.UpdateResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateCheckerTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() = server.shutdown()

    private fun checker() = UpdateChecker(OkHttpClient(), server.url("/").toString())

    private fun release(tag: String) = """
        {"tag_name":"$tag","html_url":"https://github.com/x/y/releases/tag/$tag","assets":[
          {"browser_download_url":"https://example.com/notes.txt"},
          {"browser_download_url":"https://example.com/ko-$tag.apk"}]}
    """.trimIndent()

    @Test fun newerReleaseIsAvailableWithApkUrl() = runBlocking {
        server.enqueue(MockResponse().setBody(release("v0.15.0")))
        val r = checker().check("0.14.0")
        assertEquals(UpdateResult.Available("0.15.0", "https://example.com/ko-v0.15.0.apk"), r)
        assertEquals("/repos/Lucca-Pereira/KO/releases/latest", server.takeRequest().path)
    }

    @Test fun sameVersionIsUpToDate() = runBlocking {
        server.enqueue(MockResponse().setBody(release("v0.14.0")))
        assertEquals(UpdateResult.UpToDate, checker().check("0.14.0"))
    }

    @Test fun notFoundMeansNoReleases() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(UpdateResult.NoReleases, checker().check("0.14.0"))
    }

    @Test fun serverErrorFails() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(checker().check("0.14.0") is UpdateResult.Failed)
    }

    @Test fun badJsonFails() = runBlocking {
        server.enqueue(MockResponse().setBody("not json"))
        assertTrue(checker().check("0.14.0") is UpdateResult.Failed)
    }

    @Test fun noNetworkFails() = runBlocking {
        val dead = UpdateChecker(OkHttpClient(), "http://127.0.0.1:1")
        assertTrue(dead.check("0.14.0") is UpdateResult.Failed)
    }
}
