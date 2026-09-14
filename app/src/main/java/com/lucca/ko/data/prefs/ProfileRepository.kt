package com.lucca.ko.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lucca.ko.domain.nutrition.ActivityLevel
import com.lucca.ko.domain.nutrition.Goal
import com.lucca.ko.domain.nutrition.Sex
import com.lucca.ko.domain.nutrition.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.profileStore: DataStore<Preferences> by preferencesDataStore(name = "profile")

/**
 * Your body and your goal.
 *
 * Its own DataStore file rather than a Room table because it is genuinely single-valued — there
 * is one of you — and because that keeps it out of the way of the tables that hold history.
 * Weight is the exception: it lives in `body_metrics` as a dated series, and the value here is
 * just the latest one, kept in step so the calorie maths has something to work with before you
 * have logged a single weigh-in.
 */
class ProfileRepository(private val context: Context) {

    private object Keys {
        val sex = stringPreferencesKey("sex")
        val birthYear = intPreferencesKey("birth_year")
        val heightCm = doublePreferencesKey("height_cm")
        val weightKg = doublePreferencesKey("weight_kg")
        val bodyFatPct = doublePreferencesKey("body_fat_pct")
        val activity = stringPreferencesKey("activity")
        val goal = stringPreferencesKey("goal")
        val useAdaptive = booleanPreferencesKey("use_adaptive_tdee")
        val manualKcal = intPreferencesKey("manual_kcal_target")
        val proteinPerKg = doublePreferencesKey("protein_per_kg")
    }

    val profile: Flow<UserProfile> = context.profileStore.data.map { p ->
        UserProfile(
            sex = p[Keys.sex]?.let { runCatching { Sex.valueOf(it) }.getOrNull() } ?: Sex.MALE,
            birthYear = p[Keys.birthYear] ?: 0,
            heightCm = p[Keys.heightCm] ?: 0.0,
            weightKg = p[Keys.weightKg] ?: 0.0,
            bodyFatPct = p[Keys.bodyFatPct]?.takeIf { it > 0 },
            activity = p[Keys.activity]?.let { runCatching { ActivityLevel.valueOf(it) }.getOrNull() }
                ?: ActivityLevel.MODERATE,
            goal = p[Keys.goal]?.let { runCatching { Goal.valueOf(it) }.getOrNull() } ?: Goal.MAINTAIN,
            useAdaptiveTdee = p[Keys.useAdaptive] ?: true,
            manualKcalTarget = p[Keys.manualKcal]?.takeIf { it > 0 },
            proteinPerKgOverride = p[Keys.proteinPerKg]?.takeIf { it > 0 },
        )
    }

    suspend fun current(): UserProfile = profile.first()

    suspend fun save(profile: UserProfile) {
        context.profileStore.edit { p ->
            p[Keys.sex] = profile.sex.name
            p[Keys.birthYear] = profile.birthYear
            p[Keys.heightCm] = profile.heightCm
            p[Keys.weightKg] = profile.weightKg
            profile.bodyFatPct?.let { p[Keys.bodyFatPct] = it } ?: p.remove(Keys.bodyFatPct)
            p[Keys.activity] = profile.activity.name
            p[Keys.goal] = profile.goal.name
            p[Keys.useAdaptive] = profile.useAdaptiveTdee
            profile.manualKcalTarget?.let { p[Keys.manualKcal] = it } ?: p.remove(Keys.manualKcal)
            profile.proteinPerKgOverride?.let { p[Keys.proteinPerKg] = it }
                ?: p.remove(Keys.proteinPerKg)
        }
    }

    /** Keeps the profile's weight in step with the latest weigh-in. */
    suspend fun setWeight(weightKg: Double, bodyFatPct: Double? = null) {
        context.profileStore.edit { p ->
            p[Keys.weightKg] = weightKg
            bodyFatPct?.let { p[Keys.bodyFatPct] = it }
        }
    }
}
