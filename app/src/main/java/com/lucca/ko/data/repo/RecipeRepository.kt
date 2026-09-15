package com.lucca.ko.data.repo

import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.Recipe
import com.lucca.ko.data.db.RecipeIngredient
import com.lucca.ko.data.db.RecipeSource
import com.lucca.ko.data.db.RecipeTag
import com.lucca.ko.data.db.ShoppingListItem
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.data.db.Tag
import com.lucca.ko.data.db.dao.MealPlanDao
import com.lucca.ko.data.db.dao.PantryDao
import com.lucca.ko.data.db.dao.RecipeDao
import com.lucca.ko.data.db.dao.ShoppingDao
import com.lucca.ko.data.db.dao.TagDao
import com.lucca.ko.data.db.relations.RecipeWithDetails
import com.lucca.ko.domain.Availability
import com.lucca.ko.domain.CategoryGuesser
import com.lucca.ko.domain.IngredientMatcher
import com.lucca.ko.domain.PantryResolver
import com.lucca.ko.domain.recipe.RecipeDraft
import com.lucca.ko.domain.recipe.toIngredients
import com.lucca.ko.domain.recipe.toRecipe
import com.lucca.ko.domain.recipe.toSteps
import com.lucca.ko.domain.units.MeasureParser
import kotlinx.coroutines.flow.Flow

/** Recipes sharing a normalised title. */
data class DuplicateGroup(val normalizedTitle: String, val recipes: List<Recipe>)

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
    private val mealPlanDao: MealPlanDao,
) {
    fun observeRecipe(id: Long): Flow<RecipeWithDetails?> = recipeDao.observeRecipe(id)

    fun searchLibrary(query: String): Flow<List<RecipeWithDetails>> =
        recipeDao.searchLibrary(query.trim())

    val tags: Flow<List<Tag>> = tagDao.observeAll()

    suspend fun recipeById(id: Long): Recipe? = recipeDao.recipeById(id)

    // ---- Creating ------------------------------------------------------------------

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
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
            .forEach { name ->
                val normalized = IngredientMatcher.normalize(name).ifBlank { name.lowercase() }
                // insert() is IGNORE-on-conflict, so it returns -1 for a tag that already
                // exists; fall back to looking it up rather than dropping the link.
                val tagId = tagDao.insert(Tag(name = name, normalizedName = normalized))
                    .takeIf { it > 0 }
                    ?: tagDao.byNormalized(normalized)?.id
                    ?: return@forEach
                tagDao.link(RecipeTag(dishId = dishId, tagId = tagId))
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

    /**
     * Saves everything the editor holds in one go: the recipe, its ingredients, its steps and
     * its tags. Ingredients and steps are replaced wholesale rather than diffed — see
     * [RecipeDao.replaceIngredients].
     *
     * Returns the recipe id, which is newly allocated when the draft was new.
     */
    suspend fun saveDraft(draft: RecipeDraft): Long {
        val now = System.currentTimeMillis()
        val recipe = draft.toRecipe(now)
        val id = if (draft.isNew) {
            recipeDao.insertRecipe(recipe)
        } else {
            recipeDao.updateRecipe(recipe)
            draft.id
        }
        recipeDao.replaceIngredients(id, draft.toIngredients(id))
        recipeDao.replaceSteps(id, draft.toSteps(id))
        setTags(id, draft.tags)
        // A rename must not leave the plan showing the old name in its fallback snapshot.
        mealPlanDao.refreshTitleSnapshots(id, recipe.title)
        refreshSearchBlob(id)
        return id
    }

    // ---- Duplicates ----------------------------------------------------------------

    /**
     * Recipes that share a normalised title, for the Find duplicates tool.
     *
     * The 2 -> 3 migration already collapsed MealDB duplicates automatically, because a shared
     * `mealdbId` is unambiguous. Titles are not: two homemade "Pasta" recipes may be genuinely
     * different, so these are surfaced for a human decision and never merged on their own.
     */
    suspend fun findDuplicates(): List<DuplicateGroup> =
        recipeDao.allRecipesForDuplicateScan()
            .groupBy { IngredientMatcher.normalize(it.title).ifBlank { it.title.trim().lowercase() } }
            .filter { (key, group) -> key.isNotBlank() && group.size > 1 }
            .map { (key, group) -> DuplicateGroup(key, group.sortedBy { it.id }) }
            .sortedBy { it.recipes.first().title.lowercase() }

    /**
     * Merges [dropIds] into [keepId]: plan entries are repointed, tags are unioned, and the
     * losers are deleted. Ingredients and steps are *not* merged — the kept recipe is assumed to
     * be the good one, which is why the UI shows both side by side before you choose.
     */
    suspend fun mergeRecipes(keepId: Long, dropIds: List<Long>) {
        val keeper = recipeDao.recipeById(keepId) ?: return
        val keptTagIds = tagDao.tagIdsFor(keepId).toMutableSet()
        dropIds.filter { it != keepId }.forEach { dropId ->
            tagDao.tagIdsFor(dropId).forEach { tagId ->
                if (keptTagIds.add(tagId)) {
                    tagDao.link(RecipeTag(dishId = keepId, tagId = tagId))
                }
            }
            mealPlanDao.repointRecipe(from = dropId, to = keepId, title = keeper.title)
            recipeDao.deleteRecipe(dropId)
        }
        tagDao.deleteUnusedTags()
        refreshSearchBlob(keepId)
    }

    /** How many planned meals point at this recipe — shown before deleting it. */
    suspend fun planCountFor(recipeId: Long): Int = mealPlanDao.countForRecipe(recipeId)

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
}
