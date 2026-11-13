package com.lucca.ko.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.data.db.dao.DayTotals
import com.lucca.ko.data.db.relations.PlannedRecipe
import com.lucca.ko.data.prefs.PlanViewRepository
import com.lucca.ko.data.repair.PlanMaintenance
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.data.repo.NutritionRepository
import com.lucca.ko.data.repo.PantryRepository
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.data.repo.ShoppingRepository
import com.lucca.ko.data.repo.SupplementRepository
import com.lucca.ko.domain.Availability
import com.lucca.ko.domain.PantryResolver
import com.lucca.ko.domain.pantry.isUseSoon
import kotlinx.coroutines.flow.map
import com.lucca.ko.domain.plan.FixedWeek
import com.lucca.ko.ui.koFactory
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One of today's planned meals, with how ready the kitchen is for it. */
data class TodayMeal(
    val entryId: Long,
    /** Null once the recipe has been deleted; the meal still shows, from its title snapshot. */
    val recipeId: Long?,
    val title: String,
    val slot: MealSlot,
    val totalMinutes: Int?,
    val servings: Double,
    val cooked: Boolean,
    val ingredientCount: Int,
    val missingCount: Int,
)

data class HomeUiState(
    val loading: Boolean = true,
    val weekday: String = "",
    val meals: List<TodayMeal> = emptyList(),
    val kcalEaten: Double = 0.0,
    /** Null until a profile exists; the card then invites setting one up instead. */
    val kcalTarget: Double? = null,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val toBuy: Int = 0,
    val pantryLow: Int = 0,
    val pantryOut: Int = 0,
    val supplementsTaken: Int = 0,
    val supplementsTotal: Int = 0,
    val useSoon: List<UseSoonItem> = emptyList(),
)

data class UseSoonItem(val name: String, val expiresAt: Long)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val mealPlan: MealPlanRepository,
    private val recipes: RecipeRepository,
    pantry: PantryRepository,
    shopping: ShoppingRepository,
    nutrition: NutritionRepository,
    supplements: SupplementRepository,
    planView: PlanViewRepository,
    private val maintenance: PlanMaintenance,
) : ViewModel() {

    private val today: LocalDate = LocalDate.now()

    init {
        viewModelScope.launch { maintenance.run() }
    }

    /** Today's weekday in whichever week the user has marked as current. */
    private val meals = planView.view
        .flatMapLatest { view -> mealPlan.dayPlan(FixedWeek.dateFor(view.activeWeek, today.dayOfWeek)) }
        .combine(pantry.pantry) { planned, items -> planned to items }
        .mapLatest { (planned, items) -> planned.map { it.toTodayMeal(items) } }

    private val kitchen = combine(shopping.shoppingItems, pantry.pantry) { list, items ->
        Triple(
            list.count { !it.checked },
            items.count { it.status == StockStatus.LOW },
            items.count { it.status == StockStatus.OUT },
        )
    }

    private val eaten = combine(
        nutrition.observeDayTotals(today),
        nutrition.observeTarget(today),
    ) { totals: DayTotals, target -> totals to target?.kcal }

    /** Pantry items expiring within [USE_SOON_DAYS] (or already expired), soonest first. */
    private val useSoon = pantry.pantry.map { items ->
        items.filter { isUseSoon(it.expiresAt, today) }.sortedBy { it.expiresAt }
            .map { UseSoonItem(it.name, it.expiresAt!!) }
    }

    val state = combine(meals, kitchen, eaten, supplements.observeStatus(today), useSoon) { meals, kitchen, eaten, supps, soon ->
        val (totals, target) = eaten
        HomeUiState(
            loading = false,
            weekday = today.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()),
            meals = meals,
            kcalEaten = totals.kcal,
            kcalTarget = target,
            proteinG = totals.proteinG,
            carbsG = totals.carbsG,
            fatG = totals.fatG,
            toBuy = kitchen.first,
            pantryLow = kitchen.second,
            pantryOut = kitchen.third,
            supplementsTaken = supps.count { it.takenToday },
            supplementsTotal = supps.size,
            useSoon = soon,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private suspend fun PlannedRecipe.toTodayMeal(pantry: List<PantryItem>): TodayMeal {
        val details = recipe?.let { recipes.detailsOnce(it.id) }
        val ingredients = details?.ingredients.orEmpty()
        val missing = ingredients.count { ing ->
            PantryResolver.resolve(ing.normalizedName, ing.pantryItemId, pantry).second ==
                Availability.MISSING
        }
        val minutes = recipe?.let { (it.prepMinutes ?: 0) + (it.cookMinutes ?: 0) }?.takeIf { it > 0 }
        return TodayMeal(
            entryId = entry.id,
            recipeId = recipe?.id,
            title = displayTitle.ifBlank { "Untitled" },
            slot = entry.slot,
            totalMinutes = minutes,
            servings = entry.servings,
            cooked = entry.cooked,
            ingredientCount = ingredients.size,
            missingCount = missing,
        )
    }

    fun setCooked(entryId: Long, cooked: Boolean) =
        viewModelScope.launch { mealPlan.setCooked(entryId, cooked) }

    fun addMissingToGroceries(recipeId: Long) =
        viewModelScope.launch { recipes.addMissingIngredientsToShopping(recipeId) }

    companion object {
        val Factory = koFactory {
            HomeViewModel(
                mealPlan = it.mealPlanRepository,
                recipes = it.recipeRepository,
                pantry = it.pantryRepository,
                shopping = it.shoppingRepository,
                nutrition = it.nutritionRepository,
                supplements = it.supplementRepository,
                planView = it.planViewRepository,
                maintenance = it.planMaintenance,
            )
        }
    }
}
