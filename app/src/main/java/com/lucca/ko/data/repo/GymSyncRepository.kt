package com.lucca.ko.data.repo

import com.lucca.ko.data.db.BodyMetric
import com.lucca.ko.data.db.LogSlot
import com.lucca.ko.data.db.LogSource
import com.lucca.ko.data.db.NutritionEntry
import com.lucca.ko.data.db.SupplementLog
import com.lucca.ko.data.db.dao.BodyDao
import com.lucca.ko.data.db.dao.NutritionDao
import com.lucca.ko.data.db.dao.SupplementDao
import com.lucca.ko.data.remote.sync.BodyMetricWire
import com.lucca.ko.data.remote.sync.GymSnapshot
import com.lucca.ko.data.remote.sync.NutritionEntryWire
import com.lucca.ko.data.remote.sync.SupplementLogWire
import com.lucca.ko.data.remote.sync.SupplementWire
import com.lucca.ko.data.remote.sync.TargetWire
import java.util.UUID

/** What one sync round pushes for the gym, with the local rows to stamp once it lands. */
class GymPush(
    val entries: List<NutritionEntry>,
    val entryWires: List<NutritionEntryWire>,
    val logs: List<SupplementLog>,
    val logWires: List<SupplementLogWire>,
    val bodies: List<BodyMetric>,
    val bodyWires: List<BodyMetricWire>,
)

/**
 * The gym half of [SyncRepository]: food diary, supplement ticks and weigh-ins, so Claude can log
 * them from a chat. Same rules as the kitchen side — the phone assigns `remoteId`s before the
 * first push, pulled rows are matched by `remoteId` first — plus one the kitchen doesn't have:
 * a pulled row can be a `deleted` tombstone (Claude removing a diary line), applied as a delete.
 *
 * Supplements and targets are phone-owned and go up whole every round ([snapshot]); the NAS
 * only reads them. Body metrics key on their date, so they need no remoteId.
 */
