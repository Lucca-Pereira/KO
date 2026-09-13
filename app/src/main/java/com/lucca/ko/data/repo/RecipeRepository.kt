package com.lucca.ko.data.repo

import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.Recipe
import com.lucca.ko.data.db.RecipeIngredient
import com.lucca.ko.data.db.RecipeSource
import com.lucca.ko.data.db.ShoppingListItem
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.data.db.Tag
import com.lucca.ko.data.db.dao.PantryDao
import com.lucca.ko.data.db.dao.RecipeDao
import com.lucca.ko.data.db.dao.ShoppingDao
import com.lucca.ko.data.db.dao.TagDao
import com.lucca.ko.data.db.relations.RecipeWithDetails
import com.lucca.ko.data.remote.MealDbClient
import com.lucca.ko.data.remote.MealDetail
import com.lucca.ko.data.remote.MealSummary
import com.lucca.ko.domain.Availability
import com.lucca.ko.domain.CategoryGuesser
import com.lucca.ko.domain.IngredientMatcher
import com.lucca.ko.domain.PantryResolver
import com.lucca.ko.domain.units.MeasureParser
import kotlinx.coroutines.flow.Flow

/**
 * The recipe library.
 *
 * Recipes are owned independently of the meal plan — there is no such thing as an orphan recipe
 * any more, and nothing here deletes one implicitly. Deleting a recipe is an explicit, confirmed
 * act; `meal_plan.dishId` is `ON DELETE SET NULL` so the calendar keeps its history.
 */
