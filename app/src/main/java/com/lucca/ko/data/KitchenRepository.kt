package com.lucca.ko.data

import com.lucca.ko.data.db.Dish
import com.lucca.ko.data.db.DishIngredient
import com.lucca.ko.data.db.MealPlanEntry
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.ShoppingListItem
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.data.db.dao.DishDao
import com.lucca.ko.data.db.dao.MealPlanDao
import com.lucca.ko.data.db.dao.PantryDao
import com.lucca.ko.data.db.dao.ShoppingDao
import com.lucca.ko.data.db.relations.DishWithIngredients
import com.lucca.ko.data.db.relations.PlannedDish
import com.lucca.ko.data.prefs.SettingsRepository
import com.lucca.ko.data.remote.MealDbClient
import com.lucca.ko.data.remote.MealDetail
import com.lucca.ko.data.remote.MealSummary
import com.lucca.ko.data.remote.OllamaClient
import com.lucca.ko.data.remote.RecipeIdea
import com.lucca.ko.domain.Availability
import com.lucca.ko.domain.CategoryGuesser
import com.lucca.ko.domain.IngredientMatcher
import com.lucca.ko.domain.PantryResolver
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

data class SuggestionResult(
    val usedOllama: Boolean,
    val ideas: List<RecipeIdea>,
    val meals: List<MealSummary>,
    val note: String? = null,
)

