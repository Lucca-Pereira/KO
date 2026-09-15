package com.lucca.ko.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * One-shot data-repair flags, read and written by `data/repair/StartupRepairs.kt`.
 *
 * This used to also hold the NAS brain's URL and suggestion count, and later an Anthropic API
 * key — both gone now. The app doesn't call any AI itself; recipes and pantry updates come in as
 * a file via `data/repo/AgentImportRepository.kt` instead.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val normalizationRepaired = booleanPreferencesKey("normalization_repaired_v2")
        val measuresParsed = booleanPreferencesKey("measures_parsed_v1")
        val searchBlobsBackfilled = booleanPreferencesKey("search_blobs_backfilled_v1")
    }

    private suspend fun flag(key: Preferences.Key<Boolean>): Boolean =
        context.dataStore.data.first()[key] ?: false

    private suspend fun setFlag(key: Preferences.Key<Boolean>) {
        context.dataStore.edit { it[key] = true }
    }

    /** Existing rows have had their normalizedName recomputed (v0.1.6). */
    suspend fun isNormalizationRepaired(): Boolean = flag(Keys.normalizationRepaired)

    suspend fun markNormalizationRepaired() = setFlag(Keys.normalizationRepaired)

    /** Ingredient `measure` text has been parsed into quantity + unit (v0.3.0). */
    suspend fun isMeasuresParsed(): Boolean = flag(Keys.measuresParsed)

    suspend fun markMeasuresParsed() = setFlag(Keys.measuresParsed)

    /** Recipes predating library search have had their searchBlob built (v0.3.0). */
    suspend fun isSearchBlobsBackfilled(): Boolean = flag(Keys.searchBlobsBackfilled)

    suspend fun markSearchBlobsBackfilled() = setFlag(Keys.searchBlobsBackfilled)
}
