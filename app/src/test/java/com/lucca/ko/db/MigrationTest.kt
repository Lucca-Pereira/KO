package com.lucca.ko.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.lucca.ko.data.db.KoDatabase
import com.lucca.ko.data.db.MIGRATION_2_3
import com.lucca.ko.data.db.MIGRATION_3_4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TEST_DB = "migration-test.db"

/**
 * The highest-value test in the project: the 2 -> 3 migration is the only one that touches rows
 * that already exist on the phone, and there is real pantry data on the other side of it.
 *
 * Runs under Robolectric in `src/test` rather than as an instrumented test, so CI's
 * `testDebugUnitTest` actually executes it. Schemas are read from `app/schemas`, which is on the
 * debug asset source set (see app/build.gradle.kts for why it is `debug` and not `test`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KoDatabase::class.java,
    )

    @Test
    fun migrate2To3_validatesAgainstTheEntities() {
        helper.createDatabase(TEST_DB, 2).use { it.seedV2() }
        // validateDroppedTables = true runs Room's full TableInfo comparison — columns, types,
        // NOT NULL, DEFAULTs, foreign keys and index names. This is what catches a missing
        // @ColumnInfo(defaultValue = ...) that would otherwise blow up on the user's phone.
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).close()
    }

    @Test
    fun migrate2To3_preservesThePantryAndShoppingList() {
        helper.createDatabase(TEST_DB, 2).use { it.seedV2() }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            assertEquals(3, db.count("SELECT COUNT(*) FROM pantry_items"))
            assertEquals(2, db.count("SELECT COUNT(*) FROM shopping_items"))
            // The accented row keeps the normalized name it was stored with; repairing that is
            // StartupRepairs' job, not the migration's.
            assertEquals(
                1,
                db.count("SELECT COUNT(*) FROM pantry_items WHERE name = 'Orégano'"),
            )
        }
    }

    @Test
    fun migrate2To3_collapsesDuplicateMealdbRecipesAndRepointsThePlan() {
        helper.createDatabase(TEST_DB, 2).use { it.seedV2() }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            // Seeded: two dishes with mealdbId '52772' plus one manual dish.
            assertEquals(2, db.count("SELECT COUNT(*) FROM dishes"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM dishes WHERE mealdbId = '52772'"))
            // The survivor is the lowest id, matching MIN(id) in the SQL.
            assertEquals(1, db.count("SELECT COUNT(*) FROM dishes WHERE id = 1"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM dishes WHERE id = 2"))

            // Every plan row survives, and both rows that pointed at the duplicates now agree.
            assertEquals(3, db.count("SELECT COUNT(*) FROM meal_plan"))
            assertEquals(1L, db.long("SELECT dishId FROM meal_plan WHERE id = 1"))
            assertEquals(1L, db.long("SELECT dishId FROM meal_plan WHERE id = 2"))
            // The manual dish's entry is untouched — that is what the COALESCE protects.
            assertEquals(3L, db.long("SELECT dishId FROM meal_plan WHERE id = 3"))

            // The loser's ingredients went with it; the survivor's are intact.
            assertEquals(0, db.count("SELECT COUNT(*) FROM dish_ingredients WHERE dishId = 2"))
            assertEquals(2, db.count("SELECT COUNT(*) FROM dish_ingredients WHERE dishId = 1"))
        }
    }

    @Test
    fun migrate2To3_fillsTitleSnapshotsAndDefaults() {
        helper.createDatabase(TEST_DB, 2).use { it.seedV2() }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            assertEquals(
                0,
                db.count("SELECT COUNT(*) FROM meal_plan WHERE titleSnapshot = ''"),
            )
            assertEquals("Teriyaki Chicken", db.text("SELECT titleSnapshot FROM meal_plan WHERE id = 1"))
            assertEquals("Abuela's stew", db.text("SELECT titleSnapshot FROM meal_plan WHERE id = 3"))
            assertEquals(3, db.count("SELECT COUNT(*) FROM meal_plan WHERE servings = 1.0"))
            assertEquals(3, db.count("SELECT COUNT(*) FROM meal_plan WHERE cooked = 0"))

            // updatedAt is backfilled from createdAt, and MealDB rows are tagged as such.
            assertEquals(0, db.count("SELECT COUNT(*) FROM dishes WHERE updatedAt <> createdAt"))
            assertEquals("MEALDB", db.text("SELECT source FROM dishes WHERE id = 1"))
            assertEquals("MANUAL", db.text("SELECT source FROM dishes WHERE id = 3"))
            assertEquals(2, db.count("SELECT COUNT(*) FROM dishes WHERE servings = 2"))
        }
    }

    @Test
    fun migrate2To3_ordersIngredientsAsTheyWereInserted() {
        helper.createDatabase(TEST_DB, 2).use { it.seedV2() }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT rawName FROM dish_ingredients WHERE dishId = 1 ORDER BY sortOrder").use { c ->
                c.moveToFirst()
                assertEquals("chicken", c.getString(0))
                c.moveToNext()
                assertEquals("soy sauce", c.getString(0))
            }
            // sortOrder is seeded from the id, so it is strictly increasing and sparse.
            assertEquals(0, db.count("SELECT COUNT(*) FROM dish_ingredients WHERE sortOrder = 0"))
        }
    }

    @Test
    fun migrate2To3_makesDuplicateMealdbImportsImpossible() {
        helper.createDatabase(TEST_DB, 2).use { it.seedV2() }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            val threw = runCatching {
                db.execSQL(
                    "INSERT INTO dishes (title, mealdbId, createdAt, servings, isFavourite, " +
                        "updatedAt, timesCooked, source) " +
                        "VALUES ('Teriyaki again', '52772', 1, 2, 0, 1, 0, 'MEALDB')",
                )
            }.isFailure
            assertTrue("the unique index on mealdbId should reject a second import", threw)

            // But NULLs stay distinct, so any number of manual recipes coexist.
            db.execSQL(
                "INSERT INTO dishes (title, createdAt, servings, isFavourite, updatedAt, " +
                    "timesCooked, source) VALUES ('Another stew', 1, 2, 0, 1, 0, 'MANUAL')",
            )
            db.execSQL(
                "INSERT INTO dishes (title, createdAt, servings, isFavourite, updatedAt, " +
                    "timesCooked, source) VALUES ('Third stew', 1, 2, 0, 1, 0, 'MANUAL')",
            )
            assertEquals(4, db.count("SELECT COUNT(*) FROM dishes"))
        }
    }

    @Test
    fun migrate2To3_deletingARecipeKeepsThePlanEntry() {
        helper.createDatabase(TEST_DB, 2).use { it.seedV2() }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            // Room enables foreign keys on a real connection; the test helper's does not, so
            // turn them on to exercise the ON DELETE SET NULL that replaced CASCADE.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM dishes WHERE id = 3")

            assertEquals(3, db.count("SELECT COUNT(*) FROM meal_plan"))
            db.query("SELECT dishId, titleSnapshot FROM meal_plan WHERE id = 3").use { c ->
                c.moveToFirst()
                assertTrue("dishId should be nulled, not cascaded away", c.isNull(0))
                assertEquals("Abuela's stew", c.getString(1))
            }
            assertNull(db.longOrNull("SELECT dishId FROM meal_plan WHERE id = 3"))
        }
    }

    @Test
    fun migrate2To3_survivesAnEmptyDatabase() {
        // A fresh v1 install upgraded twice in a row, with nothing in it at all.
        helper.createDatabase(TEST_DB, 2).close()
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM dishes"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM meal_plan"))
        }
    }

    @Test
    fun migrate2To3_leavesManualRecipesWithTheSameTitleAlone() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.seedV2()
            db.execSQL(
                "INSERT INTO dishes (id, title, createdAt) VALUES (4, \"Abuela's stew\", 400)",
            )
        }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            // Two homemade recipes with one name may be genuinely different; merging is a
            // decision for the "Find duplicates" screen, not something a migration does silently.
            assertEquals(
                2,
                db.count("SELECT COUNT(*) FROM dishes WHERE title = \"Abuela's stew\""),
            )
        }
    }
    // ---- 3 -> 4: chat and revisions -----------------------------------------------

    @Test
    fun migrate3To4_validatesAgainstTheEntities() {
        helper.createDatabase(TEST_DB, 2).use { it.seedV2() }
        helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_2_3, MIGRATION_3_4).close()
    }

    @Test
    fun migrate3To4_keepsEverythingFromBefore() {
        helper.createDatabase(TEST_DB, 2).use { it.seedV2() }
        helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_2_3, MIGRATION_3_4).use { db ->
            // Purely additive, so nothing the 2 -> 3 migration produced should have moved.
            assertEquals(3, db.count("SELECT COUNT(*) FROM pantry_items"))
            assertEquals(2, db.count("SELECT COUNT(*) FROM dishes"))
            assertEquals(3, db.count("SELECT COUNT(*) FROM meal_plan"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM recipe_chat_messages"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM recipe_revisions"))
        }
    }

    @Test
    fun migrate3To4_deletingARecipeTakesItsChatWithIt() {
        helper.createDatabase(TEST_DB, 2).use { it.seedV2() }
        helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_2_3, MIGRATION_3_4).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(
                "INSERT INTO recipe_chat_messages (dishId, role, content, createdAt) " +
                    "VALUES (3, 'USER', 'can I use sweet potato?', 1)",
            )
            db.execSQL(
                "INSERT INTO recipe_revisions (dishId, createdAt, reason, snapshot) " +
                    "VALUES (3, 1, 'chat edit', '{}')",
            )
            assertEquals(1, db.count("SELECT COUNT(*) FROM recipe_chat_messages"))

            db.execSQL("DELETE FROM dishes WHERE id = 3")

            // CASCADE here, unlike meal_plan's SET NULL: a conversation about a recipe that no
            // longer exists is not history worth keeping, it is orphaned noise.
            assertEquals(0, db.count("SELECT COUNT(*) FROM recipe_chat_messages"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM recipe_revisions"))
            // The plan entry, by contrast, survives.
            assertEquals(3, db.count("SELECT COUNT(*) FROM meal_plan"))
        }
    }
}

// ---- Fixture -----------------------------------------------------------------------

/**
 * A v2 database shaped like a real one: an accented pantry item, two MealDB recipes that are
 * actually the same meal added twice (the bug the migration cleans up), a manual recipe, and
 * plan entries pointing at all three.
 */
