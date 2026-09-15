package com.lucca.ko.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.secretsStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")

/**
 * The user's own Anthropic API key, kept in its own DataStore file.
 *
 * Separate from [SettingsRepository] so it can be excluded wholesale from both Android's cloud
 * backup and KO's own JSON export — a backup file is something you might email yourself, and an
 * API key has no business riding along in it. Restoring onto a new phone asks for the key again,
 * which is correct.
 */
class SecretsRepository(private val context: Context) {

    private object Keys {
        val anthropicApiKey = stringPreferencesKey("anthropic_api_key")
    }

    val apiKey: Flow<String> = context.secretsStore.data.map { it[Keys.anthropicApiKey].orEmpty() }

    suspend fun currentApiKey(): String = apiKey.first()

    suspend fun setApiKey(value: String) {
        context.secretsStore.edit { it[Keys.anthropicApiKey] = value.trim() }
    }
}