class KitchenRepository(
    private val pantryDao: PantryDao,
    private val dishDao: DishDao,
    private val mealPlanDao: MealPlanDao,
    private val shoppingDao: ShoppingDao,
    private val mealDb: MealDbClient,
    private val ollama: OllamaClient,
    private val settings: SettingsRepository,
) {
    // ---------------- Pantry ----------------

    val pantry: Flow<List<PantryItem>> = pantryDao.observeAll()

    suspend fun savePantryItem(
        id: Long?,
        name: String,
        category: String,
        status: StockStatus,
        quantity: String?,
        note: String?,
    ) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val normalized = IngredientMatcher.normalize(clean)
        val existing = if (id != null) pantryDao.byId(id) else pantryDao.byNormalized(normalized)
        val item = (existing ?: PantryItem(name = clean, normalizedName = normalized)).copy(
            name = clean,
            normalizedName = normalized,
            category = category,
            status = status,
            quantity = quantity?.trim()?.ifEmpty { null },
            note = note?.trim()?.ifEmpty { null },
            updatedAt = System.currentTimeMillis(),
        )
        val savedId = pantryDao.upsert(item)
        syncShoppingForPantry(item.copy(id = if (item.id != 0L) item.id else savedId))
    }

    suspend fun cyclePantryStatus(item: PantryItem) {
        val next = when (item.status) {
            StockStatus.IN_STOCK -> StockStatus.LOW
            StockStatus.LOW -> StockStatus.OUT
            StockStatus.OUT -> StockStatus.IN_STOCK
        }
        setPantryStatus(item, next)
    }

    suspend fun setPantryStatus(item: PantryItem, status: StockStatus) {
        pantryDao.setStatus(item.id, status, System.currentTimeMillis())
        syncShoppingForPantry(item.copy(status = status))
    }

    suspend fun deletePantryItem(id: Long) = pantryDao.delete(id)

    /**
     * Recomputes [normalizedName] for every stored pantry / shopping / recipe-ingredient
     * row using the current normalize() rules, and runs once (guarded by a flag). Fixes
     * rows saved before the accent-folding fix, e.g. "Orégano" stored as "gano".
     */
    suspend fun repairNormalizationOnce() {
        if (settings.isNormalizationRepaired()) return
        pantryDao.getAll().forEach { item ->
            val fixed = IngredientMatcher.normalize(item.name)
            if (fixed.isNotBlank() && fixed != item.normalizedName) {
                runCatching { pantryDao.update(item.copy(normalizedName = fixed)) }
            }
        }
        shoppingDao.getAll().forEach { item ->
            val fixed = IngredientMatcher.normalize(item.name)
            if (fixed.isNotBlank() && fixed != item.normalizedName) {
                runCatching { shoppingDao.update(item.copy(normalizedName = fixed)) }
            }
        }
        dishDao.getAllIngredients().forEach { ing ->
            val fixed = IngredientMatcher.normalize(ing.rawName)
            if (fixed.isNotBlank() && fixed != ing.normalizedName) {
                runCatching { dishDao.updateIngredient(ing.copy(normalizedName = fixed)) }
            }
        }
        settings.markNormalizationRepaired()
    }

    /**
     * Asks the recipe bot to translate every pantry item name into English and stores it
     * as [PantryItem.searchName], used for recipe search and matching. Returns how many
     * items were updated; 0 if the bot is unreachable or unconfigured.
     */
    suspend fun translatePantryToEnglish(): Int {
        val cfg = settings.settings.first()
        if (!cfg.ollamaConfigured) return 0
        val items = pantry.first()
        if (items.isEmpty()) return 0
        val translations = ollama.translateFoods(
            baseUrl = cfg.ollamaBaseUrl,
            model = cfg.ollamaModel,
            names = items.map { it.name },
        )
        if (translations.isEmpty()) return 0
        var updated = 0
        items.forEach { item ->
            val english = translations[item.name]?.takeIf { it.isNotBlank() } ?: return@forEach
            if (!english.equals(item.searchName, ignoreCase = true)) {
                pantryDao.update(item.copy(searchName = english))
                updated++
            }
        }
        return updated
    }

    /** Keeps the shopping list in step with a pantry item's status. */
    private suspend fun syncShoppingForPantry(item: PantryItem) {
        if (item.status == StockStatus.OUT) {
            if (shoppingDao.byNormalized(item.normalizedName) == null) {
                shoppingDao.insertIgnore(
                    ShoppingListItem(
                        name = item.name,
                        normalizedName = item.normalizedName,
                        category = item.category,
                        pantryItemId = item.id.takeIf { it != 0L },
                    ),
                )
            }
        } else {
            shoppingDao.deleteUncheckedByNormalized(item.normalizedName)
        }
    }

    // ---------------- Shopping ----------------

    val shoppingItems: Flow<List<ShoppingListItem>> = shoppingDao.observeAll()

    suspend fun addManualShoppingItem(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val normalized = IngredientMatcher.normalize(clean)
        shoppingDao.insertIgnore(
            ShoppingListItem(
                name = clean,
                normalizedName = normalized,
                category = CategoryGuesser.guess(clean),
            ),
        )
    }

    suspend fun setShoppingChecked(item: ShoppingListItem, checked: Boolean) {
        shoppingDao.update(item.copy(checked = checked))
        val target = if (checked) StockStatus.IN_STOCK else StockStatus.OUT
        val pantryItem = item.pantryItemId?.let { pantryDao.byId(it) }
            ?: pantryDao.byNormalized(item.normalizedName)
        if (pantryItem != null) {
            pantryDao.setStatus(pantryItem.id, target, System.currentTimeMillis())
        } else if (checked) {
            pantryDao.upsert(
                PantryItem(
                    name = item.name,
                    normalizedName = item.normalizedName,
                    category = item.category,
                    status = StockStatus.IN_STOCK,
                ),
            )
        }
    }

    suspend fun deleteShoppingItem(id: Long) = shoppingDao.delete(id)

    suspend fun clearCheckedShopping() = shoppingDao.clearChecked()

    // ---------------- Meal plan & dishes ----------------

    fun weekPlan(monday: LocalDate): Flow<List<PlannedDish>> =
        mealPlanDao.observeRange(monday.toString(), monday.plusDays(6).toString())

    fun dishWithIngredients(id: Long): Flow<DishWithIngredients?> =
        dishDao.observeDishWithIngredients(id)

    suspend fun saveMealFromDetail(detail: MealDetail, date: LocalDate, slot: MealSlot): Long {
        val dish = Dish(
            title = detail.title,
            sourceUrl = detail.bestLink(),
            imageUrl = detail.thumbUrl,
            mealdbId = detail.id,
            instructions = detail.instructions,
        )
        val ingredients = detail.ingredients.map {
            DishIngredient(
                dishId = 0,
                rawName = it.name,
                normalizedName = IngredientMatcher.normalize(it.name),
                measure = it.measure?.ifBlank { null },
            )
        }
        val dishId = dishDao.insertDishWithIngredients(dish, ingredients)
        mealPlanDao.insert(MealPlanEntry(date = date.toString(), slot = slot, dishId = dishId))
        return dishId
    }

    suspend fun saveManualDish(
        title: String,
        url: String?,
        ingredientLines: List<String>,
        date: LocalDate,
        slot: MealSlot,
    ): Long {
        val dish = Dish(
            title = title.trim(),
            sourceUrl = url?.trim()?.ifEmpty { null },
        )
        val ingredients = ingredientLines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map {
                DishIngredient(
                    dishId = 0,
                    rawName = it,
                    normalizedName = IngredientMatcher.normalize(it),
                )
            }
        val dishId = dishDao.insertDishWithIngredients(dish, ingredients)
        mealPlanDao.insert(MealPlanEntry(date = date.toString(), slot = slot, dishId = dishId))
        return dishId
    }

    suspend fun removePlanEntry(id: Long) {
        mealPlanDao.delete(id)
        dishDao.deleteOrphanDishes()
    }

    suspend fun linkIngredientToPantry(ingredientId: Long, pantryItemId: Long?) {
        val ingredient = dishDao.ingredientById(ingredientId) ?: return
        dishDao.updateIngredient(ingredient.copy(pantryItemId = pantryItemId))
    }

    /** Mark a recipe ingredient as run out: flips the pantry item and adds it to shopping. */
    suspend fun markIngredientRanOut(ingredientId: Long) =
        applyIngredientStatus(ingredientId, StockStatus.OUT)

    /** Mark a recipe ingredient as back in stock. */
    suspend fun markIngredientHave(ingredientId: Long) =
        applyIngredientStatus(ingredientId, StockStatus.IN_STOCK)

    private suspend fun applyIngredientStatus(ingredientId: Long, status: StockStatus) {
        val ingredient = dishDao.ingredientById(ingredientId) ?: return
        val pantrySnapshot = pantry.first()
        val (match, _) = PantryResolver.resolve(
            ingredient.normalizedName,
            ingredient.pantryItemId,
            pantrySnapshot,
        )
        val item = match ?: run {
            val newItem = PantryItem(
                name = ingredient.rawName,
                normalizedName = ingredient.normalizedName,
                category = CategoryGuesser.guess(ingredient.rawName),
                status = status,
            )
            val newId = pantryDao.upsert(newItem)
            val saved = newItem.copy(id = newId)
            if (ingredient.pantryItemId != saved.id) {
                dishDao.updateIngredient(ingredient.copy(pantryItemId = saved.id))
            }
            syncShoppingForPantry(saved)
            return
        }
        pantryDao.setStatus(item.id, status, System.currentTimeMillis())
        syncShoppingForPantry(item.copy(status = status))
    }

    suspend fun addMissingIngredientsToShopping(dishId: Long) {
        val dish = dishDao.dishWithIngredientsOnce(dishId) ?: return
        val pantrySnapshot = pantry.first()
        dish.ingredients.forEach { ingredient ->
            val (match, availability) = PantryResolver.resolve(
                ingredient.normalizedName,
                ingredient.pantryItemId,
                pantrySnapshot,
            )
            if (availability == Availability.MISSING &&
                shoppingDao.byNormalized(ingredient.normalizedName) == null
            ) {
                shoppingDao.insertIgnore(
                    ShoppingListItem(
                        name = match?.name ?: ingredient.rawName,
                        normalizedName = ingredient.normalizedName,
                        category = match?.category ?: CategoryGuesser.guess(ingredient.rawName),
                        pantryItemId = match?.id,
                    ),
                )
            }
        }
    }

    // ---------------- Recipe search / suggestions ----------------

    suspend fun searchMeals(query: String): List<MealSummary> =
        if (query.isBlank()) emptyList() else mealDb.searchByName(query)

    suspend fun mealDetail(id: String): MealDetail? = mealDb.lookup(id)

    suspend fun testOllama(baseUrl: String): Result<List<String>> = runCatching {
        ollama.listModels(baseUrl)
    }

    suspend fun suggestDishes(): SuggestionResult {
        val cfg = settings.settings.first()
        val pantryItems = pantry.first()
        // Prefer the English alias so bot ideas + TheMealDB search work for a non-English pantry.
        val pantryNames = pantryItems.map { it.searchName?.takeIf(String::isNotBlank) ?: it.name }

        var usedOllama = false
        var note: String? = null
        var ideas: List<RecipeIdea> = emptyList()

        if (cfg.ollamaConfigured) {
            try {
                ideas = ollama.suggestRecipes(
                    baseUrl = cfg.ollamaBaseUrl,
                    model = cfg.ollamaModel,
                    pantry = pantryNames,
                    count = cfg.suggestionCount,
                )
                usedOllama = ideas.isNotEmpty()
                if (ideas.isEmpty()) note = "The model didn't return any ideas — showing pantry matches instead."
            } catch (e: Exception) {
                note = "Couldn't reach Ollama (${e.message}). Showing pantry matches instead."
            }
        } else {
            note = "Set your Ollama server in Settings for AI suggestions. Showing pantry matches."
        }

        val meals = LinkedHashMap<String, MealSummary>()

        for (idea in ideas.take(cfg.suggestionCount)) {
            // Small models often return a whole sentence as the "query"; fall back to the
            // dish name so the recipe search still lands on something.
            val terms = listOf(idea.query, idea.dish)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            var hits: List<MealSummary> = emptyList()
            for (term in terms) {
                hits = runCatching { mealDb.searchByName(term) }.getOrDefault(emptyList())
                    .ifEmpty { runCatching { mealDb.filterByIngredient(term) }.getOrDefault(emptyList()) }
                if (hits.isNotEmpty()) break
            }
            hits.take(3).forEach { if (it.id.isNotBlank()) meals.putIfAbsent(it.id, it) }
        }

        if (meals.size < 6) {
            for (name in pantryNames.shuffled().take(4)) {
                runCatching { mealDb.filterByIngredient(name) }.getOrDefault(emptyList())
                    .take(4)
                    .forEach { if (it.id.isNotBlank()) meals.putIfAbsent(it.id, it) }
                if (meals.size >= 12) break
            }
        }

        if (meals.isEmpty()) {
            repeat(6) {
                runCatching { mealDb.random() }.getOrNull()?.let { d ->
                    meals.putIfAbsent(d.id, MealSummary(d.id, d.title, d.thumbUrl))
                }
            }
        }

        return SuggestionResult(
            usedOllama = usedOllama,
            ideas = ideas,
            meals = meals.values.toList(),
            note = note,
        )
    }
}
