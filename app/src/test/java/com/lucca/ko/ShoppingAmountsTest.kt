package com.lucca.ko

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lucca.ko.data.db.KoDatabase
import com.lucca.ko.data.repo.ShoppingRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Amounts on the shopping list, against a real in-memory Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShoppingAmountsTest {

    private lateinit var db: KoDatabase
    private lateinit var repo: ShoppingRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KoDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ShoppingRepository(db.shoppingDao(), db.pantryDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun only() = db.shoppingDao().getAll().single()

    @Test
    fun `typing an amount stores it and keeps the name clean`() = runTest {
        repo.addManualShoppingItem("2 kg potatoes")
        assertEquals("potatoes", only().name)
        assertEquals("2 kg", only().quantity)
    }

    @Test
    fun `adding the same item again adds the amounts`() = runTest {
        repo.addManualShoppingItem("2 kg potatoes")
        repo.addManualShoppingItem("1 kg potatoes")
        assertEquals("3 kg", only().quantity)
    }

    @Test
    fun `adding an item with no amount to an existing one changes nothing`() = runTest {
        repo.addManualShoppingItem("2 kg potatoes")
        repo.addManualShoppingItem("potatoes")
        assertEquals("2 kg", only().quantity)
    }

    @Test
    fun `an amount can be set, changed and cleared`() = runTest {
        repo.addManualShoppingItem("milk")
        assertNull(only().quantity)
        repo.setShoppingQuantity(only(), " 2 l ")
        assertEquals("2 l", only().quantity)
        repo.setShoppingQuantity(only(), "")
        assertNull(only().quantity)
    }
}
