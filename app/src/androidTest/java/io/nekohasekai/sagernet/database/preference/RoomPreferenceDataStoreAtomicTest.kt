package io.nekohasekai.sagernet.database.preference

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
