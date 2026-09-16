package com.lucca.ko.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_settings")

/**
 * NAS base URL, bearer token and sync watermark — kept out of `SettingsRepository`'s "settings"
 * DataStore file (rather than "sync_settings") on purpose, same reasoning that used to apply to
 * the Anthropic API key: a bearer token is a secret, and `AndroidManifest.xml`'s backup rules
 * exclude this file by name so a cloud/device-transfer backup never carries it off the phone.
 */
class SyncSettingsRepository(private val context: Context) {

    private object Keys {
        val nasUrl = stringPreferencesKey("nas_url")
        val token = stringPreferencesKey("token")
        val lastSyncedAt = longPreferencesKey("last_synced_at")
    }

    val nasUrl: Flow<String?> = context.syncDataStore.data.map { it[Keys.nasUrl] }
    val token: Flow<String?> = context.syncDataStore.data.map { it[Keys.token] }
    val lastSyncedAt: Flow<Long> = context.syncDataStore.data.map { it[Keys.lastSyncedAt] ?: 0L }

    suspend fun currentNasUrl(): String? = nasUrl.first()
    suspend fun currentToken(): String? = token.first()
    suspend fun currentLastSyncedAt(): Long = lastSyncedAt.first()

    suspend fun setNasUrl(url: String?) {
        context.syncDataStore.edit {
            val clean = url?.trim()?.ifEmpty { null }
            if (clean == null) it.remove(Keys.nasUrl) else it[Keys.nasUrl] = clean
        }
    }

    suspend fun setToken(token: String?) {
        context.syncDataStore.edit {
            val clean = token?.trim()?.ifEmpty { null }
            if (clean == null) it.remove(Keys.token) else it[Keys.token] = clean
        }
    }

    suspend fun setLastSyncedAt(millis: Long) {
        context.syncDataStore.edit { it[Keys.lastSyncedAt] = millis }
    }

    /** Both a NAS URL and a token are needed before any sync attempt makes sense. */
    suspend fun isConfigured(): Boolean = !currentNasUrl().isNullOrBlank() && !currentToken().isNullOrBlank()
}
