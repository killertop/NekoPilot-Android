package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.CONNECTION_TEST_URL
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity

class UrlTest {

    val link = CONNECTION_TEST_URL
    private val timeout = 5000
    private val downloadEnabled = DataStore.connectionTestDownload

    suspend fun doTest(profile: ProxyEntity): UrlTestResult {
        val result = TestInstance(
            profile,
            link,
            timeout,
            downloadEnabled = downloadEnabled,
        ).doTest()
        return result
    }

}

data class UrlTestResult(val latencyMs: Int, val downloadMbps: Double?)
