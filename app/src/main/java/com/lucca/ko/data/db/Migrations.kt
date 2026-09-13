package com.lucca.ko.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 -> v2: add the English search alias column to pantry_items. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pantry_items ADD COLUMN searchName TEXT")
    }
}

/**
 * v2 -> v3: recipes become first-class.
 *
 * This is the only migration in the rework that touches existing rows; everything after it is
 * additive `CREATE TABLE`s. It does four things:
 *
 *  1. Adds the recipe/ingredient columns the library and editor need (safe on every SQLite).
 *  2. Collapses duplicate MealDB recipes. `saveMealFromDetail` used to insert unconditionally,
 *     so adding the same meal on two days created two independent recipes with duplicated
 *     ingredients. A unique index on `mealdbId` then makes that structurally impossible.
 *  3. Rebuilds `meal_plan` so `dishId` is nullable with `ON DELETE SET NULL` and carries a
 *     `titleSnapshot`. Forced: SQLite cannot alter a foreign key or drop NOT NULL in place.
 *  4. Creates `recipe_steps`, `tags` and `recipe_tags`.
 *
 * `quantity`/`unit` on ingredients and `recipe_steps` rows are deliberately NOT backfilled here.
 * Parsing "1 1/2 cups" is Kotlin's job, not SQL's; see `data/repair/StartupRepairs.kt`.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Room turns foreign keys on in onConfigure and SQLiteOpenHelper wraps onUpgrade in a
        // transaction, which makes `PRAGMA foreign_keys = OFF` a no-op here. `defer_foreign_keys`
        // IS honoured inside a transaction: it postpones constraint checks to COMMIT while
        // leaving ON DELETE actions firing normally. That is what makes the drop-then-rename
        // below legal. SQLite clears it at commit, so there is nothing to undo.
        db.execSQL("PRAGMA defer_foreign_keys = TRUE")

        // ---- 1. Additive columns -------------------------------------------------------
        listOf(
            "ALTER TABLE dishes ADD COLUMN imageLocalPath TEXT",
            "ALTER TABLE dishes ADD COLUMN servings INTEGER NOT NULL DEFAULT 2",
            "ALTER TABLE dishes ADD COLUMN prepMinutes INTEGER",
            "ALTER TABLE dishes ADD COLUMN cookMinutes INTEGER",
            "ALTER TABLE dishes ADD COLUMN notes TEXT",
            "ALTER TABLE dishes ADD COLUMN isFavourite INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE dishes ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE dishes ADD COLUMN lastCookedAt INTEGER",
            "ALTER TABLE dishes ADD COLUMN timesCooked INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE dishes ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL'",
            "ALTER TABLE dishes ADD COLUMN forkedFromId INTEGER",
            "ALTER TABLE dishes ADD COLUMN kcalPerServing REAL",
            "ALTER TABLE dishes ADD COLUMN proteinG REAL",
            "ALTER TABLE dishes ADD COLUMN carbsG REAL",
            "ALTER TABLE dishes ADD COLUMN fatG REAL",
            "ALTER TABLE dishes ADD COLUMN macroSource TEXT",
            "ALTER TABLE dishes ADD COLUMN macroUpdatedAt INTEGER",
            "ALTER TABLE dishes ADD COLUMN macroNote TEXT",
            "ALTER TABLE dishes ADD COLUMN searchBlob TEXT",
            "UPDATE dishes SET updatedAt = createdAt",
            "UPDATE dishes SET source = 'MEALDB' WHERE mealdbId IS NOT NULL",

            "ALTER TABLE dish_ingredients ADD COLUMN quantity REAL",
            "ALTER TABLE dish_ingredients ADD COLUMN unit TEXT",
            "ALTER TABLE dish_ingredients ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE dish_ingredients ADD COLUMN optional INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE dish_ingredients ADD COLUMN section TEXT",
            "ALTER TABLE dish_ingredients ADD COLUMN note TEXT",
            // Ids are monotone within a recipe (insertDishWithIngredients inserted them in
            // recipe order), so ordering by sortOrder reproduces the original order exactly.
            // Sparse values also leave room for drag-reordering later.
            "UPDATE dish_ingredients SET sortOrder = id",
        ).forEach(db::execSQL)

        // ---- 2. Collapse duplicate MealDB recipes --------------------------------------
        // Must run while meal_plan still has the old dishId column. The COALESCE is
        // load-bearing: for a manual recipe the inner scalar is NULL, MIN over a NULL match is
        // NULL, and the entry's dishId is preserved unchanged.
        db.execSQL(
            """
            UPDATE meal_plan
            SET dishId = COALESCE((
                    SELECT MIN(d2.id) FROM dishes d2
                    WHERE d2.mealdbId IS NOT NULL
                      AND d2.mealdbId = (SELECT d1.mealdbId FROM dishes d1 WHERE d1.id = meal_plan.dishId)
                ), dishId)
            """.trimIndent(),
        )
        db.execSQL(
            """
            DELETE FROM dishes
            WHERE mealdbId IS NOT NULL
              AND id NOT IN (SELECT MIN(id) FROM dishes WHERE mealdbId IS NOT NULL GROUP BY mealdbId)
            """.trimIndent(),
        )
        // Belt and braces: CASCADE should already have taken these, but deferred foreign keys
        // plus a table rebuild in the same transaction is not where to assume.
        db.execSQL("DELETE FROM dish_ingredients WHERE dishId NOT IN (SELECT id FROM dishes)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_dishes_mealdbId ON dishes (mealdbId)")

        // Manual recipes sharing a title are deliberately NOT merged: two homemade "Pasta"
        // entries may be genuinely different, and merging destroys data irreversibly. The
        // Recipes screen gets a "Find duplicates" tool instead, where you can see both.

        // ---- 3. Rebuild meal_plan ------------------------------------------------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS meal_plan_new (
                id            INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                date          TEXT    NOT NULL,
                slot          TEXT    NOT NULL,
                dishId        INTEGER,
                titleSnapshot TEXT    NOT NULL DEFAULT '',
                servings      REAL    NOT NULL DEFAULT 1.0,
                note          TEXT,
                cooked        INTEGER NOT NULL DEFAULT 0,
                sortOrder     INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(dishId) REFERENCES dishes(id) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO meal_plan_new (id, date, slot, dishId, titleSnapshot, servings, note, cooked, sortOrder)
            SELECT mp.id, mp.date, mp.slot, mp.dishId, COALESCE(d.title, ''), 1.0, NULL, 0, mp.id
            FROM meal_plan mp LEFT JOIN dishes d ON d.id = mp.dishId
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE meal_plan")
        db.execSQL("ALTER TABLE meal_plan_new RENAME TO meal_plan")
        // Created after the rename: pre-3.25 SQLite keeps a literal index name across a rename,
        // so an index built on meal_plan_new would keep that name and fail Room's validation.
        db.execSQL("CREATE INDEX IF NOT EXISTS index_meal_plan_dishId ON meal_plan (dishId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_meal_plan_date ON meal_plan (date)")

        // ---- 4. Recipe library tables --------------------------------------------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS recipe_steps (
                id       INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                dishId   INTEGER NOT NULL,
                position INTEGER NOT NULL,
                text     TEXT    NOT NULL,
                minutes  INTEGER,
                FOREIGN KEY(dishId) REFERENCES dishes(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_recipe_steps_dishId ON recipe_steps (dishId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tags (
                id             INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name           TEXT NOT NULL,
                normalizedName TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tags_normalizedName ON tags (normalizedName)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS recipe_tags (
                dishId INTEGER NOT NULL,
                tagId  INTEGER NOT NULL,
                PRIMARY KEY(dishId, tagId),
                FOREIGN KEY(dishId) REFERENCES dishes(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(tagId)  REFERENCES tags(id)   ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_recipe_tags_tagId ON recipe_tags (tagId)")
    }
}

/** Every migration the database knows about, in order. */
val KO_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
