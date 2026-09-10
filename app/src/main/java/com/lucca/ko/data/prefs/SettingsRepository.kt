package com.lucca.ko.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Default points at the standard Android emulator's host-loopback alias for
 *  Ollama running on the developer machine (127.0.0.1:11434 on the host). */
const val DEFAULT_OLLAMA_URL = "http://10.0.2.2:11434"

@Serializable
data class AppSettings(
    val ollamaBaseUrl: String = DEFAULT_OLLAMA_URL,
    val ollamaModel: String = "qwen2.5-coder:14b",
    val suggestionCount: Int = 5,
) {
    val ollamaConfigured: Boolean get() = ollamaBaseUrl.isNotBlank()
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val baseUrl = stringPreferencesKey("ollama_base_url")
        val model = stringPreferencesKey("ollama_model")
        val count = intPreferencesKey("suggestion_count")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            ollamaBaseUrl = p[Keys.baseUrl] ?: DEFAULT_OLLAMA_URL,
            ollamaModel = p[Keys.model]?.takeIf { it.isNotBlank() } ?: "qwen2.5-coder:14b",
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
