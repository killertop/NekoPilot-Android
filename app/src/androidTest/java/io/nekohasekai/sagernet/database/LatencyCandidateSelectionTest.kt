package io.nekohasekai.sagernet.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LatencyCandidateSelectionTest {
    @Test
    fun selectedProfileSurvivesOrderedCandidateLimit() {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SagerDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = database.proxyDao()
            val selectedId = dao.addProxy(
                ProxyEntity(type = ProxyEntity.TYPE_SS, status = 0, ping = 0),
            )
            val fastestId = dao.addProxy(
                ProxyEntity(type = ProxyEntity.TYPE_SS, status = 1, ping = 10),
            )
            dao.addProxy(ProxyEntity(type = ProxyEntity.TYPE_SS, status = 1, ping = 20))

            val candidates = dao.getLatencyCandidates(
                excludedType = ProxyEntity.TYPE_CONFIG,
                selectedId = selectedId,
                limit = 2,
            )

            assertEquals(listOf(selectedId, fastestId), candidates.map { it.id })
        } finally {
            database.close()
        }
    }
}
