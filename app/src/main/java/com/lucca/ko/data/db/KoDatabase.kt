package com.lucca.ko.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.lucca.ko.data.db.dao.MealPlanDao
import com.lucca.ko.data.db.dao.PantryDao
import com.lucca.ko.data.db.dao.RecipeDao
import com.lucca.ko.data.db.dao.ShoppingDao
import com.lucca.ko.data.db.dao.TagDao

class Converters {
    @TypeConverter fun stockToString(s: StockStatus): String = s.name
    @TypeConverter fun stringToStock(s: String): StockStatus = StockStatus.valueOf(s)

    @TypeConverter fun slotToString(s: MealSlot): String = s.name
    @TypeConverter fun stringToSlot(s: String): MealSlot = MealSlot.valueOf(s)

    @TypeConverter fun recipeSourceToString(s: RecipeSource?): String? = s?.name
    @TypeConverter fun stringToRecipeSource(s: String?): RecipeSource? =
        s?.let { runCatching { RecipeSource.valueOf(it) }.getOrDefault(RecipeSource.MANUAL) }

    @TypeConverter fun macroSourceToString(s: MacroSource?): String? = s?.name
    @TypeConverter fun stringToMacroSource(s: String?): MacroSource? =
        s?.let { runCatching { MacroSource.valueOf(it) }.getOrNull() }
}

@Database(
    entities = [
        PantryItem::class,
        Recipe::class,
        RecipeIngredient::class,
        RecipeStep::class,
        Tag::class,
        RecipeTag::class,
        MealPlanEntry::class,
        ShoppingListItem::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KoDatabase : RoomDatabase() {
    abstract fun pantryDao(): PantryDao
    abstract fun recipeDao(): RecipeDao
    abstract fun tagDao(): TagDao
    abstract fun mealPlanDao(): MealPlanDao
    abstract fun shoppingDao(): ShoppingDao

    companion object {
        @Volatile private var instance: KoDatabase? = null

        fun get(context: Context): KoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                KoDatabase::class.java,
                "ko.db",
            )
                .addMigrations(*KO_MIGRATIONS)
                // Deliberately no fallbackToDestructiveMigration(). A missing migration now
                // crashes on launch, which is the correct behaviour: the alternative silently
                // deletes the pantry. Every migration ships behind MigrationTest.
                .build()
                .also { instance = it }
        }
    }
}
