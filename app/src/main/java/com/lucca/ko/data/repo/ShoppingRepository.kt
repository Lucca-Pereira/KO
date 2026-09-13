package com.lucca.ko.data.repo

import com.lucca.ko.data.db.PantryItem
import com.lucca.ko.data.db.ShoppingListItem
import com.lucca.ko.data.db.StockStatus
import com.lucca.ko.data.db.dao.PantryDao
import com.lucca.ko.data.db.dao.ShoppingDao
import com.lucca.ko.domain.CategoryGuesser
import com.lucca.ko.domain.IngredientMatcher
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
                ),
            )
        }
    }

    suspend fun deleteShoppingItem(id: Long) = shoppingDao.delete(id)

    suspend fun clearCheckedShopping() = shoppingDao.clearChecked()
}
