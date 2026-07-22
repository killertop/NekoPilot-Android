package io.nekohasekai.sagernet.database.preference

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
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
}
