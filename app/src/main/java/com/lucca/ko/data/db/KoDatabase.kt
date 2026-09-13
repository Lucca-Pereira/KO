package com.lucca.ko.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KoDatabase : RoomDatabase() {
    abstract fun pantryDao(): PantryDao
    abstract fun dishDao(): DishDao
    abstract fun mealPlanDao(): MealPlanDao
    abstract fun shoppingDao(): ShoppingDao

    companion object {
        @Volatile private var instance: KoDatabase? = null

        /** v1 -> v2: add the English search alias column to pantry_items. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pantry_items ADD COLUMN searchName TEXT")
            }
        }

        fun get(context: Context): KoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                KoDatabase::class.java,
                "ko.db",
            )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
