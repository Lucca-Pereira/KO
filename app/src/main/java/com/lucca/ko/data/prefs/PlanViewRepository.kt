package com.lucca.ko.data.prefs

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.planViewDataStore: DataStore<Preferences> by preferencesDataStore(name = "plan_view")

/**
 * How the meal-plan screen is laid out.
 *
 * @param weeks 1 for a single week, 2 for a two-week (biweekly) plan.
 * @param calendar unused since the plan became a fixed week; kept because the sync wire and
 *   older servers still carry it.
 * @param updatedAt epoch millis of the last change, 0 if never changed. Synced with the NAS
 *   last-write-wins, so Claude can change the layout from a chat.
 * @param activeWeek which week of a two-week plan is "this week" — the one the plan screen
 *   opens on and the Today screen reads. Switched by hand; always 1 for a one-week plan.
 */
data class PlanView(
    val weeks: Int = 1,
    val calendar: Boolean = false,
    val updatedAt: Long = 0,
    val activeWeek: Int = 1,
)

class PlanViewRepository(private val context: Context) {

    private object Keys {
        val weeks = intPreferencesKey("weeks")
        val calendar = booleanPreferencesKey("calendar")
        val updatedAt = longPreferencesKey("updated_at")
        val activeWeek = intPreferencesKey("active_week")
        val ticksResetFor = stringPreferencesKey("ticks_reset_for")
    }

    val view: Flow<PlanView> = context.planViewDataStore.data.map {
        PlanView(
            weeks = (it[Keys.weeks] ?: 1).coerceIn(1, 2),
            calendar = it[Keys.calendar] ?: true,
            updatedAt = it[Keys.updatedAt] ?: 0L,
            activeWeek = activeWeekOf(it[Keys.weeks] ?: 1, it[Keys.activeWeek] ?: 1),
        )
    }

    suspend fun current(): PlanView = view.first()

    /** A change made on the phone: stamped now, so it wins the next sync. */
    suspend fun set(weeks: Int? = null, activeWeek: Int? = null) {
        val now = current()
        write(
            PlanView(
                weeks = weeks ?: now.weeks,
                calendar = false,
                updatedAt = System.currentTimeMillis(),
                activeWeek = activeWeek ?: now.activeWeek,
            ),
        )
    }

    /**
     * The Monday (ISO date) whose weekly tick reset has already run, or null if none has. Local
     * to this phone: ticks are not synced, so neither is when they were last cleared.
     */
    suspend fun ticksResetFor(): String? = context.planViewDataStore.data.first()[Keys.ticksResetFor]

    suspend fun markTicksResetFor(monday: String) {
        context.planViewDataStore.edit { it[Keys.ticksResetFor] = monday }
    }

    /** A change pulled from the NAS; ignored unless it is newer than what is stored here. */
    suspend fun applyFromSync(incoming: PlanView) {
        if (incoming.updatedAt > current().updatedAt) write(incoming)
    }

    /**
     * Back to defaults. The DataStore file outlives a single Robolectric test, so tests call this
     * in set-up; a value stamped "now" by one test would otherwise beat every later test's sync.
     */
    @VisibleForTesting
    suspend fun clear() {
        context.planViewDataStore.edit { it.clear() }
    }

    private suspend fun write(v: PlanView) {
        context.planViewDataStore.edit {
            it[Keys.weeks] = v.weeks.coerceIn(1, 2)
            it[Keys.calendar] = v.calendar
            it[Keys.updatedAt] = v.updatedAt
            it[Keys.activeWeek] = activeWeekOf(v.weeks, v.activeWeek)
        }
    }

    private fun activeWeekOf(weeks: Int, activeWeek: Int): Int =
        if (weeks.coerceIn(1, 2) == 1) 1 else activeWeek.coerceIn(1, 2)
}
