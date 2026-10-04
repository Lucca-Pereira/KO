package com.lucca.ko.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.planViewDataStore: DataStore<Preferences> by preferencesDataStore(name = "plan_view")

/**
 * How the meal-plan screen is laid out.
 *
 * @param weeks 1 for a single week, 2 for a two-week (biweekly) plan.
 * @param calendar true lets the user page to other weeks; false pins the screen to the current
 *   week(s) with no navigation.
 * @param updatedAt epoch millis of the last change, 0 if never changed. Synced with the NAS
 *   last-write-wins, so Claude can change the layout from a chat.
 */
data class PlanView(val weeks: Int = 1, val calendar: Boolean = true, val updatedAt: Long = 0)

class PlanViewRepository(private val context: Context) {

    private object Keys {
        val weeks = intPreferencesKey("weeks")
        val calendar = booleanPreferencesKey("calendar")
        val updatedAt = longPreferencesKey("updated_at")
    }

    val view: Flow<PlanView> = context.planViewDataStore.data.map {
        PlanView(
            weeks = (it[Keys.weeks] ?: 1).coerceIn(1, 2),
            calendar = it[Keys.calendar] ?: true,
            updatedAt = it[Keys.updatedAt] ?: 0L,
        )
    }

    suspend fun current(): PlanView = view.first()

    /** A change made on the phone: stamped now, so it wins the next sync. */
    suspend fun set(weeks: Int? = null, calendar: Boolean? = null) {
        val now = current()
        write(PlanView(weeks ?: now.weeks, calendar ?: now.calendar, System.currentTimeMillis()))
    }

    /** A change pulled from the NAS; ignored unless it is newer than what is stored here. */
    suspend fun applyFromSync(incoming: PlanView) {
        if (incoming.updatedAt > current().updatedAt) write(incoming)
    }

    private suspend fun write(v: PlanView) {
        context.planViewDataStore.edit {
            it[Keys.weeks] = v.weeks.coerceIn(1, 2)
            it[Keys.calendar] = v.calendar
            it[Keys.updatedAt] = v.updatedAt
        }
    }
}
