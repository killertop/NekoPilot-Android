package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import org.json.JSONObject

/**
 * A reusable sing-box test core. One instance can test a complete group by
 * switching the selector outbound instead of creating one core per profile.
 */
class TestInstance(
    profile: ProxyEntity,
    val link: String,
    private val timeout: Int,
    private val testProfiles: List<ProxyEntity> = listOf(profile),
    private val downloadEnabled: Boolean = false,
    private val downloadLink: String = "https://speed.cloudflare.com/__down?bytes=1048576",
) : BoxInstance(profile) {

    private var testOutbounds: Map<Long, String> = emptyMap()

    suspend fun doTest(): UrlTestResult {
        var result: UrlTestResult? = null
        var failure: Throwable? = null
        runBatch(
            listOf(profile),
            onResult = { _, value -> result = value },
            onError = { _, error -> failure = error },
        )
        failure?.let { throw it }
        return result ?: error("test produced no result")
    }

    suspend fun doTestInitialized(target: ProxyEntity): UrlTestResult {
        val outbound = testOutbounds[target.id]
        if (outbound != null && !box.selectOutbound(outbound)) {
            error("test outbound unavailable for ${target.id}")
        }
        val latency = Libcore.urlTest(box, link, timeout)
        val downloadMbps = if (downloadEnabled) {
            try {
                val value = JSONObject(
                    Libcore.urlTestDownload(box, downloadLink, timeout.toLong(), 1_048_576L)
                )
                val bytes = value.getLong("bytes")
                val elapsedMs = value.getLong("elapsedMs")
                if (bytes > 0 && elapsedMs > 0) bytes * 0.008 / elapsedMs else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                null
            }
        } else {
            null
        }
        return UrlTestResult(latency, downloadMbps)
    }

    suspend fun runBatch(
        targets: List<ProxyEntity>,
        onResult: (ProxyEntity, UrlTestResult) -> Unit,
        onError: (ProxyEntity, Throwable) -> Unit,
    ) = coroutineScope {
        val fatalError = CompletableDeferred<Throwable>()
        processes = GuardedProcessPool { error ->
            Logs.w(error)
            fatalError.complete(error)
        }
        val worker = async(Dispatchers.Default) {
            use {
                init()
                launch()
                if (processes.processCount > 0) {
                    // wait for plugin start once for the whole batch
                    delay(500)
                }
                for (target in targets) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val result = doTestInitialized(target)
                        currentCoroutineContext().ensureActive()
                        onResult(target, result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onError(target, e)
                    }
                }
            }
        }
        try {
            select<Unit> {
                worker.onAwait { }
                fatalError.onAwait { throw it }
            }
        } finally {
            worker.cancelAndJoin()
        }
    }

    override fun buildConfig() {
        config = buildConfig(
            profile,
            forTest = true,
            testProfiles = testProfiles.takeIf { it.size > 1 },
        )
        testOutbounds = config.testOutbounds
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }
}
