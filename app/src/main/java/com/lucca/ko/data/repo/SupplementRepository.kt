package com.lucca.ko.data.repo

import com.lucca.ko.data.db.LogSlot
import com.lucca.ko.data.db.LogSource
import com.lucca.ko.data.db.NutritionEntry
import com.lucca.ko.data.db.Supplement
import com.lucca.ko.data.db.SupplementKind
import com.lucca.ko.data.db.SupplementLog
import com.lucca.ko.data.db.dao.NutritionDao
import com.lucca.ko.data.db.dao.SupplementDao
import com.lucca.ko.domain.nutrition.StreakCalculator
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A supplement with today's state and how consistently it has been taken. */
data class SupplementStatus(
    val supplement: Supplement,
    val takenToday: Boolean,
    val streakDays: Int,
    val adherence30d: Double,
)

class SupplementRepository(
    private val supplementDao: SupplementDao,
    private val nutritionDao: NutritionDao,
) {
    fun observeAll(): Flow<List<Supplement>> = supplementDao.observeAll()

    fun observeActive(): Flow<List<Supplement>> = supplementDao.observeActive()

    /** Everything needed to draw the supplement list for a day. */
    suspend fun statusFor(date: LocalDate): List<SupplementStatus> =
        supplementDao.observeActive().first().map { supplement ->
            val history = supplementDao.observeHistory(supplement.id).first()
                .map { LocalDate.parse(it.date) }
                .toSet()
            SupplementStatus(
                supplement = supplement,
                takenToday = date in history,
                streakDays = StreakCalculator.currentStreak(history, date),
                adherence30d = StreakCalculator.adherence(history, date),
            )
        }

    /** Recomputed whenever the day's log changes, so ticking a box updates the streak at once. */
    fun observeStatus(date: LocalDate): Flow<List<SupplementStatus>> =
        supplementDao.observeDay(date.toString()).map { statusFor(date) }

    suspend fun save(supplement: Supplement): Long = supplementDao.upsert(supplement)

    suspend fun byId(id: Long): Supplement? = supplementDao.byId(id)

    suspend fun delete(id: Long) = supplementDao.delete(id)

    /**
     * Records a dose.
     *
     * **One rule covers both kinds.** A `supplement_log` row is always written, because
     * adherence is the point for everything here. A food-diary line is written *only* if the
     * dose carries calories or protein: a protein shake belongs in the day's macros, and creatine
     * at zero calories would be a row in a food diary that says nothing.
     */
    suspend fun logDose(supplement: Supplement, date: LocalDate, doses: Double = 1.0) {
        supplementDao.logDose(
            SupplementLog(date = date.toString(), supplementId = supplement.id, doses = doses),
        )

        // Replace rather than add: ticking the box twice is a correction, not a second scoop.
        nutritionDao.deleteSupplementEntry(date.toString(), supplement.id)
        if (supplement.affectsMacros) {
            nutritionDao.insert(
                NutritionEntry(
                    date = date.toString(),
                    slot = LogSlot.SUPPLEMENT,
                    sourceType = LogSource.SUPPLEMENT,
                    supplementId = supplement.id,
                    foodItemId = supplement.foodItemId,
                    label = supplement.name,
                    servings = doses,
                    kcal = supplement.kcalPerDose * doses,
                    proteinG = supplement.proteinPerDose * doses,
                    carbsG = supplement.carbsPerDose * doses,
                    fatG = supplement.fatPerDose * doses,
                ),
            )
        }
    }

    suspend fun unlogDose(supplement: Supplement, date: LocalDate) {
        supplementDao.unlog(date.toString(), supplement.id)
        nutritionDao.deleteSupplementEntry(date.toString(), supplement.id)
    }

    /**
     * Adds creatine and a whey shake if there is nothing yet.
     *
     * Two presets rather than an empty screen: these are what the feature is for, and making
     * someone type "5 g creatine monohydrate, 0 kcal" by hand is a poor first impression.
     */
    suspend fun seedDefaultsIfEmpty(): Boolean {
        if (supplementDao.count() > 0) return false
        supplementDao.upsert(
            Supplement(
                name = "Creatine",
                kind = SupplementKind.CREATINE,
                doseAmount = 5.0,
                doseUnit = "g",
                sortOrder = 0,
            ),
        )
        supplementDao.upsert(
            Supplement(
                name = "Whey protein",
                kind = SupplementKind.PROTEIN,
                doseAmount = 1.0,
                doseUnit = "scoop",
                kcalPerDose = 112.0,
                proteinPerDose = 24.0,
                carbsPerDose = 2.3,
                fatPerDose = 1.5,
                sortOrder = 1,
            ),
        )
        return true
    }
}
