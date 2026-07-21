package io.nekohasekai.sagernet.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
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
            fun profile(name: String, status: Int, ping: Int) = ProxyEntity(
                status = status,
                ping = ping,
            ).putBean(ShadowsocksBean().apply {
                serverAddress = "127.0.0.1"
                serverPort = 8388
                this.name = name
                initializeDefaultValues()
            })
            val selectedId = dao.addProxy(profile("Selected", status = 0, ping = 0))
            val fastestId = dao.addProxy(profile("Fastest", status = 1, ping = 10))
            dao.addProxy(profile("Second", status = 1, ping = 20))

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
