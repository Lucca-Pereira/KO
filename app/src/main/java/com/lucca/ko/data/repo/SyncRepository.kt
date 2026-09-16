package com.lucca.ko.data.repo

import com.lucca.ko.data.db.MealPlanEntry
import com.lucca.ko.data.db.MealSlot
import com.lucca.ko.data.db.Recipe
import com.lucca.ko.data.db.RecipeSource
import com.lucca.ko.data.db.ShoppingListItem
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.data.prefs.SyncSettingsRepository
import com.lucca.ko.data.remote.sync.KoSyncClient
import com.lucca.ko.data.remote.sync.MealPlanEntryWire
import com.lucca.ko.data.remote.sync.PantryItemWire
import com.lucca.ko.data.remote.sync.RecipeIngredientWire
import com.lucca.ko.data.remote.sync.RecipeStepWire
import com.lucca.ko.data.remote.sync.RecipeWire
import com.lucca.ko.data.remote.sync.ShoppingItemWire
import com.lucca.ko.data.remote.sync.SyncPush
import com.lucca.ko.domain.CategoryGuesser
import com.lucca.ko.domain.IngredientMatcher
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

sealed interface SyncOutcome {
    data object NotConfigured : SyncOutcome
    data class Success(val pushed: Int, val pulled: Int, val skipped: List<String>) : SyncOutcome
    data class Failed(val message: String) : SyncOutcome
}

/**
 * Push-then-pull sync with the NAS, in one HTTP round trip: see `POST /v1/sync` on
 * `server/ko_sync/api/rest.py` for the other end of this.
 *
 * Reuses the exact same safety nets [AgentImportRepository] already relies on rather than
 * reimplementing them — [RecipeMerge] for the snapshot-then-save recipe write, and
 * [PantryRepository.savePantryItem]'s existing by-name fallback for the pantry unique index.
 * `remoteId` is always assigned by the phone (at row-creation time for anything created after
 * this feature landed; lazily here, before the network call, for anything older) — never by the
 * server — so a push whose response is lost to a dropped connection retries as a no-op the second
 * time instead of creating a duplicate row server-side.
 */
