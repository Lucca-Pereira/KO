package com.lucca.ko.data.repo

import com.lucca.ko.data.db.BodyMetric
import com.lucca.ko.data.db.dao.BodyDao
import com.lucca.ko.data.prefs.ProfileRepository
import com.lucca.ko.domain.nutrition.DatedValue
import com.lucca.ko.domain.nutrition.WeightTrend
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Weight over time, plus the smoothed trend that is actually worth looking at. */
data class BodyTrend(
    val raw: List<DatedValue> = emptyList(),
    val smoothed: List<DatedValue> = emptyList(),
    val currentTrendKg: Double? = null,
    val changePerWeekKg: Double? = null,
    val hasEnoughData: Boolean = false,
)

class BodyRepository(
    private val bodyDao: BodyDao,
    private val profileRepo: ProfileRepository,
) {
    fun observeAll(): Flow<List<BodyMetric>> = bodyDao.observeAll()

    /**
     * The weight series, smoothed.
     *
     * Daily bodyweight swings by a kilo on water alone, so the raw series cannot tell you whether
     * a cut is working and the smoothed one can. Both are returned: the chart draws the readings
     * as dots and the trend as a line, because hiding the raw data would be its own kind of lie.
     */
    fun observeWeightTrend(): Flow<BodyTrend> = bodyDao.observeAll().map { metrics ->
        val raw = metrics
            .mapNotNull { m -> m.weightKg?.let { DatedValue(LocalDate.parse(m.date), it) } }
            .sortedBy { it.date }

        val smoothed = WeightTrend.ema(raw)
        BodyTrend(
            raw = raw,
            smoothed = smoothed,
            currentTrendKg = WeightTrend.currentTrend(smoothed),
            changePerWeekKg = WeightTrend.slopeKgPerWeek(smoothed),
            hasEnoughData = raw.size >= WeightTrend.MIN_POINTS,
        )
    }

    suspend fun onDate(date: LocalDate): BodyMetric? = bodyDao.onDate(date.toString())

    /**
     * Records a weigh-in, replacing any earlier one that day.
     *
     * The profile's weight is kept in step, because that is what the calorie maths reads — a
     * target computed against a weight from four months ago is worse than no target at all.
     */
    suspend fun save(metric: BodyMetric) {
        val existing = bodyDao.onDate(metric.date)
        bodyDao.upsert(metric.copy(id = existing?.id ?: 0))

        val latest = bodyDao.latestWeighIn()
        if (latest != null && latest.date == metric.date) {
            latest.weightKg?.let { profileRepo.setWeight(it, latest.bodyFatPct) }
        }
    }

    suspend fun delete(id: Long) = bodyDao.delete(id)
}
