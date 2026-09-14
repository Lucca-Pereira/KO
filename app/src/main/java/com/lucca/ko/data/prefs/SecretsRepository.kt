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
 * The NAS bearer token, kept in its own DataStore file.
 *
 * Separate from [SettingsRepository] so it can be excluded wholesale from both Android's cloud
 * backup and KO's own JSON export. `datastore/` used to be included in the backup rules in one
 * lump, which would have sent the token to Google Drive; and a backup file is something you
 * might email yourself. Restoring onto a new phone asks for the token again, which is correct.
 */
class SecretsRepository(private val context: Context) {

    private object Keys {
        val nasToken = stringPreferencesKey("nas_token")
    }

    val token: Flow<String> = context.secretsStore.data.map { it[Keys.nasToken].orEmpty() }

    suspend fun currentToken(): String = token.first()

    suspend fun setToken(value: String) {
        context.secretsStore.edit { it[Keys.nasToken] = value.trim() }
    }
}
