package io.nekohasekai.sagernet.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SagerDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SagerDatabase::class.java,
    )

    @Test
    fun migrate1To6PreservesRowsAndAppliesSafeDefaults() {
        helper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                "INSERT INTO `proxy_groups` " +
                    "(`id`,`userOrder`,`ungrouped`,`name`,`type`,`subscription`,`order`) " +
                    "VALUES (1,1,1,'Default',0,NULL,0)"
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            6,
            true,
            *SagerDatabase.ALL_MIGRATIONS,
        ).use { database ->
            database.query(
                "SELECT `name`,`isSelector`,`frontProxy`,`landingProxy` " +
                    "FROM `proxy_groups` WHERE `id`=1"
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("Default", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(-1L, cursor.getLong(2))
                assertEquals(-1L, cursor.getLong(3))
            }
            database.query("PRAGMA table_info(`proxy_entities`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                var foundAnyTls = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "anyTLSBean") foundAnyTls = true
                }
                assertEquals(true, foundAnyTls)
            }
            database.query("PRAGMA table_info(`rules`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                var foundConfig = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "config") foundConfig = true
                }
                assertEquals(true, foundConfig)
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-test"
    }
}
