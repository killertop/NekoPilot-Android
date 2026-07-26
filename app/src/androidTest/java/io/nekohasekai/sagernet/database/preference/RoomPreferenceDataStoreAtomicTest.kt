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

    @Test
    fun concurrentRevisionCompareAndSetHasExactlyOneWinner() {
        val dao = PublicDatabase.kvPairDao
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val revisionKey = "policy-revision-$suffix"
        val payloadKey = "policy-payload-$suffix"
        val firstStore = RoomPreferenceDataStore(PublicDatabase.instance, dao)
        val secondStore = RoomPreferenceDataStore(PublicDatabase.instance, dao)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            runBlocking {
                firstStore.putValuesAtomically(
                    listOf(KeyValuePair(revisionKey).put(7L)),
                )
            }
            val first = executor.submit<Boolean> {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                runBlocking {
                    firstStore.compareAndSetLongWithValues(
                        revisionKey = revisionKey,
                        expectedRevision = 7L,
                        missingRevision = 0L,
                        newRevision = 8L,
                        values = listOf(KeyValuePair(payloadKey).put("first")),
                    )
                }
            }
            val second = executor.submit<Boolean> {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                runBlocking {
                    secondStore.compareAndSetLongWithValues(
                        revisionKey = revisionKey,
                        expectedRevision = 7L,
                        missingRevision = 0L,
                        newRevision = 8L,
                        values = listOf(KeyValuePair(payloadKey).put("second")),
                    )
                }
            }
            start.countDown()

            val results = listOf(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
            assertEquals(1, results.count { it })
            assertEquals(8L, dao[revisionKey]?.long)
            assertTrue(dao[payloadKey]?.string in setOf("first", "second"))
        } finally {
            start.countDown()
            executor.shutdownNow()
            dao.delete(revisionKey)
            dao.delete(payloadKey)
        }
    }

    @Test
    fun staleAppliedReceiptCannotChangeStateOrGeneration() {
        val dao = PublicDatabase.kvPairDao
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val desiredRevisionKey = "desired-revision-$suffix"
        val generationKey = "tun-generation-$suffix"
        val statusKey = "policy-status-$suffix"
        val store = RoomPreferenceDataStore(PublicDatabase.instance, dao)
        try {
            runBlocking {
                store.putValuesAtomically(
                    listOf(
                        KeyValuePair(desiredRevisionKey).put(9L),
                        KeyValuePair(generationKey).put(41L),
                        KeyValuePair(statusKey).put("pending"),
                    ),
                )
                val staleGeneration = store.compareLongAndIncrementCounterWithValues(
                    conditionKey = desiredRevisionKey,
                    expectedCondition = 8L,
                    missingCondition = 0L,
                    counterKey = generationKey,
                    missingCounter = 0L,
                    values = listOf(KeyValuePair(statusKey).put("applied")),
                )
                assertEquals(null, staleGeneration)
            }

            assertEquals(9L, dao[desiredRevisionKey]?.long)
            assertEquals(41L, dao[generationKey]?.long)
            assertEquals("pending", dao[statusKey]?.string)
        } finally {
            dao.delete(desiredRevisionKey)
            dao.delete(generationKey)
            dao.delete(statusKey)
        }
    }

    @Test
    fun matchingAppliedReceiptPublishesStateAndNextGenerationTogether() {
        val dao = PublicDatabase.kvPairDao
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val desiredRevisionKey = "desired-revision-$suffix"
        val generationKey = "tun-generation-$suffix"
        val statusKey = "policy-status-$suffix"
        val store = RoomPreferenceDataStore(PublicDatabase.instance, dao)
        try {
            val generation = runBlocking {
                store.putValuesAtomically(
                    listOf(
                        KeyValuePair(desiredRevisionKey).put(9L),
                        KeyValuePair(generationKey).put(41L),
                        KeyValuePair(statusKey).put("pending"),
                    ),
                )
                store.compareLongAndIncrementCounterWithValues(
                    conditionKey = desiredRevisionKey,
                    expectedCondition = 9L,
                    missingCondition = 0L,
                    counterKey = generationKey,
                    missingCounter = 0L,
                    values = listOf(KeyValuePair(statusKey).put("applied")),
                )
            }

            assertEquals(42L, generation)
            assertEquals(42L, dao[generationKey]?.long)
            assertEquals("applied", dao[statusKey]?.string)
        } finally {
            dao.delete(desiredRevisionKey)
            dao.delete(generationKey)
            dao.delete(statusKey)
        }
    }

    @Test
    fun staleAttemptTokenCannotOverwriteTheNewerReceipt() {
        val dao = PublicDatabase.kvPairDao
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val desiredRevisionKey = "desired-revision-$suffix"
        val attemptTokenKey = "attempt-token-$suffix"
        val statusKey = "policy-status-$suffix"
        val store = RoomPreferenceDataStore(PublicDatabase.instance, dao)
        try {
            runBlocking {
                store.putValuesAtomically(
                    listOf(
                        KeyValuePair(desiredRevisionKey).put(11L),
                        KeyValuePair(attemptTokenKey).put("new-attempt"),
                        KeyValuePair(statusKey).put("applying"),
                    ),
                )
                assertFalse(
                    store.compareLongAndStringWithValues(
                        longConditionKey = desiredRevisionKey,
                        expectedLong = 11L,
                        missingLong = 0L,
                        stringConditionKey = attemptTokenKey,
                        expectedString = "stale-attempt",
                        values = listOf(KeyValuePair(statusKey).put("failed")),
                    ),
                )
                assertTrue(
                    store.compareLongAndStringWithValues(
                        longConditionKey = desiredRevisionKey,
                        expectedLong = 11L,
                        missingLong = 0L,
                        stringConditionKey = attemptTokenKey,
                        expectedString = "new-attempt",
                        values = listOf(KeyValuePair(statusKey).put("applied")),
                    ),
                )
            }

            assertEquals("new-attempt", dao[attemptTokenKey]?.string)
            assertEquals("applied", dao[statusKey]?.string)
        } finally {
            dao.delete(desiredRevisionKey)
            dao.delete(attemptTokenKey)
            dao.delete(statusKey)
        }
    }
}
