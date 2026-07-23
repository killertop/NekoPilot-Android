package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.core.ConnectionStateRepository
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.InetAddress
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

@Suppress("EXPERIMENTAL_API_USAGE")
abstract class GroupUpdater {

    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    )

    data class Progress(
        var max: Int
    ) {
        private val value = AtomicInteger()
        val progress: Int
            get() = value.get()

        fun increment(): Int = value.incrementAndGet()
    }

    protected suspend fun forceResolve(
        profiles: List<AbstractBean>, groupId: Long?
    ) {
        val progress = Progress(
            profiles.count { it !is NaiveBean && !it.serverAddress.isIpAddress() }.coerceAtLeast(1)
        )
        if (groupId != null) {
            GroupUpdater.progress[groupId] = progress
        }
        val ipv6First = false

        coroutineScope {
            val lookupJobs = mutableListOf<Job>()
            for (profile in profiles) {
                when (profile) {
                    // SNI rewrite unsupported
                    is NaiveBean -> continue
                }

                if (profile.serverAddress.isIpAddress()) continue

                lookupJobs.add(launch(Dispatchers.IO) {
                    resolveSemaphore.withPermit {
                        try {
                            val results = if (
                                SagerNet.underlyingNetwork != null &&
                                ConnectionStateRepository.stateOrIdle.started
                            ) {
                                // FakeDNS
                                SagerNet.underlyingNetwork!!
                                    .getAllByName(profile.serverAddress)
                                    .filterNotNull()
                            } else {
                                // System DNS is enough when the VPN is connected.
                                InetAddress.getAllByName(profile.serverAddress).filterNotNull()
                            }
                            if (results.isEmpty()) error("empty response")
                            rewriteAddress(profile, results, ipv6First)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logs.d("Profile address lookup failed (${e.javaClass.simpleName})")
                        }
                        if (groupId != null) progress.increment()
                    }
                })
            }
            lookupJobs.joinAll()
        }
    }

    protected fun rewriteAddress(
        bean: AbstractBean, addresses: List<InetAddress>, ipv6First: Boolean
    ) {
        val address = addresses.sortedBy { (it is Inet4Address) xor ipv6First }[0].hostAddress
            ?: return

        with(bean) {
            when (this) {
                is HttpBean -> {
                    if (isTLS() && sni.isBlank()) sni = bean.serverAddress
                }
                is StandardV2RayBean -> {
                    when (security) {
                        "tls" -> if (sni.isBlank()) sni = bean.serverAddress
                    }
                }
                is TrojanBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
                is TrojanGoBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
                is HysteriaBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
            }

            bean.serverAddress = address
        }
    }

    companion object {

        private val resolveSemaphore = Semaphore(5)

        val updating = Collections.synchronizedSet<Long>(mutableSetOf())
        val progress = Collections.synchronizedMap<Long, Progress>(mutableMapOf())
        private val userUpdateGroupId = AtomicLong(0L)

        fun startUpdate(proxyGroup: ProxyGroup, byUser: Boolean): Boolean {
            val ownsUserUpdate = !byUser || userUpdateGroupId.compareAndSet(0L, proxyGroup.id)
            if (!ownsUserUpdate) {
                runOnIoDispatcher {
                    notifyUpdateAlreadyRunning(
                        proxyGroup,
                        anotherGroup = userUpdateGroupId.get() != proxyGroup.id,
                    )
                }
                return false
            }
            runOnIoDispatcher {
                executeUpdateReserved(proxyGroup, byUser, ownsUserUpdate = byUser)
            }
            return true
        }

        suspend fun executeUpdate(proxyGroup: ProxyGroup, byUser: Boolean): Boolean {
            val ownsUserUpdate = !byUser || userUpdateGroupId.compareAndSet(0L, proxyGroup.id)
            if (!ownsUserUpdate) {
                notifyUpdateAlreadyRunning(
                    proxyGroup,
                    anotherGroup = userUpdateGroupId.get() != proxyGroup.id,
                )
                return false
            }
            return executeUpdateReserved(proxyGroup, byUser, ownsUserUpdate = byUser)
        }

        private suspend fun executeUpdateReserved(
            proxyGroup: ProxyGroup,
            byUser: Boolean,
            ownsUserUpdate: Boolean,
        ): Boolean {
            var registeredUpdate = false
            var finishedGroup = proxyGroup
            try {
                if (!updating.add(proxyGroup.id)) {
                    if (byUser) notifyUpdateAlreadyRunning(proxyGroup, anotherGroup = false)
                    return false
                }
                registeredUpdate = true
                val subscription = proxyGroup.subscription
                    ?: error(app.getString(R.string.subscription_source_missing))
                val connected = ConnectionStateRepository.stateOrIdle.connected
                val userInterface = GroupManager.userInterface

                if (byUser && (subscription.link?.startsWith("http://") == true || subscription.updateWhenConnectedOnly) && !connected) {
                    val confirmed = userInterface?.let { ui ->
                        runCatching {
                            ui.confirm(app.getString(R.string.update_subscription_warning))
                        }.getOrDefault(false)
                    } ?: false
                    if (!confirmed) {
                        return false
                    }
                }
                runCatching {
                    GroupManager.userInterface?.onUpdateStarted(proxyGroup, byUser)
                }.onFailure {
                    Logs.w("Subscription progress UI failed (${it.javaClass.simpleName})")
                }

                return withSubscriptionUpdateLock(proxyGroup.id) {
                    val freshGroup = SagerDatabase.groupDao.getById(proxyGroup.id)
                        ?: error(app.getString(R.string.subscription_source_missing))
                    val freshSubscription = freshGroup.subscription
                        ?: error(app.getString(R.string.subscription_source_missing))
                    finishedGroup = freshGroup
                    RawUpdater.doUpdate(
                        freshGroup,
                        freshSubscription,
                        GroupManager.userInterface,
                        byUser,
                    )
                    true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val technicalMessage = sanitizeSubscriptionError(
                    e.readableMessage,
                    finishedGroup.subscription?.link,
                )
                Logs.w("Subscription update failed (${e.javaClass.simpleName}): $technicalMessage")
                val userMessage = app.getString(subscriptionFailureMessageRes(e))
                runCatching {
                    GroupManager.userInterface?.onUpdateFailure(proxyGroup, userMessage)
                }.onFailure {
                    Logs.w("Subscription failure UI failed (${it.javaClass.simpleName})")
                }
                return false
            } finally {
                withContext(NonCancellable) {
                    if (registeredUpdate) finishUpdate(finishedGroup)
                    if (ownsUserUpdate) userUpdateGroupId.compareAndSet(proxyGroup.id, 0L)
                }
            }
        }

        private suspend fun notifyUpdateAlreadyRunning(
            proxyGroup: ProxyGroup,
            anotherGroup: Boolean,
        ) {
            val message = app.getString(
                if (anotherGroup) R.string.subscription_update_another_running
                else R.string.subscription_update_already_running,
            )
            runCatching {
                GroupManager.userInterface?.onUpdateBusy(proxyGroup, message)
            }.onFailure {
                Logs.w("Subscription busy UI failed (${it.javaClass.simpleName})")
            }
        }

        suspend fun finishUpdate(proxyGroup: ProxyGroup) {
            updating.remove(proxyGroup.id)
            progress.remove(proxyGroup.id)
            runCatching { GroupManager.postUpdate(proxyGroup) }
                .onFailure {
                    Logs.w("Subscription completion listener failed (${it.javaClass.simpleName})")
                }
        }

    }

}