class RecipeRepository(
    private val recipeDao: RecipeDao,
    private val tagDao: TagDao,
    private val pantryDao: PantryDao,
    private val shoppingDao: ShoppingDao,
    private val mealDb: MealDbClient,
) {
    fun observeRecipe(id: Long): Flow<RecipeWithDetails?> = recipeDao.observeRecipe(id)

    fun searchLibrary(query: String): Flow<List<RecipeWithDetails>> =
        recipeDao.searchLibrary(query.trim())

    val tags: Flow<List<Tag>> = tagDao.observeAll()

    suspend fun recipeById(id: Long): Recipe? = recipeDao.recipeById(id)

    // ---- Creating ------------------------------------------------------------------

    /**
     * Imports a MealDB meal into the library, reusing the existing recipe if it is already
     * there. Deliberately does not overwrite: re-adding a meal you have since edited must not
     * wipe your edits.
     */
    suspend fun importFromMealDb(detail: MealDetail): Long {
        recipeDao.byMealdbId(detail.id)?.let { return it.id }

        val recipe = Recipe(
            title = detail.title,
            sourceUrl = detail.bestLink(),
            imageUrl = detail.thumbUrl,
            mealdbId = detail.id,
            instructions = detail.instructions,
            source = RecipeSource.MEALDB,
        )
        val ingredients = detail.ingredients.map { line ->
            val parsed = MeasureParser.parse(line.measure)
            RecipeIngredient(
                dishId = 0,
                rawName = line.name,
                normalizedName = IngredientMatcher.normalize(line.name),
                measure = line.measure?.ifBlank { null },
                quantity = parsed?.quantity,
                unit = parsed?.unit,
            )
        }
        val id = recipeDao.insertRecipeWithIngredients(recipe, ingredients)
        refreshSearchBlob(id)
        return id
    }

    /** Creates an empty recipe for the editor to fill in. Returns its id. */
    suspend fun createBlankRecipe(title: String = ""): Long {
        val id = recipeDao.insertRecipe(Recipe(title = title.trim(), source = RecipeSource.MANUAL))
        refreshSearchBlob(id)
        return id
    }

    suspend fun saveManualRecipe(
        title: String,
        url: String?,
        ingredientLines: List<String>,
    ): Long {
        val ingredients = ingredientLines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map {
                RecipeIngredient(
                    dishId = 0,
                    rawName = it,
                    normalizedName = IngredientMatcher.normalize(it),
                )
            }
        val id = recipeDao.insertRecipeWithIngredients(
            Recipe(
                title = title.trim(),
                sourceUrl = url?.trim()?.ifEmpty { null },
                source = RecipeSource.MANUAL,
            ),
            ingredients,
        )
        refreshSearchBlob(id)
        return id
    }

    // ---- Editing -------------------------------------------------------------------

    suspend fun updateRecipe(recipe: Recipe) {
        recipeDao.updateRecipe(recipe.copy(updatedAt = System.currentTimeMillis()))
        refreshSearchBlob(recipe.id)
    }

    suspend fun setFavourite(id: Long, favourite: Boolean) =
        recipeDao.setFavourite(id, favourite, System.currentTimeMillis())

    suspend fun addIngredient(ingredient: RecipeIngredient): Long {
        val order = recipeDao.ingredientsFor(ingredient.dishId).maxOfOrNull { it.sortOrder } ?: -1
        val id = recipeDao.insertIngredient(ingredient.copy(sortOrder = order + 1))
        refreshSearchBlob(ingredient.dishId)
        return id
    }

    suspend fun updateIngredient(ingredient: RecipeIngredient) {
        recipeDao.updateIngredient(ingredient)
        refreshSearchBlob(ingredient.dishId)
    }

    suspend fun deleteIngredient(ingredient: RecipeIngredient) {
        recipeDao.deleteIngredient(ingredient.id)
        refreshSearchBlob(ingredient.dishId)
    }

    /** Persists a drag-reorder: [orderedIds] is the ingredient list in its new order. */
    suspend fun reorderIngredients(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> recipeDao.setIngredientOrder(id, index) }
    }

    suspend fun setTags(dishId: Long, names: List<String>) {
        tagDao.unlinkAllFor(dishId)
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { name ->
            val normalized = IngredientMatcher.normalize(name).ifBlank { name.lowercase() }
            val existing = tagDao.byNormalized(normalized)
            val tagId = existing?.id
                ?: tagDao.insert(Tag(name = name, normalizedName = normalized))
                    .takeIf { it > 0 }
                ?: tagDao.byNormalized(normalized)?.id
                ?: return@forEach
            tagDao.link(com.lucca.ko.data.db.RecipeTag(dishId = dishId, tagId = tagId))
        }
        tagDao.deleteUnusedTags()
        refreshSearchBlob(dishId)
    }

    /** Explicit, user-confirmed. Plan entries survive via `ON DELETE SET NULL`. */
    suspend fun deleteRecipe(id: Long) {
        recipeDao.deleteRecipe(id)
        tagDao.deleteUnusedTags()
    }

    suspend fun recordCooked(id: Long) = recipeDao.recordCooked(id, System.currentTimeMillis())

    /** Rebuilds the lowercased blob that library search LIKEs against. */
    suspend fun refreshSearchBlob(dishId: Long) {
        val recipe = recipeDao.recipeById(dishId) ?: return
        val parts = buildList {
            add(recipe.title)
            recipe.notes?.let(::add)
            addAll(recipeDao.ingredientsFor(dishId).map { it.rawName })
            addAll(tagDao.tagsFor(dishId).map { it.name })
        }
        recipeDao.setSearchBlob(dishId, parts.joinToString(" ").lowercase())
    }

    // ---- Pantry interplay ----------------------------------------------------------

    suspend fun linkIngredientToPantry(ingredientId: Long, pantryItemId: Long?) {
        val ingredient = recipeDao.ingredientById(ingredientId) ?: return
        recipeDao.updateIngredient(ingredient.copy(pantryItemId = pantryItemId))
    }

    /** Mark a recipe ingredient as run out: flips the pantry item and adds it to shopping. */
    suspend fun markIngredientRanOut(ingredientId: Long) =
        applyIngredientStatus(ingredientId, StockStatus.OUT)

    /** Mark a recipe ingredient as back in stock. */
    suspend fun markIngredientHave(ingredientId: Long) =
        applyIngredientStatus(ingredientId, StockStatus.IN_STOCK)

    private suspend fun applyIngredientStatus(ingredientId: Long, status: StockStatus) {
        val ingredient = recipeDao.ingredientById(ingredientId) ?: return
        val pantrySnapshot = pantryDao.getAll()
        val (match, _) = PantryResolver.resolve(
            ingredient.normalizedName,
            ingredient.pantryItemId,
            pantrySnapshot,
        )
        val item = match ?: run {
            // The recipe mentions something the pantry has never heard of: teach it, and
            // remember the link so the next tap resolves straight away.
            val newItem = PantryItem(
                name = ingredient.rawName,
                normalizedName = ingredient.normalizedName,
                category = CategoryGuesser.guess(ingredient.rawName),
                status = status,
            )
            val newId = pantryDao.upsert(newItem)
            val saved = newItem.copy(id = newId)
            if (ingredient.pantryItemId != saved.id) {
                recipeDao.updateIngredient(ingredient.copy(pantryItemId = saved.id))
            }
            syncShopping(saved)
            return
        }
        pantryDao.setStatus(item.id, status, System.currentTimeMillis())
        syncShopping(item.copy(status = status))
    }

    suspend fun addMissingIngredientsToShopping(dishId: Long) {
        val recipe = recipeDao.recipeWithDetailsOnce(dishId) ?: return
        val pantrySnapshot = pantryDao.getAll()
        recipe.ingredients.forEach { ingredient ->
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

    private suspend fun syncShopping(item: PantryItem) {
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

    // ---- TheMealDB search ----------------------------------------------------------

    suspend fun searchMeals(query: String): List<MealSummary> =
        if (query.isBlank()) emptyList() else mealDb.searchByName(query)

    suspend fun mealDetail(id: String): MealDetail? = mealDb.lookup(id)
}
