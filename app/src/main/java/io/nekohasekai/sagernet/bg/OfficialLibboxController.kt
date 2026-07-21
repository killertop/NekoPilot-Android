package io.nekohasekai.sagernet.bg

import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.sagernet.ktx.Logs

/**
 * Sole lifecycle owner for the official libbox command server. UI code interacts with the
 * existing Android service binder; it never invokes libbox directly.
 */
internal class OfficialLibboxController(
    platform: PlatformInterface,
    private val onServiceStop: () -> Unit,
    private val onServiceReload: () -> Unit,
) : AutoCloseable {
    private val commandServer = CommandServer(
        object : CommandServerHandler {
            override fun serviceStop() = onServiceStop()
            override fun serviceReload() = onServiceReload()
            override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
                available = false
                enabled = false
            }
            override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
            override fun triggerNativeCrash() = error("Native crash trigger is disabled")
            override fun writeDebugMessage(message: String) {
                Logs.d("libbox: $message")
            }
            override fun connectSSHAgent(): Int = -1
        },
        platform,
    )
    private var started = false
    private var closed = false

    fun startOrReload(config: String, includePackages: Collection<String> = emptyList(), excludePackages: Collection<String> = emptyList()) {
        require(includePackages.isEmpty() || excludePackages.isEmpty()) {
            "Cannot include and exclude VPN packages simultaneously"
        }
        check(!closed) { "Official libbox controller is already closed" }
        try {
            if (!started) {
                commandServer.start()
                started = true
            }
            commandServer.startOrReloadService(config, OverrideOptions().apply {
                if (includePackages.isNotEmpty()) includePackage = LibboxStringIterator(includePackages)
                if (excludePackages.isNotEmpty()) excludePackage = LibboxStringIterator(excludePackages)
            })
        } catch (error: Throwable) {
            // A partial command-server start retains native resources unless it is closed here.
            close()
            throw error
        }
    }

    fun pause() {
        if (started) commandServer.pause()
    }

    fun wake() {
        if (started) commandServer.wake()
    }

    fun resetNetwork() {
        if (started) commandServer.resetNetwork()
    }

    override fun close() {
        if (closed) return
        closed = true
        if (started) runCatching { commandServer.closeService() }
        commandServer.close()
        started = false
    }
}
