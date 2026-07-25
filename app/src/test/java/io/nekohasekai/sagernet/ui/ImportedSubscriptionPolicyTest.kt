package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.SubscriptionBean
import org.junit.Assert.assertFalse
import org.junit.Test

class ImportedSubscriptionPolicyTest {
    @Test
    fun sharedSubscriptionCannotEnableDeviceLocalDnsResolution() {
        val imported = SubscriptionBean().apply {
            link = "https://subscription.example/list"
            forceResolve = true
        }

        sanitizeImportedSubscriptionNetworkPolicy(imported)

        assertFalse(imported.forceResolve)
    }
}
