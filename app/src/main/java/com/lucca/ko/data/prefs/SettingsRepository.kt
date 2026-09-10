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

/** The home NAS Ollama over Tailscale (stable IP; the LAN IP is DHCP and can move).
 *  On the Android emulator use http://10.0.2.2:11434 to reach a host-local Ollama. */
const val DEFAULT_OLLAMA_URL = "http://100.67.219.26:11434"

/** Model installed on the NAS, chosen for Spanish->English food translation accuracy
 *  and stable JSON. `llama3.1:8b` is also there as a faster fallback. */
const val DEFAULT_OLLAMA_MODEL = "gemma2:9b"

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
