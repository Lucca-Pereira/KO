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

/**
 * The KO brain service on the home NAS, over Tailscale.
 *
 * A Tailscale address rather than the LAN IP because the LAN one is DHCP and moves. Port 8080 is
 * the brain, not Ollama's 11434 — the app no longer talks to Ollama directly, and pointing this
 * at 11434 will only produce confusing 404s.
 */
const val DEFAULT_NAS_URL = "http://100.67.219.26:8080"

@Serializable
data class AppSettings(
    val nasBaseUrl: String = DEFAULT_NAS_URL,
    val suggestionCount: Int = 5,
) {
    val nasConfigured: Boolean get() = nasBaseUrl.isNotBlank()
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val nasBaseUrl = stringPreferencesKey("nas_base_url")
        val count = intPreferencesKey("suggestion_count")
        val normalizationRepaired = booleanPreferencesKey("normalization_repaired_v2")
        val measuresParsed = booleanPreferencesKey("measures_parsed_v1")
        val searchBlobsBackfilled = booleanPreferencesKey("search_blobs_backfilled_v1")
        // Deliberately not read: `ollama_base_url` and `ollama_model` from before v0.5.0. The
        // old value pointed at Ollama's own port, which is not where the brain lives, so
        // migrating it forward would hand every existing user a broken configuration.
    }

    private suspend fun flag(key: Preferences.Key<Boolean>): Boolean =
        context.dataStore.data.first()[key] ?: false

    private suspend fun setFlag(key: Preferences.Key<Boolean>) {
        context.dataStore.edit { it[key] = true }
    }

    // One-shot repair flags, read and written by data/repair/StartupRepairs.kt.

    /** Existing rows have had their normalizedName recomputed (v0.1.6). */
    suspend fun isNormalizationRepaired(): Boolean = flag(Keys.normalizationRepaired)

    suspend fun markNormalizationRepaired() = setFlag(Keys.normalizationRepaired)

    /** Ingredient `measure` text has been parsed into quantity + unit (v0.3.0). */
    suspend fun isMeasuresParsed(): Boolean = flag(Keys.measuresParsed)

    suspend fun markMeasuresParsed() = setFlag(Keys.measuresParsed)

    /** Recipes predating library search have had their searchBlob built (v0.3.0). */
    suspend fun isSearchBlobsBackfilled(): Boolean = flag(Keys.searchBlobsBackfilled)

    suspend fun markSearchBlobsBackfilled() = setFlag(Keys.searchBlobsBackfilled)

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            nasBaseUrl = p[Keys.nasBaseUrl]?.takeIf { it.isNotBlank() } ?: DEFAULT_NAS_URL,
            suggestionCount = (p[Keys.count] ?: 5).coerceIn(1, 10),
        )
    }

    suspend fun currentSettings(): AppSettings = settings.first()

    suspend fun update(baseUrl: String? = null, count: Int? = null) {
        context.dataStore.edit { p ->
            baseUrl?.let { p[Keys.nasBaseUrl] = it.trim() }
            count?.let { p[Keys.count] = it.coerceIn(1, 10) }
        }
    }
}