class GymSyncRepository(
    private val nutritionDao: NutritionDao,
    private val supplementDao: SupplementDao,
    private val bodyDao: BodyDao,
) {
    suspend fun pendingPush(): GymPush {
        val supplementNames = supplementDao.getAllSupplements().associate { it.id to it.name }

        val entries = nutritionDao.pendingPush()
        val entryWires = entries.map { e ->
            val remoteId = e.remoteId ?: freshId { nutritionDao.setRemoteId(e.id, it) }
            NutritionEntryWire(
                remoteId = remoteId,
                date = e.date,
                slot = e.slot.name,
                sourceType = e.sourceType.name,
                supplementName = e.supplementId?.let { supplementNames[it] },
                label = e.label,
                grams = e.grams,
                servings = e.servings,
                kcal = e.kcal,
                proteinG = e.proteinG,
                carbsG = e.carbsG,
                fatG = e.fatG,
                fiberG = e.fiberG,
                note = e.note,
                updatedAt = e.updatedAt,
            )
        }

        // A tick for a supplement that no longer exists can't be named, so it isn't sent.
        val logs = supplementDao.pendingPushLogs().filter { it.supplementId in supplementNames }
        val logWires = logs.map { l ->
            val remoteId = l.remoteId ?: freshId { supplementDao.setLogRemoteId(l.id, it) }
            SupplementLogWire(
                remoteId = remoteId,
                date = l.date,
                supplementName = supplementNames.getValue(l.supplementId),
                doses = l.doses,
                updatedAt = l.takenAt,
            )
        }

        val bodies = bodyDao.pendingPush()
        val bodyWires = bodies.map { it.toWire() }

        return GymPush(entries, entryWires, logs, logWires, bodies, bodyWires)
    }

    /** Read after [pendingPush] has assigned remoteIds, so this round's pushes are listed. */
    suspend fun presentEntryIds(): List<String> = nutritionDao.allRemoteIds()
    suspend fun presentLogIds(): List<String> = supplementDao.allLogRemoteIds()
    suspend fun presentBodyDates(): List<String> = bodyDao.allDates()

    suspend fun snapshot(): GymSnapshot = GymSnapshot(
        supplements = supplementDao.getAllSupplements().map {
            SupplementWire(
                name = it.name,
                kind = it.kind.name,
                doseAmount = it.doseAmount,
                doseUnit = it.doseUnit,
                kcalPerDose = it.kcalPerDose,
                proteinPerDose = it.proteinPerDose,
                carbsPerDose = it.carbsPerDose,
                fatPerDose = it.fatPerDose,
                dosesPerDay = it.dosesPerDay,
                active = it.active,
            )
        },
        targets = nutritionDao.allTargets().map {
            TargetWire(it.effectiveFrom, it.kcal, it.proteinG, it.carbsG, it.fatG, it.source)
        },
    )

    suspend fun applyEntry(wire: NutritionEntryWire, serverTime: Long) {
        if (wire.deleted) {
            nutritionDao.deleteByRemoteId(wire.remoteId)
            return
        }
        val existing = nutritionDao.byRemoteId(wire.remoteId)
        val supplementId = wire.supplementName?.let { supplementDao.byName(it)?.id }
        val source = runCatching { LogSource.valueOf(wire.sourceType) }.getOrDefault(LogSource.QUICK)

        if (existing == null && source == LogSource.SUPPLEMENT && supplementId != null) {
            // The phone's own rule for a supplement's diary line: replace, never a second one.
            nutritionDao.deleteSupplementEntry(wire.date, supplementId)
        }

        val row = (existing ?: NutritionEntry(date = wire.date, label = wire.label)).copy(
            date = wire.date,
            slot = runCatching { LogSlot.valueOf(wire.slot) }.getOrDefault(LogSlot.SNACK),
            sourceType = source,
            supplementId = supplementId ?: existing?.supplementId,
            label = wire.label,
            grams = wire.grams,
            servings = wire.servings,
            kcal = wire.kcal,
            proteinG = wire.proteinG,
            carbsG = wire.carbsG,
            fatG = wire.fatG,
            fiberG = wire.fiberG,
            note = wire.note,
            updatedAt = wire.updatedAt,
            remoteId = wire.remoteId,
            syncedAt = serverTime,
        )
        if (existing != null) nutritionDao.update(row) else nutritionDao.insert(row)
    }

    suspend fun applyLog(wire: SupplementLogWire, serverTime: Long) {
        if (wire.deleted) {
            supplementDao.deleteLogByRemoteId(wire.remoteId)
            return
        }
        val supplement = supplementDao.byName(wire.supplementName)
            ?: error("no supplement called \"${wire.supplementName}\" on the phone")

        val existing = supplementDao.logByRemoteId(wire.remoteId)
        if (existing != null) {
            supplementDao.logDose(existing.copy(doses = wire.doses, syncedAt = serverTime))
            return
        }
        val inserted = supplementDao.insertLogIgnore(
            SupplementLog(
                date = wire.date,
                supplementId = supplement.id,
                doses = wire.doses,
                takenAt = wire.updatedAt,
                remoteId = wire.remoteId,
                syncedAt = serverTime,
            ),
        )
        if (inserted <= 0) {
            // Already ticked here that day under another (or no) remoteId: adopt the NAS's id.
            val local = supplementDao.entryFor(wire.date, supplement.id) ?: return
            supplementDao.stampLogSync(local.id, wire.remoteId, serverTime)
        }
    }

    suspend fun stamp(push: GymPush, serverTime: Long) {
        push.entries.forEach { nutritionDao.stampSynced(it.id, serverTime) }
        push.logs.zip(push.logWires).forEach { (log, wire) ->
            supplementDao.stampLogSync(log.id, wire.remoteId, serverTime)
        }
        push.bodies.forEach { bodyDao.stampSynced(it.id, serverTime) }
    }

    private suspend fun freshId(persist: suspend (String) -> Unit): String =
        UUID.randomUUID().toString().also { persist(it) }
}

private fun BodyMetric.toWire() = BodyMetricWire(
    date = date,
    weightKg = weightKg,
    bodyFatPct = bodyFatPct,
    waistCm = waistCm,
    chestCm = chestCm,
    hipCm = hipCm,
    armCm = armCm,
    thighCm = thighCm,
    neckCm = neckCm,
    note = note,
    updatedAt = recordedAt,
)

/** A pulled weigh-in as a local row; [BodyRepository.saveFromSync] keys it on the date. */
fun BodyMetricWire.toMetric(serverTime: Long) = BodyMetric(
    date = date,
    weightKg = weightKg,
    bodyFatPct = bodyFatPct,
    waistCm = waistCm,
    chestCm = chestCm,
    hipCm = hipCm,
    armCm = armCm,
    thighCm = thighCm,
    neckCm = neckCm,
    note = note,
    recordedAt = updatedAt,
    syncedAt = serverTime,
)
