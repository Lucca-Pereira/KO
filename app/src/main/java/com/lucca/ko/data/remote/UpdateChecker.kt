package com.lucca.ko.data.remote

import com.lucca.ko.domain.VersionCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface UpdateResult {
    data object UpToDate : UpdateResult

    data object NoReleases : UpdateResult

    data class Available(val version: String, val downloadUrl: String) : UpdateResult

    data class Failed(val message: String) : UpdateResult
}

/** Asks GitHub for the latest release. Only ever called from the About button — never automatic. */
class UpdateChecker(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://api.github.com",
) {
    suspend fun check(installedVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/repos/Lucca-Pereira/KO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.code == 404) return@use UpdateResult.NoReleases
                if (!response.isSuccessful) return@use UpdateResult.Failed("GitHub answered ${response.code}.")
                val body = response.body?.string().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@use UpdateResult.Failed("Couldn't read GitHub's answer.")
                val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull
                    ?: return@use UpdateResult.Failed("Couldn't read GitHub's answer.")
                if (!VersionCompare.isNewer(tag, installedVersion)) return@use UpdateResult.UpToDate
                val apk = (obj["assets"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.get("browser_download_url")?.jsonPrimitive?.contentOrNull }
                    ?.firstOrNull { it.endsWith(".apk", ignoreCase = true) }
                    ?: (obj["html_url"]?.jsonPrimitive?.contentOrNull)
                    ?: return@use UpdateResult.Failed("Release $tag has no download.")
                UpdateResult.Available(tag.removePrefix("v"), apk)
            }
        } catch (e: java.io.IOException) {
            UpdateResult.Failed("Couldn't reach GitHub. Check your connection.")
        }
    }
}
