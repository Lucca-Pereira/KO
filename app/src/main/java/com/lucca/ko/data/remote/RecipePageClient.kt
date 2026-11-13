package com.lucca.ko.data.remote

import com.lucca.ko.domain.recipe.RecipeDraft
import com.lucca.ko.domain.recipe.RecipeJsonLd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Fetches a recipe web page and reads its schema.org JSON-LD. No AI, no third-party service. */
class RecipePageClient(private val http: OkHttpClient) {

    /** Messages are user-readable; the import dialog shows them as is. */
    class ImportException(message: String) : Exception(message)

    suspend fun fetch(url: String): RecipeDraft = withContext(Dispatchers.IO) {
        val clean = url.trim().let { if (it.contains("://")) it else "https://$it" }
        val httpUrl = clean.toHttpUrlOrNull() ?: throw ImportException("That does not look like a web address.")
        val request = Request.Builder().url(httpUrl)
            .header("User-Agent", "Mozilla/5.0 (Android) KO-Kitchen")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        val html = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ImportException("The site answered ${response.code}.")
                response.body?.string().orEmpty()
            }
        } catch (e: java.io.IOException) {
            throw ImportException("Could not reach the site.")
        }
        RecipeJsonLd.parse(html, httpUrl.toString())
            ?: throw ImportException("No recipe data found on that page.")
    }
}