private fun SupportSQLiteDatabase.seedV2() {
    execSQL(
        "INSERT INTO pantry_items (id, name, normalizedName, category, status, updatedAt) " +
            "VALUES (1, 'Cebolla', 'cebolla', 'Produce', 'IN_STOCK', 100)",
    )
    execSQL(
        "INSERT INTO pantry_items (id, name, normalizedName, category, status, searchName, updatedAt) " +
            "VALUES (2, 'Orégano', 'gano', 'Spices & Herbs', 'LOW', 'oregano', 100)",
    )
    execSQL(
        "INSERT INTO pantry_items (id, name, normalizedName, category, status, updatedAt) " +
            "VALUES (3, 'Soy sauce', 'soy sauce', 'Condiments & Oils', 'OUT', 100)",
    )

    execSQL(
        "INSERT INTO dishes (id, title, mealdbId, instructions, createdAt) " +
            "VALUES (1, 'Teriyaki Chicken', '52772', 'Mix. Cook. Serve.', 100)",
    )
    // The same meal, added to a second day back when saveMealFromDetail always inserted.
    execSQL(
        "INSERT INTO dishes (id, title, mealdbId, instructions, createdAt) " +
            "VALUES (2, 'Teriyaki Chicken', '52772', 'Mix. Cook. Serve.', 200)",
    )
    execSQL("INSERT INTO dishes (id, title, createdAt) VALUES (3, \"Abuela's stew\", 300)")

    execSQL(
        "INSERT INTO dish_ingredients (id, dishId, rawName, normalizedName, measure) " +
            "VALUES (1, 1, 'chicken', 'chicken', '2 lbs')",
    )
    execSQL(
        "INSERT INTO dish_ingredients (id, dishId, rawName, normalizedName, measure) " +
            "VALUES (2, 1, 'soy sauce', 'soy sauce', '1/2 cup')",
    )
    execSQL(
        "INSERT INTO dish_ingredients (id, dishId, rawName, normalizedName, measure) " +
            "VALUES (3, 2, 'chicken', 'chicken', '2 lbs')",
    )
    execSQL(
        "INSERT INTO dish_ingredients (id, dishId, rawName, normalizedName) " +
            "VALUES (4, 3, 'Cebolla', 'cebolla')",
    )

    execSQL("INSERT INTO meal_plan (id, date, slot, dishId) VALUES (1, '2026-09-14', 'DINNER', 1)")
    execSQL("INSERT INTO meal_plan (id, date, slot, dishId) VALUES (2, '2026-09-16', 'DINNER', 2)")
    execSQL("INSERT INTO meal_plan (id, date, slot, dishId) VALUES (3, '2026-09-17', 'LUNCH', 3)")

    execSQL(
        "INSERT INTO shopping_items (id, name, normalizedName, category, checked, addedAt) " +
            "VALUES (1, 'Soy sauce', 'soy sauce', 'Condiments & Oils', 0, 100)",
    )
    execSQL(
        "INSERT INTO shopping_items (id, name, normalizedName, category, checked, addedAt) " +
            "VALUES (2, 'Rice', 'rice', 'Grains & Pasta', 1, 100)",
    )
}

// ---- Query helpers -----------------------------------------------------------------

private fun SupportSQLiteDatabase.count(sql: String): Int =
    query(sql).use { it.moveToFirst(); it.getInt(0) }

private fun SupportSQLiteDatabase.long(sql: String): Long =
    query(sql).use { it.moveToFirst(); it.getLong(0) }

private fun SupportSQLiteDatabase.longOrNull(sql: String): Long? =
    query(sql).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

private fun SupportSQLiteDatabase.text(sql: String): String =
    query(sql).use { it.moveToFirst(); it.getString(0) }