/**
 * Serializes subscription mutation across the UI and WorkManager processes. Deleting or
 * replacing a source must use this same lock, otherwise a stale updater can recreate orphaned
 * nodes after their parent group has been removed.
 */
internal suspend fun <T> withSubscriptionUpdateLock(
    groupId: Long,
    block: suspend () -> T,
): T = subscriptionProcessLocks.getOrPut(groupId) { Mutex() }.withLock {
    withContext(Dispatchers.IO) {
        val lockDir = File(app.filesDir, "subscription-update-locks").apply { mkdirs() }
        RandomAccessFile(File(lockDir, "$groupId.lock"), "rw").use { file ->
            file.channel.use { channel ->
                channel.lock().use { block() }
            }
        }
    }
}

private val subscriptionProcessLocks = ConcurrentHashMap<Long, Mutex>()

internal fun sanitizeSubscriptionError(message: String?, subscriptionLink: String?): String {
    var sanitized = message.orEmpty().trim().ifBlank {
        app.getString(R.string.subscription_update_failed)
    }
    subscriptionLink?.trim()?.takeIf(String::isNotEmpty)?.let { link ->
        sanitized = sanitized.replace(link, safeSubscriptionOrigin(link))
    }
    sanitized = subscriptionHttpUrlPattern.replace(sanitized) { match ->
        val address = match.value.trimEnd { it in ".,;:)]}" }
        val trailing = match.value.removePrefix(address)
        safeSubscriptionOrigin(address) + trailing
    }
    return sanitized.take(MAX_SUBSCRIPTION_ERROR_CHARACTERS)
}

private const val MAX_SUBSCRIPTION_ERROR_CHARACTERS = 500
private val subscriptionHttpUrlPattern = Regex("(?i)https?://[^\\s\\\"'<>]+")

private fun safeSubscriptionOrigin(raw: String): String = runCatching {
    val uri = URI(raw)
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host.orEmpty()
    require(scheme == "http" || scheme == "https")
    require(host.isNotBlank())
    val renderedHost = if (host.contains(':')) "[$host]" else host
    val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
    "$scheme://$renderedHost$port/…"
}.getOrElse { "subscription source" }

internal fun subscriptionFailureMessageRes(error: Throwable): Int {
    val reason = buildString {
        append(error.javaClass.simpleName.lowercase())
        append(' ')
        append(error.message.orEmpty().lowercase())
    }
    return when {
        listOf("timeout", "timed out", "deadline exceeded", "no recent network activity")
            .any(reason::contains) -> R.string.subscription_update_timeout_error
        listOf("unknownhost", "no such host", "name resolution", "dns")
            .any(reason::contains) -> R.string.subscription_update_dns_error
        listOf(
            "unsupported profile",
            "no proxies",
            "invalid clash",
            "profile document",
            "parse",
            "decode",
        ).any(reason::contains) -> R.string.subscription_update_format_error
        listOf(
            "connection",
            "network",
            "socket",
            "http",
            "tls",
            "ssl",
            "eof",
        ).any(reason::contains) -> R.string.subscription_update_network_error
        else -> R.string.subscription_update_failed
    }
}
