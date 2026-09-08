package com.lucca.ko.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun stockToString(s: StockStatus): String = s.name
    @TypeConverter fun stringToStock(s: String): StockStatus = StockStatus.valueOf(s)

    @TypeConverter fun slotToString(s: MealSlot): String = s.name
    @TypeConverter fun stringToSlot(s: String): MealSlot = MealSlot.valueOf(s)
}

@Database(
    entities = [
        PantryItem::class,
        Dish::class,
        DishIngredient::class,
        MealPlanEntry::class,
        ShoppingListItem::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class KoDatabase : RoomDatabase() {
    abstract fun pantryDao(): PantryDao
    abstract fun dishDao(): DishDao
    abstract fun mealPlanDao(): MealPlanDao
    abstract fun shoppingDao(): ShoppingDao

    companion object {
        @Volatile private var instance: KoDatabase? = null

        fun get(context: Context): KoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                KoDatabase::class.java,
                "ko.db",
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
