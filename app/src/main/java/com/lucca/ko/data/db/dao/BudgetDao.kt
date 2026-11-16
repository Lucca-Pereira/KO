package com.lucca.ko.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lucca.ko.data.db.BudgetLimit
import com.lucca.ko.data.db.Expense
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {

    // ---- Expenses --------------------------------------------------------------------

    /** [from] inclusive, [until] exclusive; ISO dates compare correctly as text. */
    @Query("SELECT * FROM expenses WHERE spentOn >= :from AND spentOn < :until ORDER BY spentOn DESC, id DESC")
    fun expensesBetween(from: String, until: String): Flow<List<Expense>>

    @Query("SELECT DISTINCT category FROM expenses ORDER BY category")
    fun expenseCategories(): Flow<List<String>>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun expenseById(id: Long): Expense?

    @Insert
    suspend fun insertExpense(expense: Expense): Long

    @Update
    suspend fun updateExpense(expense: Expense)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteExpense(id: Long)

    @Query("SELECT * FROM expenses")
    suspend fun getAllExpenses(): List<Expense>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllExpenses(expenses: List<Expense>)

    // ---- Limits ----------------------------------------------------------------------

    @Query("SELECT * FROM budget_limits ORDER BY category")
    fun limits(): Flow<List<BudgetLimit>>

    @Query("SELECT * FROM budget_limits")
    suspend fun getAllLimits(): List<BudgetLimit>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLimit(limit: BudgetLimit)

    @Query("DELETE FROM budget_limits WHERE category = :category")
    suspend fun deleteLimit(category: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllLimits(limits: List<BudgetLimit>)

    // ---- Sync ------------------------------------------------------------------------

    @Query("SELECT * FROM expenses WHERE syncedAt IS NULL OR updatedAt > syncedAt")
    suspend fun pendingExpenses(): List<Expense>

    @Query("SELECT * FROM expenses WHERE remoteId = :remoteId LIMIT 1")
    suspend fun expenseByRemoteId(remoteId: String): Expense?

    @Query("SELECT remoteId FROM expenses WHERE remoteId IS NOT NULL")
    suspend fun allExpenseRemoteIds(): List<String>

    @Query("UPDATE expenses SET remoteId = :remoteId WHERE id = :id")
    suspend fun setExpenseRemoteId(id: Long, remoteId: String)

    @Query("UPDATE expenses SET syncedAt = :syncedAt WHERE id = :id")
    suspend fun stampExpense(id: Long, syncedAt: Long)

    @Query("DELETE FROM expenses WHERE remoteId = :remoteId")
    suspend fun deleteExpenseByRemoteId(remoteId: String)

    @Query("SELECT * FROM budget_limits WHERE syncedAt IS NULL OR updatedAt > syncedAt")
    suspend fun pendingLimits(): List<BudgetLimit>

    @Query("SELECT * FROM budget_limits WHERE category = :category")
    suspend fun limitByCategory(category: String): BudgetLimit?

    @Query("SELECT category FROM budget_limits")
    suspend fun allLimitCategories(): List<String>

    @Query("UPDATE budget_limits SET syncedAt = :syncedAt WHERE category = :category")
    suspend fun stampLimit(category: String, syncedAt: Long)
}
