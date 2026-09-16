package com.lucca.ko.data.repo

import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.ShoppingListItem
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.data.db.dao.PantryDao
import com.lucca.ko.data.db.dao.ShoppingDao
import com.lucca.ko.domain.CategoryGuesser
import com.lucca.ko.domain.IngredientMatcher
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** The shopping list, and the pantry writes that ticking an item off implies. */
class ShoppingRepository(
    private val shoppingDao: ShoppingDao,
    private val pantryDao: PantryDao,
) {
    val shoppingItems: Flow<List<ShoppingListItem>> = shoppingDao.observeAll()

    suspend fun addManualShoppingItem(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        shoppingDao.insertIgnore(
            ShoppingListItem(
                name = clean,
                normalizedName = IngredientMatcher.normalize(clean),
                category = CategoryGuesser.guess(clean),
                remoteId = UUID.randomUUID().toString(),
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
            // Ticking off something the pantry has never heard of teaches it the item.
            pantryDao.upsert(
                PantryItem(
                    name = item.name,
                    normalizedName = item.normalizedName,
                    category = item.category,
                    status = StockStatus.IN_STOCK,
                    remoteId = UUID.randomUUID().toString(),
                ),
            )
        }
    }

    suspend fun deleteShoppingItem(id: Long) = shoppingDao.delete(id)

    suspend fun clearCheckedShopping() = shoppingDao.clearChecked()

    // ---- Sync ------------------------------------------------------------------------

    suspend fun shoppingByRemoteId(remoteId: String): ShoppingListItem? = shoppingDao.byRemoteId(remoteId)

    suspend fun shoppingByNormalized(normalized: String): ShoppingListItem? = shoppingDao.byNormalized(normalized)

    suspend fun pendingSyncPush(): List<ShoppingListItem> = shoppingDao.pendingPush()

    /**
     * Inserts a row pulled from the NAS, already carrying its remoteId — unlike
     * [addManualShoppingItem], which is for the user typing a name and always mints a fresh one.
     * `insertIgnore`'s conflict handling is what keeps this from crashing if [item]'s normalized
     * name collides with a row that already exists locally under a different remoteId; on an
     * ignore, the caller falls back to [shoppingByNormalized] and stamps that row instead.
     */
    suspend fun insertFromSync(item: ShoppingListItem): Long = shoppingDao.insertIgnore(item)

    suspend fun stampSynced(id: Long, syncedAt: Long) = shoppingDao.stampSynced(id, syncedAt)

    suspend fun stampSync(id: Long, remoteId: String, syncedAt: Long) =
        shoppingDao.stampSync(id, remoteId, syncedAt)

    suspend fun setRemoteId(id: Long, remoteId: String) = shoppingDao.setRemoteId(id, remoteId)
}
