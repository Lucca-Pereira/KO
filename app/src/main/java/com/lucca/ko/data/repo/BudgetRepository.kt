package com.lucca.ko.data.repo

import com.lucca.ko.data.db.BudgetLimit
import com.lucca.ko.data.db.Expense
import com.lucca.ko.data.db.dao.BudgetDao
import com.lucca.ko.data.remote.sync.BudgetLimitWire
import com.lucca.ko.data.remote.sync.ExpenseWire
import com.lucca.ko.domain.budget.BudgetMath
import com.lucca.ko.domain.budget.MonthSummary
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** What one sync round pushes for the budget, with the local rows to stamp once it lands. */
class BudgetPush(
    val expenses: List<Expense>,
    val expenseWires: List<ExpenseWire>,
    val limits: List<BudgetLimit>,
    val limitWires: List<BudgetLimitWire>,
)

/**
 * Expenses and per-category monthly limits. Deleting an expense or clearing a limit just removes
 * the row: the NAS finds out because it drops out of the `present` lists on the next sync.
 */
class BudgetRepository(private val dao: BudgetDao) {

    fun expensesIn(month: YearMonth): Flow<List<Expense>> {
        val (from, until) = BudgetMath.monthRange(month)
        return dao.expensesBetween(from, until)
    }

    fun summaryFor(month: YearMonth): Flow<MonthSummary> =
        combine(expensesIn(month), dao.limits()) { expenses, limits ->
            BudgetMath.summarize(
                expenses.map { it.category to it.amountCents },
                limits.associate { it.category to it.monthlyCents },
            )
        }

    /** Categories already used by an expense or limit, for suggestions. */
    fun knownCategories(): Flow<List<String>> =
        combine(dao.expenseCategories(), dao.limits()) { used, limits ->
            (used + limits.map { it.category }).distinct().sorted()
        }

    suspend fun expenseById(id: Long): Expense? = dao.expenseById(id)

    suspend fun limitFor(category: String): Long? =
        dao.limitByCategory(BudgetMath.normalizeCategory(category))?.monthlyCents

    /** Adds ([id] null) or edits an expense; edits bump `updatedAt` so they push again. */
    suspend fun saveExpense(id: Long?, amountCents: Long, category: String, note: String?, spentOn: String) {
        val cat = BudgetMath.normalizeCategory(category)
        val cleanNote = note?.trim()?.ifBlank { null }
        val existing = id?.let { dao.expenseById(it) }
        if (existing == null) {
            dao.insertExpense(
                Expense(
                    amountCents = amountCents, category = cat, note = cleanNote, spentOn = spentOn,
                    remoteId = UUID.randomUUID().toString(),
                ),
            )
        } else {
            dao.updateExpense(
                existing.copy(
                    amountCents = amountCents, category = cat, note = cleanNote, spentOn = spentOn,
                    updatedAt = maxOf(System.currentTimeMillis(), existing.updatedAt + 1),
                ),
            )
        }
    }

    suspend fun deleteExpense(id: Long) = dao.deleteExpense(id)

    suspend fun setLimit(category: String, monthlyCents: Long) {
        val cat = BudgetMath.normalizeCategory(category)
        if (cat.isEmpty()) return
        val existing = dao.limitByCategory(cat)
        dao.upsertLimit(
            BudgetLimit(
                cat, monthlyCents,
                updatedAt = maxOf(System.currentTimeMillis(), (existing?.updatedAt ?: 0) + 1),
            ),
        )
    }

    suspend fun clearLimit(category: String) = dao.deleteLimit(BudgetMath.normalizeCategory(category))

    // ---- Sync ------------------------------------------------------------------------

    suspend fun pendingPush(): BudgetPush {
        val expenses = dao.pendingExpenses()
        val expenseWires = expenses.map { e ->
            val remoteId = e.remoteId ?: UUID.randomUUID().toString().also { dao.setExpenseRemoteId(e.id, it) }
            ExpenseWire(
                remoteId = remoteId,
                amountCents = e.amountCents,
                category = e.category,
                note = e.note,
                spentOn = e.spentOn,
                updatedAt = e.updatedAt,
            )
        }
        val limits = dao.pendingLimits()
        val limitWires = limits.map { BudgetLimitWire(it.category, it.monthlyCents, updatedAt = it.updatedAt) }
        return BudgetPush(expenses, expenseWires, limits, limitWires)
    }

    /** Read after [pendingPush] has assigned remoteIds, so this round's pushes are listed. */
    suspend fun presentExpenseIds(): List<String> = dao.allExpenseRemoteIds()
    suspend fun presentLimitCategories(): List<String> = dao.allLimitCategories()

    suspend fun applyExpense(wire: ExpenseWire, serverTime: Long) {
        if (wire.deleted) {
            dao.deleteExpenseByRemoteId(wire.remoteId)
            return
        }
        val existing = dao.expenseByRemoteId(wire.remoteId)
        // Last write wins: a local edit newer than the pulled row stays and pushes next round.
        if (existing != null && existing.updatedAt > wire.updatedAt) return
        val row = (existing ?: Expense(amountCents = wire.amountCents, category = "", spentOn = wire.spentOn)).copy(
            amountCents = wire.amountCents,
            category = BudgetMath.normalizeCategory(wire.category),
            note = wire.note,
            spentOn = wire.spentOn,
            updatedAt = wire.updatedAt,
            remoteId = wire.remoteId,
            syncedAt = serverTime,
        )
        if (existing != null) dao.updateExpense(row) else dao.insertExpense(row)
    }

    suspend fun applyLimit(wire: BudgetLimitWire, serverTime: Long) {
        val cat = BudgetMath.normalizeCategory(wire.category)
        if (wire.deleted) {
            dao.deleteLimit(cat)
            return
        }
        val existing = dao.limitByCategory(cat)
        if (existing != null && existing.updatedAt > wire.updatedAt) return
        dao.upsertLimit(BudgetLimit(cat, wire.monthlyCents, wire.updatedAt, serverTime))
    }

    suspend fun stamp(push: BudgetPush, serverTime: Long) {
        push.expenses.forEach { dao.stampExpense(it.id, serverTime) }
        push.limits.forEach { dao.stampLimit(it.category, serverTime) }
    }
}
