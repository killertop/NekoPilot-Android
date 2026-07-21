package io.nekohasekai.sagernet.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NodeListProjectionTest {

    @Test
    fun projectionSortsByLatencyWithoutLoadingBeans() = withDatabase { dao ->
        val slow = dao.addProxy(profile("Slow", "slow.example", status = 1, ping = 180))
        val pending = dao.addProxy(profile("Pending", "pending.example", status = 0, ping = 0))
        val fast = dao.addProxy(profile("Fast", "fast.example", status = 1, ping = 35))

        val rows = dao.getNodeList()

        assertEquals(listOf(fast, slow, pending), rows.map { it.id })
        assertEquals(listOf("Fast", "Slow", "Pending"), rows.map { it.displayNameCache })
        assertEquals("fast.example:1080", rows.first().displayAddressCache)
        assertNull(rows.first().toStub().socksBean)
    }

    @Test
    fun staleLatencyResultCannotOverwriteEditedProfile() = withDatabase { dao ->
        val id = dao.addProxy(profile("Original", "old.example", status = 0, ping = 0))
        val stale = dao.getNodeList().single().toStub().apply {
            status = 1
            ping = 120
        }

        val edited = requireNotNull(dao.getById(id)).apply {
            putBean(SOCKSBean().apply {
                name = "Edited"
                serverAddress = "new.example"
                serverPort = 1080
                initializeDefaultValues()
            })
        }
        dao.updateProxy(edited)

        assertTrue(dao.updateTestResultsIfUnchanged(listOf(stale)).isEmpty())
        assertEquals(0, requireNotNull(dao.getById(id)).ping)

        val current = dao.getNodeList().single().toStub().apply {
            status = 1
            ping = 42
        }
        assertEquals(listOf(id), dao.updateTestResultsIfUnchanged(listOf(current)))
        assertEquals(42, requireNotNull(dao.getById(id)).ping)
    }

    private fun profile(name: String, address: String, status: Int, ping: Int) =
        ProxyEntity(status = status, ping = ping).putBean(SOCKSBean().apply {
            this.name = name
            serverAddress = address
            serverPort = 1080
            initializeDefaultValues()
        })

    private fun withDatabase(block: (ProxyEntity.Dao) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SagerDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            block(database.proxyDao())
        } finally {
            database.close()
        }
    }
}
