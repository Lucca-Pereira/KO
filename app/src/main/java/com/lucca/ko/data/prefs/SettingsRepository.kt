package com.lucca.ko.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Default points at the standard Android emulator's host-loopback alias for
 *  Ollama running on the developer machine (127.0.0.1:11434 on the host). */
const val DEFAULT_OLLAMA_URL = "http://10.0.2.2:11434"

/** Tiny model that runs on a CPU-only box (e.g. the NAS at 192.168.68.65). Point the
 *  app at a GPU host and pick a bigger model in Settings for better suggestions. */
const val DEFAULT_OLLAMA_MODEL = "qwen2.5:0.5b"

@Serializable
data class AppSettings(
    val ollamaBaseUrl: String = DEFAULT_OLLAMA_URL,
    val ollamaModel: String = DEFAULT_OLLAMA_MODEL,
    val suggestionCount: Int = 5,
) {
    val ollamaConfigured: Boolean get() = ollamaBaseUrl.isNotBlank()
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val baseUrl = stringPreferencesKey("ollama_base_url")
        val model = stringPreferencesKey("ollama_model")
        val count = intPreferencesKey("suggestion_count")
        val normalizationRepaired = booleanPreferencesKey("normalization_repaired_v2")
    }

    /** One-time flag: existing rows have had their normalizedName recomputed (v0.1.6). */
    suspend fun isNormalizationRepaired(): Boolean =
        context.dataStore.data.first()[Keys.normalizationRepaired] ?: false

    suspend fun markNormalizationRepaired() {
        context.dataStore.edit { it[Keys.normalizationRepaired] = true }
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            ollamaBaseUrl = p[Keys.baseUrl] ?: DEFAULT_OLLAMA_URL,
            ollamaModel = p[Keys.model]?.takeIf { it.isNotBlank() } ?: DEFAULT_OLLAMA_MODEL,
            suggestionCount = (p[Keys.count] ?: 5).coerceIn(1, 10),
        )
    }

    suspend fun update(
        baseUrl: String? = null,
        model: String? = null,
        count: Int? = null,
    ) {
        context.dataStore.edit { p ->
            baseUrl?.let { p[Keys.baseUrl] = it.trim() }
            model?.let { p[Keys.model] = it.trim() }
            count?.let { p[Keys.count] = it.coerceIn(1, 10) }
        }
    }
}