class SyncRepository(
    private val recipeRepository: RecipeRepository,
    private val pantryRepository: PantryRepository,
    private val shoppingRepository: ShoppingRepository,
    private val mealPlanRepository: MealPlanRepository,
    private val recipeMerge: RecipeMerge,
    private val syncSettingsRepository: SyncSettingsRepository,
    private val koSyncClient: KoSyncClient,
) {
    suspend fun sync(): SyncOutcome {
        val nasUrl = syncSettingsRepository.currentNasUrl()
        val token = syncSettingsRepository.currentToken()
        if (nasUrl.isNullOrBlank() || token.isNullOrBlank()) return SyncOutcome.NotConfigured

        val lastSyncedAt = syncSettingsRepository.currentLastSyncedAt()

        val pendingRecipes = recipeRepository.pendingSyncPush()
        val recipeWires = pendingRecipes.mapNotNull { buildRecipeWire(it) }

        val pendingPantry = pantryRepository.pendingSyncPush()
        val pantryWires = pendingPantry.map { item ->
            val remoteId = item.remoteId ?: freshId { pantryRepository.setRemoteId(item.id, it) }
            PantryItemWire(remoteId, item.name, item.status.name, item.category, item.quantity, item.note, item.updatedAt)
        }

        val pendingShopping = shoppingRepository.pendingSyncPush()
        val shoppingWires = pendingShopping.map { item ->
            val remoteId = item.remoteId ?: freshId { shoppingRepository.setRemoteId(item.id, it) }
            ShoppingItemWire(remoteId, item.name)
        }

        val pendingPlan = mealPlanRepository.pendingSyncPush()
        val planWires = pendingPlan.map { entry ->
            val remoteId = entry.remoteId ?: freshId { mealPlanRepository.setRemoteId(entry.id, it) }
            val title = entry.dishId?.let { recipeRepository.recipeById(it)?.title } ?: entry.titleSnapshot
            MealPlanEntryWire(remoteId, title, entry.date, entry.slot.name, entry.servings)
        }

        val response = try {
            koSyncClient.sync(
                nasUrl,
                lastSyncedAt,
                SyncPush(recipeWires, pantryWires, shoppingWires, planWires),
                token,
            )
        } catch (e: CancellationException) {
            throw e // structured concurrency needs this to keep propagating, not get swallowed
        } catch (e: Exception) {
            // Anything the network can throw (connection refused, DNS failure, a timeout, TLS/
            // cleartext rejection) lands here rather than crashing the caller — a manual "Sync
            // now" tap has no other safety net, unlike the foreground trigger's own runCatching.
            return SyncOutcome.Failed(e.message ?: "Sync failed: ${e::class.simpleName}")
        }

        // Anything we just pushed will echo back in the pull if it's now the newest version
        // server-side, which by construction it always is for a row nothing else is touching —
        // applying it again would just be a wasted no-op write (and a spurious undo snapshot for
        // a recipe), so skip pull entries whose remoteId we pushed this round.
        val justPushed = (recipeWires.map { it.remoteId } + pantryWires.map { it.remoteId } +
            shoppingWires.map { it.remoteId } + planWires.map { it.remoteId }).toSet()

        val skipped = mutableListOf<String>()
        var pulled = 0

        response.recipes.filterNot { it.remoteId in justPushed }.forEach { wire ->
            runCatching { applyPulledRecipe(wire, response.serverTime) }
                .onSuccess { pulled++ }
                .onFailure { skipped += "recipe \"${wire.title}\": ${it.message}" }
        }
        response.pantryItems.filterNot { it.remoteId in justPushed }.forEach { wire ->
            runCatching { applyPulledPantry(wire, response.serverTime) }
                .onSuccess { pulled++ }
                .onFailure { skipped += "pantry \"${wire.name}\": ${it.message}" }
        }
        response.shoppingItems.filterNot { it.remoteId in justPushed }.forEach { wire ->
            runCatching { applyPulledShopping(wire, response.serverTime) }
                .onSuccess { pulled++ }
                .onFailure { skipped += "shopping \"${wire.name}\": ${it.message}" }
        }
        response.mealPlanEntries.filterNot { it.remoteId in justPushed }.forEach { wire ->
            runCatching { applyPulledPlan(wire, response.serverTime) }
                .onSuccess { pulled++ }
                .onFailure { skipped += "plan \"${wire.recipeTitle}\": ${it.message}" }
        }

        // Only after the pull is fully applied do we mark what we pushed as synced — a failure
        // partway through leaves those rows unstamped, so the next attempt retries them safely.
        pendingRecipes.forEach { recipeRepository.stampSynced(it.id, response.serverTime) }
        pendingPantry.forEach { pantryRepository.stampSynced(it.id, response.serverTime) }
        pendingShopping.forEach { shoppingRepository.stampSynced(it.id, response.serverTime) }
        pendingPlan.forEach { mealPlanRepository.stampSynced(it.id, response.serverTime) }
        syncSettingsRepository.setLastSyncedAt(response.serverTime)

        val pushed = recipeWires.size + pantryWires.size + shoppingWires.size + planWires.size
        return SyncOutcome.Success(pushed, pulled, skipped)
    }

    /** Generates a remoteId and persists it immediately — before this id is ever sent over the
     *  network, so a retried push always carries the same id instead of minting a new one. */
    private suspend fun freshId(persist: suspend (String) -> Unit): String =
        UUID.randomUUID().toString().also { persist(it) }

    private suspend fun buildRecipeWire(recipe: Recipe): RecipeWire? {
        val remoteId = recipe.remoteId ?: UUID.randomUUID().toString().also {
            recipeRepository.setRemoteId(recipe.id, it)
        }
        val details = recipeRepository.detailsOnce(recipe.id) ?: return null
        return RecipeWire(
            remoteId = remoteId,
            title = recipe.title,
            servings = recipe.servings,
            prepMinutes = recipe.prepMinutes,
            cookMinutes = recipe.cookMinutes,
            notes = recipe.notes,
            tags = details.tags.map { it.name },
            kcalPerServing = recipe.kcalPerServing,
            proteinG = recipe.proteinG,
            carbsG = recipe.carbsG,
            fatG = recipe.fatG,
            macroNote = recipe.macroNote,
            ingredients = details.orderedIngredients.map {
                RecipeIngredientWire(it.rawName, it.measure.orEmpty(), it.optional)
            },
            steps = details.orderedSteps.map { RecipeStepWire(it.text, it.minutes) },
            updatedAt = recipe.updatedAt,
        )
    }

    private suspend fun applyPulledRecipe(wire: RecipeWire, serverTime: Long) {
        val existing = recipeRepository.recipeByRemoteId(wire.remoteId)
        val id = recipeMerge.apply(
            existingId = existing?.id,
            fields = RecipeFields(
                title = wire.title,
                servings = wire.servings,
                prepMinutes = wire.prepMinutes,
                cookMinutes = wire.cookMinutes,
                notes = wire.notes,
                tags = wire.tags,
                kcalPerServing = wire.kcalPerServing,
                proteinG = wire.proteinG,
                carbsG = wire.carbsG,
                fatG = wire.fatG,
                macroNote = wire.macroNote,
                ingredients = wire.ingredients.map { RecipeFieldIngredient(it.name, it.amount, it.optional) },
                steps = wire.steps.map { RecipeFieldStep(it.text, it.minutes) },
            ),
            source = RecipeSource.AI,
            reason = "NAS sync",
        )
        recipeRepository.stampSync(id, wire.remoteId, wire.updatedAt, serverTime)
    }

    private suspend fun applyPulledPantry(wire: PantryItemWire, serverTime: Long) {
        val existing = pantryRepository.pantryByRemoteId(wire.remoteId)
        // savePantryItem falls back to matching by normalized name when id is null — the same
        // safety net that keeps AgentImportRepository's pantry apply from crashing on the
        // normalizedName unique index when a row with this name already exists under a different
        // (or no) remoteId.
        val id = pantryRepository.savePantryItem(
            id = existing?.id,
            name = wire.name,
            category = wire.category ?: CategoryGuesser.guess(wire.name),
            status = runCatching { StockStatus.valueOf(wire.status) }.getOrDefault(StockStatus.IN_STOCK),
            quantity = wire.quantity,
            note = wire.note,
        ) ?: return
        pantryRepository.stampSync(id, wire.remoteId, wire.updatedAt, serverTime)
    }

    private suspend fun applyPulledShopping(wire: ShoppingItemWire, serverTime: Long) {
        val existingByRemote = shoppingRepository.shoppingByRemoteId(wire.remoteId)
        if (existingByRemote != null) {
            shoppingRepository.stampSync(existingByRemote.id, wire.remoteId, serverTime)
            return
        }
        val normalized = IngredientMatcher.normalize(wire.name)
        val insertedId = shoppingRepository.insertFromSync(
            ShoppingListItem(
                name = wire.name,
                normalizedName = normalized,
                category = CategoryGuesser.guess(wire.name),
                remoteId = wire.remoteId,
                syncedAt = serverTime,
            ),
        )
        if (insertedId <= 0) {
            // insertIgnore's conflict path: a row with this name already exists locally under a
            // different remoteId (or none yet) — adopt the NAS's id onto it instead of dropping
            // the update, same reasoning as the pantry merge above.
            val existingByName = shoppingRepository.shoppingByNormalized(normalized) ?: return
            shoppingRepository.stampSync(existingByName.id, wire.remoteId, serverTime)
        }
    }

    private suspend fun applyPulledPlan(wire: MealPlanEntryWire, serverTime: Long) {
        val existingByRemote = mealPlanRepository.planByRemoteId(wire.remoteId)
        if (existingByRemote != null) {
            mealPlanRepository.stampSync(existingByRemote.id, wire.remoteId, serverTime)
            return
        }
        val matches = recipeRepository.searchLibrary(wire.recipeTitle).first()
        val dishId = matches.firstOrNull { it.recipe.title.equals(wire.recipeTitle, ignoreCase = true) }?.recipe?.id
            ?: matches.firstOrNull()?.recipe?.id
            ?: error("no recipe called \"${wire.recipeTitle}\" in the library yet")
        val slot = runCatching { MealSlot.valueOf(wire.slot) }.getOrDefault(MealSlot.DINNER)
        val title = recipeRepository.recipeById(dishId)?.title.orEmpty()
        mealPlanRepository.insertFromSync(
            MealPlanEntry(
                date = wire.date,
                slot = slot,
                dishId = dishId,
                titleSnapshot = title,
                servings = wire.servings ?: 1.0,
                remoteId = wire.remoteId,
                syncedAt = serverTime,
            ),
        )
    }
}
