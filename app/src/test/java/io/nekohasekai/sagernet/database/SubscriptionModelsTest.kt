package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionModelsTest {
    @Test
    fun editingSourceConfigPreservesRuntimeMetadata() {
        val bean = SubscriptionBean().apply {
            link = "https://old.example/sub"
            lastUpdated = 123
            subscriptionUserinfo = "upload=1"
        }

        bean.applySourceConfig(
            bean.sourceConfig().copy(
                link = "https://new.example/sub",
                autoUpdate = true,
                autoUpdateDelayMinutes = 60,
            ),
        )

        assertEquals("https://new.example/sub", bean.link)
        assertEquals(123, bean.runtimeState().lastUpdatedSeconds)
        assertEquals("upload=1", bean.runtimeState().userInfo)
    }

    @Test
    fun updatingRuntimeMetadataPreservesSourceConfig() {
        val bean = SubscriptionBean().apply {
            link = "https://example.com/sub"
            customUserAgent = "NekoPilot"
        }
        val source = bean.sourceConfig()

        bean.applyRuntimeState(SubscriptionRuntimeState(456, "download=2"))

        assertEquals(source, bean.sourceConfig())
        assertEquals(456, bean.lastUpdated)
        assertEquals("download=2", bean.subscriptionUserinfo)
    }
}
