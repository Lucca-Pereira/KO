package com.lucca.ko.data.repo

import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.ShoppingListItem
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.data.db.dao.PantryDao
import com.lucca.ko.data.db.dao.ShoppingDao
import com.lucca.ko.domain.IngredientMatcher
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * The pantry, and the shopping-list rows the pantry owns.
 *
 * Repositories hold DAOs, never other repositories: the shopping sync below writes through
 * [ShoppingDao] directly rather than depending on [ShoppingRepository], which is what keeps the
 * dependency graph flat now that the old god object is gone.
 */
class PantryRepository(
    private val pantryDao: PantryDao,
    private val shoppingDao: ShoppingDao,
) {
    val pantry: Flow<List<PantryItem>> = pantryDao.observeAll()

    suspend fun snapshot(): List<PantryItem> = pantryDao.getAll()

    suspend fun byId(id: Long): PantryItem? = pantryDao.byId(id)

    // ---- Sync ------------------------------------------------------------------------

    suspend fun pantryByRemoteId(remoteId: String): PantryItem? = pantryDao.byRemoteId(remoteId)

    suspend fun pendingSyncPush(): List<PantryItem> = pantryDao.pendingPush()

    suspend fun stampSynced(id: Long, syncedAt: Long) = pantryDao.stampSynced(id, syncedAt)

    suspend fun stampSync(id: Long, remoteId: String, updatedAt: Long, syncedAt: Long) =
        pantryDao.stampSync(id, remoteId, updatedAt, syncedAt)

    suspend fun setRemoteId(id: Long, remoteId: String) = pantryDao.setRemoteId(id, remoteId)

    /** Returns the saved row's id — callers that need to stamp sync metadata onto it want this. */
    suspend fun savePantryItem(
        id: Long?,
        name: String,
        category: String,
        status: StockStatus,
        quantity: String?,
        note: String?,
    ): Long? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val normalized = IngredientMatcher.normalize(clean)
        val existing = if (id != null) pantryDao.byId(id) else pantryDao.byNormalized(normalized)
        val fresh = PantryItem(name = clean, normalizedName = normalized, remoteId = UUID.randomUUID().toString())
        val item = (existing ?: fresh).copy(
            name = clean,
            normalizedName = normalized,
            category = category,
            status = status,
            quantity = quantity?.trim()?.ifEmpty { null },
            note = note?.trim()?.ifEmpty { null },
            updatedAt = System.currentTimeMillis(),
        )
        val savedId = pantryDao.upsert(item)
        val finalId = if (item.id != 0L) item.id else savedId
        syncShoppingForPantry(item.copy(id = finalId))
        return finalId
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

    suspend fun setSearchName(item: PantryItem, english: String) {
        pantryDao.update(item.copy(searchName = english))
    }

    /**
     * Keeps the shopping list in step with a pantry item's status: running out adds it, having
     * it again removes the row unless it has already been ticked off.
     */
    suspend fun syncShoppingForPantry(item: PantryItem) {
        if (item.status == StockStatus.OUT) {
            if (shoppingDao.byNormalized(item.normalizedName) == null) {
                shoppingDao.insertIgnore(
                    ShoppingListItem(
                        name = item.name,
                        normalizedName = item.normalizedName,
                        category = item.category,
                        pantryItemId = item.id.takeIf { it != 0L },
                        remoteId = UUID.randomUUID().toString(),
                    ),
                )
            }
        } else {
            shoppingDao.deleteUncheckedByNormalized(item.normalizedName)
        }
    }
}
