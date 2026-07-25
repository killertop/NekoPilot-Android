package io.nekohasekai.sagernet.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileSelectionCompatibilityTest {
    @Test
    fun staleUnsupportedSelectionRepairsToSupportedNode() = runBlocking {
        SagerDatabase.proxyDao.reset()
        SagerDatabase.groupDao.reset()
        val groupId = SagerDatabase.groupDao.createGroup(
            ProxyGroup(userOrder = 1L, ungrouped = true, type = GroupType.BASIC),
        )
        try {
            val unsupportedId = SagerDatabase.proxyDao.addProxy(
                ProxyEntity(groupId = groupId, userOrder = 1L).putBean(HysteriaBean().apply {
                    protocolVersion = 1
                    serverAddress = "legacy-hysteria.example"
                    serverPorts = "443"
                    protocol = HysteriaBean.PROTOCOL_FAKETCP
                    uploadMbps = 10
                    downloadMbps = 50
                    hopInterval = 10
                    name = "Legacy FakeTCP"
                }),
            )
            val supportedId = SagerDatabase.proxyDao.addProxy(
                ProxyEntity(groupId = groupId, userOrder = 2L).putBean(SOCKSBean().apply {
                    serverAddress = "socks.example"
                    serverPort = 1080
                    name = "Supported SOCKS"
                }),
            )
            DataStore.selectProxy(unsupportedId, groupId)

            val repaired = ProfileManager.ensureValidSelection()

            assertEquals(supportedId, repaired?.id)
            assertEquals(supportedId, DataStore.readProxySelection().profileId)
        } finally {
            SagerDatabase.proxyDao.reset()
            SagerDatabase.groupDao.reset()
            DataStore.selectProxy(0L, 0L)
        }
    }
}
