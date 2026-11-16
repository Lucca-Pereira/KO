package com.lucca.ko.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucca.ko.data.db.Expense
import com.lucca.ko.data.repo.BudgetRepository
import com.lucca.ko.domain.budget.MonthSummary
import com.lucca.ko.ui.koFactory
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BudgetUiState(
    val month: YearMonth = YearMonth.now(),
    val summary: MonthSummary = MonthSummary(0, 0, emptyList()),
    val expenses: List<Expense> = emptyList(),
    val categories: List<String> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModel(private val repo: BudgetRepository) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())

    val state = month.flatMapLatest { m ->
        combine(repo.summaryFor(m), repo.expensesIn(m), repo.knownCategories()) { summary, expenses, cats ->
            BudgetUiState(m, summary, expenses, cats)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetUiState())

    fun previousMonth() { month.value = month.value.minusMonths(1) }

    fun nextMonth() { month.value = month.value.plusMonths(1) }

    fun save(id: Long?, amountCents: Long, category: String, note: String?, spentOn: String) =
        viewModelScope.launch { repo.saveExpense(id, amountCents, category, note, spentOn) }

    fun delete(id: Long) = viewModelScope.launch { repo.deleteExpense(id) }

    fun setLimit(category: String, cents: Long) = viewModelScope.launch { repo.setLimit(category, cents) }

    fun clearLimit(category: String) = viewModelScope.launch { repo.clearLimit(category) }

    companion object {
        val Factory = koFactory { BudgetViewModel(it.budgetRepository) }
    }
}
