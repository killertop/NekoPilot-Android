package io.nekohasekai.sagernet.database.preference

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomPreferenceDataStoreAtomicTest {

    @Test
    fun concurrentInitializersReturnTheSingleDatabaseWinner() {
        val dao = PublicDatabase.kvPairDao
        val key = "atomic-test-${UUID.randomUUID()}"
        val firstStore = RoomPreferenceDataStore(PublicDatabase.instance, dao)
        val secondStore = RoomPreferenceDataStore(PublicDatabase.instance, dao)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<String> {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                firstStore.getOrPutStringBlocking(key) { "first" }
            }
            val second = executor.submit<String> {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                secondStore.getOrPutStringBlocking(key) { "second" }
            }
            start.countDown()

            val firstResult = first.get(10, TimeUnit.SECONDS)
            val secondResult = second.get(10, TimeUnit.SECONDS)
            assertEquals(firstResult, secondResult)
            assertEquals(firstResult, dao[key]?.string)
        } finally {
            executor.shutdownNow()
            dao.delete(key)
        }
    }

    @Test
    fun concurrentInitializersRepairOneLegacyBlankValue() {
        val dao = PublicDatabase.kvPairDao
        val key = "blank-secret-test-${UUID.randomUUID()}"
        val firstStore = RoomPreferenceDataStore(PublicDatabase.instance, dao)
        val secondStore = RoomPreferenceDataStore(PublicDatabase.instance, dao)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            dao.put(KeyValuePair(key).put("   "))
            val first = executor.submit<String> {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                firstStore.getOrPutStringBlocking(key) { "first-replacement" }
            }
            val second = executor.submit<String> {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                secondStore.getOrPutStringBlocking(key) { "second-replacement" }
            }
            start.countDown()

            val firstResult = first.get(10, TimeUnit.SECONDS)
            val secondResult = second.get(10, TimeUnit.SECONDS)
            assertTrue(firstResult.isNotBlank())
            assertEquals(firstResult, secondResult)
            assertEquals(firstResult, dao[key]?.string)
        } finally {
            executor.shutdownNow()
            dao.delete(key)
        }
    }

    @Test
    fun staleReloadCannotOverwriteLocalMutationBetweenQueryAndCommit() {
        val dao = PublicDatabase.kvPairDao
        val key = "reload-race-${UUID.randomUUID()}"
        val store = RoomPreferenceDataStore(PublicDatabase.instance, dao)
        val queried = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            dao.delete(key)
            store.refreshBlocking()
            val reload = executor.submit {
                store.reloadFromDatabaseForTest {
                    queried.countDown()
                    assertTrue(allowCommit.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(queried.await(5, TimeUnit.SECONDS))

            // This write lands after the database query but before the stale snapshot can be
            // committed. The generation check and swap must share the same state lock.
            store.putString(key, "local-winner")
            allowCommit.countDown()
            reload.get(10, TimeUnit.SECONDS)

            assertEquals("local-winner", store.getString(key))
            store.flushBlocking()
            assertEquals("local-winner", dao[key]?.string)
        } finally {
            allowCommit.countDown()
            executor.shutdownNow()
            dao.delete(key)
        }
    }

    @Test
    fun longPairCompareAndSetRejectsAnotherStoreRevision() {
        val dao = PublicDatabase.kvPairDao
        val firstKey = "pair-first-${UUID.randomUUID()}"
        val secondKey = "pair-second-${UUID.randomUUID()}"
        val firstStore = RoomPreferenceDataStore(PublicDatabase.instance, dao)
        val secondStore = RoomPreferenceDataStore(PublicDatabase.instance, dao)
        try {
            firstStore.putLongPairBlocking(firstKey, 1L, secondKey, 10L)
            val observed = firstStore.getLongPairBlocking(firstKey, secondKey)

            assertTrue(
                firstStore.compareAndSetLongPairBlocking(
                    firstKey,
                    observed.first,
                    secondKey,
                    observed.second,
                    3L,
                    30L,
                ),
            )
            val committed = firstStore.getLongPairBlocking(firstKey, secondKey)
            assertEquals(3L, committed.first)
            assertEquals(30L, committed.second)

            secondStore.putLongPairBlocking(firstKey, 2L, secondKey, 20L)
            assertFalse(
                firstStore.compareAndSetLongPairBlocking(
                    firstKey,
                    committed.first,
                    secondKey,
                    committed.second,
                    4L,
                    40L,
                ),
            )

            val latest = firstStore.getLongPairBlocking(firstKey, secondKey)
            assertEquals(2L, latest.first)
            assertEquals(20L, latest.second)
        } finally {
            dao.delete(firstKey)
            dao.delete(secondKey)
        }
    }

    @Test
    fun atomicValueBatchPublishesModeAndAllowListTogether() {
        val dao = PublicDatabase.kvPairDao
        val modeKey = "policy-mode-${UUID.randomUUID()}"
        val listKey = "policy-list-${UUID.randomUUID()}"
        val setupKey = "policy-setup-${UUID.randomUUID()}"
        val store = RoomPreferenceDataStore(PublicDatabase.instance, dao)
        try {
            runBlocking {
                store.putValuesAtomically(
                    listOf(
                        KeyValuePair(modeKey).put(true),
                        KeyValuePair(listKey).put("com.example.one\ncom.example.two"),
                        KeyValuePair(setupKey).put(true),
                    ),
                )
            }

            assertTrue(store.getBoolean(modeKey) == true)
            assertEquals("com.example.one\ncom.example.two", store.getString(listKey))
            assertTrue(store.getBoolean(setupKey) == true)
            assertTrue(dao[modeKey]?.boolean == true)
            assertEquals("com.example.one\ncom.example.two", dao[listKey]?.string)
            assertTrue(dao[setupKey]?.boolean == true)
        } finally {
            dao.delete(modeKey)
            dao.delete(listKey)
            dao.delete(setupKey)
        }
    }

    @Test
    fun atomicValueSnapshotReadsModeAndAllowListFromOneCommittedState() {
        val dao = PublicDatabase.kvPairDao
        val modeKey = "snapshot-mode-${UUID.randomUUID()}"
        val listKey = "snapshot-list-${UUID.randomUUID()}"
        val setupKey = "snapshot-setup-${UUID.randomUUID()}"
        val store = RoomPreferenceDataStore(PublicDatabase.instance, dao)
        try {
            runBlocking {
                store.putValuesAtomically(
                    listOf(
                        KeyValuePair(modeKey).put(true),
                        KeyValuePair(listKey).put("com.example.one\ncom.example.two"),
                        KeyValuePair(setupKey).put(true),
                    ),
                )
                val snapshot = store.readValuesAtomically(listOf(modeKey, listKey, setupKey))
                assertTrue(snapshot[modeKey]?.boolean == true)
                assertEquals("com.example.one\ncom.example.two", snapshot[listKey]?.string)
                assertTrue(snapshot[setupKey]?.boolean == true)
            }
        } finally {
            dao.delete(modeKey)
            dao.delete(listKey)
            dao.delete(setupKey)
        }
    }

    @Test
    fun atomicValueBatchRollsBackEveryKeyWhenOneWriteFails() {
        val dao = PublicDatabase.kvPairDao
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val modeKey = "rollback-mode-$suffix"
        val listKey = "rollback-list-$suffix"
        val setupKey = "rollback-setup-$suffix"
        val triggerName = "reject_policy_$suffix"
        val store = RoomPreferenceDataStore(PublicDatabase.instance, dao)
        try {
            runBlocking {
                store.putValuesAtomically(
                    listOf(
                        KeyValuePair(modeKey).put(false),
                        KeyValuePair(listKey).put("com.example.old"),
                        KeyValuePair(setupKey).put(false),
                    ),
                )
            }
            PublicDatabase.instance.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER `$triggerName`
                BEFORE INSERT ON `KeyValuePair`
                WHEN NEW.`key` = '$listKey'
                BEGIN
                    SELECT RAISE(ABORT, 'forced policy write failure');
                END
                """.trimIndent(),
            )

            assertThrows(Exception::class.java) {
                runBlocking {
                    store.putValuesAtomically(
                        listOf(
                            KeyValuePair(modeKey).put(true),
                            KeyValuePair(listKey).put("com.example.new"),
                            KeyValuePair(setupKey).put(true),
                        ),
                    )
                }
            }
            store.refreshBlocking()

            assertFalse(store.getBoolean(modeKey)!!)
            assertEquals("com.example.old", store.getString(listKey))
            assertFalse(store.getBoolean(setupKey)!!)
        } finally {
            PublicDatabase.instance.openHelper.writableDatabase.execSQL(
                "DROP TRIGGER IF EXISTS `$triggerName`",
            )
            dao.delete(modeKey)
            dao.delete(listKey)
            dao.delete(setupKey)
        }
    }
}
