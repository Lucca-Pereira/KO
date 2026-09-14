package com.lucca.ko.ui.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucca.ko.data.db.BodyMetric
import com.lucca.ko.data.db.FoodItem
import com.lucca.ko.data.db.LogSlot
import com.lucca.ko.data.db.NutritionEntry
import com.lucca.ko.data.repo.BodyRepository
import com.lucca.ko.data.repo.BodyTrend
import com.lucca.ko.data.repo.NutritionRepository
import com.lucca.ko.data.repo.SupplementRepository
import com.lucca.ko.data.repo.SupplementStatus
import com.lucca.ko.data.repo.TargetWithProvenance
import com.lucca.ko.data.repo.asTotals
import com.lucca.ko.data.repo.toMacroTargets
import com.lucca.ko.domain.nutrition.MacroTargets
import com.lucca.ko.domain.nutrition.MacroTotals
import com.lucca.ko.domain.nutrition.UserProfile
import com.lucca.ko.ui.koFactory
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TodayUiState(
    val date: LocalDate = LocalDate.now(),
    val entries: List<NutritionEntry> = emptyList(),
    val totals: MacroTotals = MacroTotals(),
    val targets: MacroTargets? = null,
    val targetExplanation: String = "",
    val supplements: List<SupplementStatus> = emptyList(),
    val profileComplete: Boolean = true,
    val loading: Boolean = true,
) {
    val isToday: Boolean get() = date == LocalDate.now()

    val entriesBySlot: Map<LogSlot, List<NutritionEntry>>
        get() = entries.groupBy { it.slot }.toSortedMap(compareBy { it.ordinal })
}

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val nutrition: NutritionRepository,
    private val supplements: SupplementRepository,
) : ViewModel() {

    private val date = MutableStateFlow(LocalDate.now())
    private val provenance = MutableStateFlow<TargetWithProvenance?>(null)
    private val profileComplete = MutableStateFlow(true)

    val state = combine(
        date,
        date.flatMapLatest { nutrition.observeDay(it) },
        date.flatMapLatest { nutrition.observeDayTotals(it) },
        date.flatMapLatest { nutrition.observeTarget(it) },
        combine(
            date.flatMapLatest { supplements.observeStatus(it) },
            provenance,
            profileComplete,
        ) { supps, prov, complete -> Triple(supps, prov, complete) },
    ) { day, entries, totals, storedTarget, (supps, prov, complete) ->
        TodayUiState(
            date = day,
            entries = entries,
            totals = totals.asTotals(),
            // The stored, point-in-time target wins: a day in February should be judged against
            // February's number, not against whatever the profile computes today.
            targets = storedTarget?.toMacroTargets() ?: prov?.targets,
            targetExplanation = prov?.explanation.orEmpty(),
            supplements = supps,
            profileComplete = complete,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    init {
        refreshTarget()
    }

    fun setDate(value: LocalDate) {
        date.value = value
        refreshTarget()
    }

    fun previousDay() = setDate(date.value.minusDays(1))

    fun nextDay() {
        // No logging into the future: a diary of what you are going to eat is a meal plan, and
        // there is already one of those.
        if (date.value < LocalDate.now()) setDate(date.value.plusDays(1))
    }

    fun today() = setDate(LocalDate.now())

    fun refreshTarget() = viewModelScope.launch {
        profileComplete.value = nutrition.profile().isComplete
        provenance.value = nutrition.refreshTodaysTarget(date.value)
    }

    fun deleteEntry(id: Long) = viewModelScope.launch { nutrition.deleteEntry(id) }

    fun toggleSupplement(status: SupplementStatus) = viewModelScope.launch {
        if (status.takenToday) {
            supplements.unlogDose(status.supplement, date.value)
        } else {
            supplements.logDose(status.supplement, date.value)
        }
    }

    companion object {
        val Factory = koFactory {
            TodayViewModel(it.nutritionRepository, it.supplementRepository)
        }
    }
}

// ---- Foods ------------------------------------------------------------------------------

data class FoodListUiState(
    val query: String = "",
    val results: List<FoodItem> = emptyList(),
    val recent: List<FoodItem> = emptyList(),
    val scanning: Boolean = false,
    val scanError: String? = null,
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class FoodListViewModel(private val nutrition: NutritionRepository) : ViewModel() {

    private val query = MutableStateFlow("")
    private val transient = MutableStateFlow(false to null as String?)

    /** A food found by scanning, for the screen to open straight away. */
    private val _scanned = MutableStateFlow<FoodItem?>(null)
    val scanned = _scanned

    val state = combine(
        query,
        query.flatMapLatest { nutrition.searchFoods(it) },
        nutrition.observeRecentFoods(),
        transient,
    ) { q, results, recent, (scanning, error) ->
        FoodListUiState(
            query = q,
            results = results,
            recent = if (q.isBlank()) recent else emptyList(),
            scanning = scanning,
            scanError = error,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoodListUiState())

    fun setQuery(value: String) { query.value = value }

    fun onBarcode(barcode: String) {
        transient.value = true to null
        viewModelScope.launch {
            val found = runCatching { nutrition.lookupBarcode(barcode) }.getOrNull()
            transient.value = false to if (found == null) {
                // Not an error to dead-end on: the product may simply not be in Open Food Facts,
                // or may be there with no nutrition filled in.
                "Nothing found for $barcode. Add it by hand?"
            } else {
                null
            }
            _scanned.value = found
        }
    }

    fun onScanFailed(message: String) { transient.value = false to message }

    fun clearScanned() { _scanned.value = null }

    fun dismissError() { transient.value = transient.value.first to null }

    fun toggleFavourite(food: FoodItem) =
        viewModelScope.launch { nutrition.toggleFoodFavourite(food) }

    fun delete(food: FoodItem) = viewModelScope.launch { nutrition.deleteFood(food.id) }

    companion object {
        val Factory = koFactory { FoodListViewModel(it.nutritionRepository) }
    }
}

// ---- Body -------------------------------------------------------------------------------

data class BodyUiState(
    val trend: BodyTrend = BodyTrend(),
    val entries: List<BodyMetric> = emptyList(),
    val profile: UserProfile = UserProfile(),
    val tdee: TargetWithProvenance? = null,
    val loading: Boolean = true,
)

class BodyViewModel(
    private val body: BodyRepository,
    private val nutrition: NutritionRepository,
) : ViewModel() {

    private val profile = MutableStateFlow(UserProfile())
    private val tdee = MutableStateFlow<TargetWithProvenance?>(null)

    val state = combine(
        body.observeWeightTrend(),
        body.observeAll(),
        profile,
        tdee,
    ) { trend, entries, prof, estimate ->
        BodyUiState(
            trend = trend,
            entries = entries.sortedByDescending { it.date },
            profile = prof,
            tdee = estimate,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BodyUiState())

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        profile.value = nutrition.profile()
        tdee.value = nutrition.computeTarget()
    }

    fun save(metric: BodyMetric) = viewModelScope.launch {
        body.save(metric)
        // A new weigh-in changes the calorie target, so recompute rather than leaving a stale one.
        nutrition.refreshTodaysTarget()
        refresh()
    }

    fun delete(id: Long) = viewModelScope.launch {
        body.delete(id)
        refresh()
    }

    companion object {
        val Factory = koFactory { BodyViewModel(it.bodyRepository, it.nutritionRepository) }
    }
}
