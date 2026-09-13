package com.lucca.ko.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.lucca.ko.data.db.KoDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TEST_DB = "migration-test.db"

/**
 * Runs under Robolectric in `src/test` rather than as an instrumented test, so that CI's
 * `testDebugUnitTest` actually executes it. Reads the exported schemas from
 * `app/schemas`, which is on the test asset source set (see app/build.gradle.kts).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KoDatabase::class.java,
    )

    /** Smoke test: the committed 2.json is readable and a v2 database can be created from it. */
    @Test
    fun createsSchemaVersion2() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO pantry_items (name, normalizedName, category, status, updatedAt) " +
                    "VALUES ('Cebolla', 'onion', 'Produce', 'IN_STOCK', 1)",
            )
            db.query("SELECT COUNT(*) FROM pantry_items").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
        }
    }
}
