package io.nekohasekai.sagernet.bg

/**
 * Process-local access to the currently owned VPN runtime.
 *
 * Runtime instances do not belong in the persistent DataStore. Identity-aware removal prevents
 * a stale Service callback from clearing a newer Service instance during Android restarts.
 */
internal object ServiceRuntimeRegistry {
    private val vpnSlot = OwnedRuntimeSlot<VpnService>()
    private val baseSlot = OwnedRuntimeSlot<BaseService.Interface>()

    val vpnService: VpnService?
        get() = vpnSlot.value

    val baseService: BaseService.Interface?
        get() = baseSlot.value

    fun registerVpn(service: VpnService) = vpnSlot.register(service)

    fun registerBase(service: BaseService.Interface) = baseSlot.register(service)

    fun unregisterVpn(service: VpnService) = vpnSlot.unregister(service)

    fun unregisterBase(service: BaseService.Interface) = baseSlot.unregister(service)
}

internal class OwnedRuntimeSlot<T : Any> {
    private val lock = Any()

    @Volatile
    private var current: T? = null

    val value: T?
        get() = current

    fun register(owner: T) = synchronized(lock) {
        current = owner
    }

    fun unregister(owner: T) = synchronized(lock) {
        if (current === owner) current = null
    }
}
