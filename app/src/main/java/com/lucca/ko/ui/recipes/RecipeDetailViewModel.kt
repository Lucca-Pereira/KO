package com.lucca.ko.ui.recipes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lucca.ko.data.db.Recipe
import com.lucca.ko.data.db.RecipeStep
import com.lucca.ko.data.db.Tag
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.LogSlot
import com.lucca.ko.data.repo.MealPlanRepository
import com.lucca.ko.data.repo.NutritionRepository
import com.lucca.ko.data.repo.PantryRepository
import com.lucca.ko.data.repo.RecipeRepository
import com.lucca.ko.domain.Availability
import com.lucca.ko.domain.PantryResolver
import com.lucca.ko.domain.ResolvedIngredient
import com.lucca.ko.domain.recipe.InstructionSplitter
import com.lucca.ko.ui.koFactory
import com.lucca.ko.ui.nav.RecipeDetailRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecipeDetailUiState(
    val loading: Boolean = true,
    val recipe: Recipe? = null,
    val ingredients: List<ResolvedIngredient> = emptyList(),
    val steps: List<RecipeStep> = emptyList(),
    val fallbackSteps: List<String> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val missingCount: Int = 0,
    val planCount: Int = 0,
)

/** One running countdown. [endsAt] is wall-clock millis so it survives a rotation. */
data class CookTimer(val id: Int, val label: String, val endsAt: Long)

/** A step as shown while cooking: from saved steps, or the split prose of an import. */
data class CookStep(val text: String, val minutes: Int?)

/**
 * Screen-only state: nothing here is saved. The scale, what you have crossed off, which step you
 * are on and the running timers all reset when you leave the recipe.
 */
data class CookSession(
    val scale: Double = 1.0,
    val crossed: Set<Long> = emptySet(),
    val currentStep: Int = 0,
    val cooking: Boolean = false,
    val timers: List<CookTimer> = emptyList(),
)

val RecipeDetailUiState.cookSteps: List<CookStep>
    get() = if (steps.isNotEmpty()) {
        steps.map { CookStep(it.text, it.minutes) }
    } else {
        fallbackSteps.map { CookStep(it, null) }
    }

class RecipeDetailViewModel(
    private val recipes: RecipeRepository,
    private val pantryRepo: PantryRepository,
    private val mealPlan: MealPlanRepository,
    private val nutrition: NutritionRepository,
    private val recipeId: Long,
) : ViewModel() {

    private val planCount = MutableStateFlow(0)

    private val _session = MutableStateFlow(CookSession())
    val session = _session.asStateFlow()
    private var nextTimerId = 1

    fun setScale(scale: Double) = _session.update { it.copy(scale = scale) }

    fun toggleCrossed(ingredientId: Long) = _session.update {
        it.copy(crossed = if (ingredientId in it.crossed) it.crossed - ingredientId else it.crossed + ingredientId)
    }

    fun setStep(index: Int) = _session.update { it.copy(currentStep = index.coerceAtLeast(0)) }

    fun setCooking(on: Boolean) = _session.update { it.copy(cooking = on) }

    fun startTimer(label: String, seconds: Int) = _session.update {
        it.copy(timers = it.timers + CookTimer(nextTimerId++, label, System.currentTimeMillis() + seconds * 1000L))
    }

    fun cancelTimer(id: Int) = _session.update { it.copy(timers = it.timers.filterNot { t -> t.id == id }) }

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _deleted = MutableStateFlow(false)

    /** Flips once the recipe is gone, so the screen can navigate away. */
    val deleted = _deleted.asStateFlow()

    init {
        viewModelScope.launch { planCount.value = recipes.planCountFor(recipeId) }
    }

    val state = combine(
        recipes.observeRecipe(recipeId),
        pantryRepo.pantry,
        planCount,
    ) { details, pantry, planned ->
        if (details == null) {
            RecipeDetailUiState(loading = false)
        } else {
            val resolved = details.orderedIngredients.map { ing ->
                val (match, availability) = PantryResolver.resolve(
                    ing.normalizedName, ing.pantryItemId, pantry,
                )
                ResolvedIngredient(
                    dishIngredientId = ing.id,
                    rawName = ing.rawName,
                    normalizedName = ing.normalizedName,
                    measure = ing.measure,
                    availability = availability,
                    pantryItem = match,
                )
            }
            val steps = details.orderedSteps
            RecipeDetailUiState(
                loading = false,
                recipe = details.recipe,
                ingredients = resolved,
                steps = steps,
                // A MealDB import has prose and no step rows until it is edited; show that prose
                // split into readable steps rather than one intimidating paragraph.
                fallbackSteps = if (steps.isEmpty()) {
                    InstructionSplitter.split(details.recipe.instructions)
                } else {
                    emptyList()
                },
                tags = details.tags,
                missingCount = resolved.count { it.availability == Availability.MISSING },
                planCount = planned,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipeDetailUiState())

    fun markRanOut(ingredientId: Long) =
        viewModelScope.launch { recipes.markIngredientRanOut(ingredientId) }

    fun markHave(ingredientId: Long) =
        viewModelScope.launch { recipes.markIngredientHave(ingredientId) }

    fun addAllMissingToShopping() =
        viewModelScope.launch { recipes.addMissingIngredientsToShopping(recipeId, _session.value.scale) }

    fun toggleFavourite() = viewModelScope.launch {
        val current = state.value.recipe?.isFavourite ?: return@launch
        recipes.setFavourite(recipeId, !current)
    }

    fun addToPlan(date: java.time.LocalDate, slot: MealSlot) = viewModelScope.launch {
        mealPlan.addToPlan(recipeId, date, slot)
        planCount.value = recipes.planCountFor(recipeId)
    }

    /** Logs a portion of this recipe into today's food diary. */
    fun logServings(servings: Double) = viewModelScope.launch {
        val logged = nutrition.logRecipe(recipeId, servings, java.time.LocalDate.now(), LogSlot.DINNER)
        _message.value = if (logged == null) {
            "This recipe has no macros yet — ask Claude for them and import the file (Settings)."
        } else {
            "Logged $servings serving${if (servings == 1.0) "" else "s"}."
        }
    }

    fun clearMessage() { _message.value = null }

    fun refreshPlanCount() = viewModelScope.launch {
        planCount.value = recipes.planCountFor(recipeId)
    }

    fun delete() = viewModelScope.launch {
        recipes.deleteRecipe(recipeId)
        _deleted.value = true
    }

    companion object {
        val Factory = koFactory { container ->
            RecipeDetailViewModel(
                recipes = container.recipeRepository,
                pantryRepo = container.pantryRepository,
                mealPlan = container.mealPlanRepository,
                nutrition = container.nutritionRepository,
                recipeId = createSavedStateHandle().recipeId(),
            )
        }

        private fun SavedStateHandle.recipeId(): Long =
            runCatching { toRoute<RecipeDetailRoute>().recipeId }.getOrDefault(0L)
    }
}
