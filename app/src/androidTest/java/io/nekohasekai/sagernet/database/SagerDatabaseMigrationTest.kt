package io.nekohasekai.sagernet.database

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SagerDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SagerDatabase::class.java,
        emptyList(),
    )

    @Test
    fun everyExportedVersionMigratesToCurrentSchema() {
        (1..9).forEach { version ->
            val databaseName = "profile-migration-$version"
            helper.createDatabase(databaseName, version).close()
            helper.runMigrationsAndValidate(
                databaseName,
                10,
                true,
                *SagerDatabase.ALL_MIGRATIONS,
            ).close()
        }
    }

    @Test
    fun migration9To10PreservesProfilesAndInitializesNewMetadata() {
        val databaseName = "profile-migration-data"
        val database = helper.createDatabase(databaseName, 9)
        database.insert(
            "proxy_entities",
            0,
            ContentValues().apply {
                put("id", 42L)
                put("groupId", 7L)
                put("type", ProxyEntity.TYPE_SOCKS)
                put("userOrder", 1L)
                put("tx", 123L)
                put("rx", 456L)
                put("status", 0)
                put("ping", 0)
                put("uuid", "migration-test")
            },
        )
        database.close()

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            10,
            true,
            SagerDatabase.MIGRATION_9_10,
        )
        migrated.query(
            "SELECT id, groupId, uuid, displayNameCache, displayAddressCache, " +
                "displayTypeCache, hasExplicitName, configRevision FROM proxy_entities",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(42L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
            assertEquals(7L, cursor.getLong(cursor.getColumnIndexOrThrow("groupId")))
            assertEquals("migration-test", cursor.getString(cursor.getColumnIndexOrThrow("uuid")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("displayNameCache")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("displayAddressCache")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("displayTypeCache")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("hasExplicitName")))
            assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("configRevision")))
        }
        migrated.close()
    }
}
