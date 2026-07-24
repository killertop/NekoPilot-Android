package io.nekohasekai.sagernet.bg

import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal class RuntimeReloadRecoveryException(cause: Throwable) :
    IllegalStateException("Unable to restore the last known-good VPN runtime", cause)

/**
 * Sole lifecycle owner for the official libbox command server. UI code interacts with the
 * existing Android service binder; it never invokes libbox directly.
 */
internal class OfficialLibboxController private constructor(
    private val lifecycle: NativeCommandServerLifecycle,
) : AutoCloseable {
    constructor(
        platform: PlatformInterface,
        onServiceStop: () -> Unit,
        onServiceReload: () -> Unit,
        closeDispatcher: ((() -> Unit) -> Unit) = ::dispatchNativeClose,
    ) : this(
        NativeCommandServerLifecycle(
            AndroidLibboxCommandServer(
                CommandServer(
                    object : CommandServerHandler {
                        override fun serviceStop() = onServiceStop()
                        override fun serviceReload() = onServiceReload()
                        override fun getSystemProxyStatus(): SystemProxyStatus =
                            SystemProxyStatus().apply {
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
                ),
            ),
            closeDispatcher,
        ),
    )

    internal constructor(
        commandServer: LibboxCommandServer,
        closeDispatcher: ((() -> Unit) -> Unit) = ::dispatchNativeClose,
    ) : this(NativeCommandServerLifecycle(commandServer, closeDispatcher))

    fun startOrReload(
        config: String,
        includePackages: Collection<String> = emptyList(),
        excludePackages: Collection<String> = emptyList(),
    ) {
        require(includePackages.isEmpty() || excludePackages.isEmpty()) {
            "Cannot include and exclude VPN packages simultaneously"
        }
        lifecycle.startOrReload(config, includePackages, excludePackages)
    }

    fun pause() = lifecycle.pause()

    fun wake() = lifecycle.wake()

    fun resetNetwork() = lifecycle.resetNetwork()

    /**
     * Sends native shutdown from another thread without waiting for an in-flight JNI start call
     * to release this controller's operation lock. Android's onDestroy path must use this method.
     */
    fun requestClose() = lifecycle.requestClose()

    override fun close() = lifecycle.close()
}

internal interface LibboxCommandServer {
    fun start()
    fun startOrReloadService(
        config: String,
        includePackages: Collection<String>,
        excludePackages: Collection<String>,
    )
    fun closeService()
    fun close()
    fun pause()
    fun wake()
    fun resetNetwork()
}

private class AndroidLibboxCommandServer(
    private val delegate: CommandServer,
) : LibboxCommandServer {
    override fun start() = delegate.start()
    override fun startOrReloadService(
        config: String,
        includePackages: Collection<String>,
        excludePackages: Collection<String>,
    ) = delegate.startOrReloadService(config, OverrideOptions().apply {
        if (includePackages.isNotEmpty()) includePackage = LibboxStringIterator(includePackages)
        if (excludePackages.isNotEmpty()) excludePackage = LibboxStringIterator(excludePackages)
    })
    override fun closeService() = delegate.closeService()
    override fun close() = delegate.close()
    override fun pause() = delegate.pause()
    override fun wake() = delegate.wake()
    override fun resetNetwork() = delegate.resetNetwork()
}

internal class NativeCommandServerLifecycle(
    private val commandServer: LibboxCommandServer,
    private val closeDispatcher: ((() -> Unit) -> Unit),
) : AutoCloseable {
    private val operationLock = Any()
    private val closeRequested = AtomicBoolean(false)
    private val nativeCloseStarted = AtomicBoolean(false)

    @Volatile
    private var started = false
    @Volatile
    private var serviceStarted = false

    fun startOrReload(
        config: String,
        includePackages: Collection<String>,
        excludePackages: Collection<String>,
    ) = synchronized(operationLock) {
        check(!closeRequested.get()) { "Official libbox controller is already closed" }
        val replacingHealthyService = serviceStarted
        try {
            if (!started) {
                commandServer.start()
                started = true
            }
            cancelIfCloseWasRequested()
            commandServer.startOrReloadService(config, includePackages, excludePackages)
            serviceStarted = true
            cancelIfCloseWasRequested()
        } catch (error: Throwable) {
            // Official libbox currently closes its old instance before starting the candidate.
            // Keep the command server open after a reload error so the caller can recreate its
            // last known-good config. The native service state is no longer observable here, so
            // do not advertise it as running while the caller performs that recovery. Initial
            // startup has no fallback, so partial first-start resources are still torn down.
            if (replacingHealthyService) serviceStarted = false
            if (!replacingHealthyService || closeRequested.get()) {
                closeRequested.set(true)
                closeNative()
            }
            throw error
        }
    }

    fun pause() = synchronized(operationLock) {
        if (started && serviceStarted && !closeRequested.get()) commandServer.pause()
    }

    fun wake() = synchronized(operationLock) {
        if (started && serviceStarted && !closeRequested.get()) commandServer.wake()
    }

    fun resetNetwork() = synchronized(operationLock) {
        if (started && serviceStarted && !closeRequested.get()) commandServer.resetNetwork()
    }

    fun requestClose() {
        if (!closeRequested.compareAndSet(false, true)) return
        closeDispatcher { closeNative() }
    }

    override fun close() {
        closeRequested.set(true)
        synchronized(operationLock) {
            closeNative()
        }
    }

    private fun closeNative() {
        if (!nativeCloseStarted.compareAndSet(false, true)) return
        if (serviceStarted) runCatching { commandServer.closeService() }
        commandServer.close()
        serviceStarted = false
        started = false
    }

    private fun cancelIfCloseWasRequested() {
        if (closeRequested.get()) {
            throw CancellationException("Official libbox controller was closed during startup")
        }
    }
}

private fun dispatchNativeClose(block: () -> Unit) {
    Thread(block, "NekoPilot-libbox-close").apply {
        isDaemon = true
        start()
    }
}
