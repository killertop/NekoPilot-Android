package io.nekohasekai.sagernet.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.NodeTestOutcome
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxySelection
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.applyNodeTestOutcome
import io.nekohasekai.sagernet.database.clearNodeTestOutcome
import io.nekohasekai.sagernet.core.AutoNodeSelectionStatus
import io.nekohasekai.sagernet.core.ConnectionRecoveryReason
import io.nekohasekai.sagernet.core.ConnectionState
import io.nekohasekai.sagernet.core.SubscriptionDataCore
import io.nekohasekai.sagernet.core.ConnectionStateRepository
import io.nekohasekai.sagernet.fmt.KotlinSingBoxConfigInput
import io.nekohasekai.sagernet.fmt.KotlinSelectorNode
import io.nekohasekai.sagernet.fmt.buildKotlinSingBoxConfig
import io.nekohasekai.sagernet.fmt.loadKotlinRouteRules
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.Subnet
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import io.nekohasekai.sagernet.utils.sanitizePerAppPackages
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import android.net.VpnService as BaseVpnService

/** Makes the Android VPN policy handoff explicit instead of treating it as a core reload. */
internal fun shouldReconnectForVpnPolicyChange(
    state: ConnectionState,
    activePackages: Collection<String>?,
    requestedPackages: Collection<String>?,
): Boolean = when (state) {
    ConnectionState.Connected -> requestedPackages != null && activePackages != requestedPackages
    ConnectionState.Preparing,
    ConnectionState.Connecting,
    ConnectionState.Stopping -> true
    ConnectionState.Idle,
    ConnectionState.Error -> false
}

class VpnService : BaseVpnService(),
    BaseService.Interface {

    private data class ActiveRuntimeConfig(
        val content: String,
        val includePackages: List<String>,
        val healthCheckEndpoint: DataStore.LocalProxyEndpoint,
    )

    private class RuntimeProxyHealthException(message: String) : IllegalStateException(message)

    companion object {

        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        const val FAKEDNS_VLAN4_CLIENT = "198.18.0.0"
        const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"
        private const val MAX_LOCAL_PORT_BIND_ATTEMPTS = 3
        private const val AUTOMATIC_RECOVERY_PROBE_TIMEOUT_MS = 3_000
        private const val AUTOMATIC_RECOVERY_PRIMARY_URL =
            "https://www.gstatic.com/generate_204"
        private const val AUTOMATIC_RECOVERY_SECONDARY_URL =
            "https://www.cloudflare.com/cdn-cgi/trace"
        private const val LAST_KNOWN_GOOD_RESTORE_ATTEMPTS = 3
        private const val LAST_KNOWN_GOOD_RESTORE_DELAY_MS = 250L
        private const val CANDIDATE_PREFLIGHT_PORT_ATTEMPTS = 2
        private const val INITIAL_RUNTIME_HEALTH_DELAY_MS = 500L

        private val RUNTIME_HEALTH_PROBE_URLS = listOf(
            AUTOMATIC_RECOVERY_PRIMARY_URL,
            AUTOMATIC_RECOVERY_SECONDARY_URL,
        )
    }

    var conn: ParcelFileDescriptor? = null
    @Volatile
    private var startingCore: OfficialLibboxController? = null
    private var officialCore: OfficialLibboxController? = null
    private var officialPlatform: OfficialLibboxPlatform? = null
    private var autoNodeSelector: AutoNodeSelector? = null
    private var trafficMonitor: RuntimeTrafficMonitor? = null
    private var runtimeHealthJob: Job? = null
    private var activeLocalProxyEndpoint: DataStore.LocalProxyEndpoint? = null
    private var activeRuntimeConfig: ActiveRuntimeConfig? = null
    @Volatile
    private var reloadInProgress = false
    /** Reuses the currently registered TUN for a reload when its Android routing policy is stable. */
    @Volatile
    private var reuseTunDuringReload = false
    private val automaticStatusMutex = Mutex()
    private val reloadMutex = Mutex()
    private val tunLock = Any()
    private val reloadTun = StagedResourceSwap<ParcelFileDescriptor>()
    /**
     * Descriptors from superseded Android VPN interfaces are retained until a healthy runtime has
     * committed or the service is explicitly torn down. Closing one while recovery is still in
     * flight can release the only system VPN registration on OEM implementations that do not
     * immediately finish the Builder.establish handoff.
     */
    private val retiredTunDescriptors = ArrayList<ParcelFileDescriptor>()
    /** Callback descriptors are valid only until libbox synchronously duplicates their raw FD. */
    private val nativeCallbackTunDescriptorLease = NativeCallbackDescriptorLease<ParcelFileDescriptor> { error ->
        Logs.w("Native callback TUN descriptor cleanup failed (${error.javaClass.simpleName})")
    }

    private var metered = false

    override var upstreamInterfaceName: String? = null

    override suspend fun startCore(profile: ProxyEntity) {
        runtimeHealthJob?.cancel()
        runtimeHealthJob = null
        ServiceRuntimeRegistry.registerVpn(this)
        OfficialLibboxRuntime.ensureSetup(this)
        val ruleAssets = RuleAssetsUpdater.runtimeSnapshot(this)
        val persistedEndpoint = DataStore.prepareLocalProxyEndpoint(refresh = true)
        val allowLanAccess = DataStore.allowAccess
        val includePackages = loadIncludedPackages()
        val routeRules = loadKotlinRouteRules()
        val selectorProfiles = loadSelectorProfiles(profile)
        val sessionSuffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val selectorTag = "proxy-$sessionSuffix"
        val attemptedPorts = linkedSetOf<Int>()
        var mixedPort = resolveAvailableMixedPort(
            persistedEndpoint.port,
            allowLanAccess,
        )
        var bindAttempt = 0
        while (true) {
            bindAttempt++
            if (mixedPort != DataStore.mixedPort) {
                Logs.w("Local proxy port ${DataStore.mixedPort} is unavailable; using $mixedPort")
                DataStore.mixedPort = mixedPort
                DataStore.configurationStore.flush()
            }
            val endpoint = persistedEndpoint.copy(port = mixedPort)
            val healthCheckEndpoint = endpoint.copy(
                port = allocateEphemeralLoopbackPort(setOf(endpoint.port)),
            )
            val config = buildKotlinSingBoxConfig(
                KotlinSingBoxConfigInput(
                    selected = profile.requireBean(),
                    selectedProfileId = profile.id,
                    selectorNodes = selectorProfiles.map {
                        KotlinSelectorNode(it.id, it.requireBean())
                    },
                    routeRules = routeRules,
                    proxyTag = selectorTag,
                    useVpn = true,
                    mixedPort = endpoint.port,
                    healthCheckPort = healthCheckEndpoint.port,
                    mixedUsername = endpoint.username,
                    mixedPassword = endpoint.password,
                    allowAccess = allowLanAccess,
                    ruleAssetDirectory = ruleAssets.directory.absolutePath,
                ),
            )
            // Reject schema/core incompatibilities before libbox allocates a command server or
            // asks Android for a TUN descriptor. A failed config therefore cannot replace the
            // last healthy runtime with a partially initialized one.
            Libbox.checkConfig(config)
            val platform = OfficialLibboxPlatform(
                this,
                ::openTunFromOfficialLibbox,
                ::protect,
            )
            val controller = OfficialLibboxController(
                platform = platform,
                onServiceStop = { data.lifecycle.scope.launch { stopRunner(false) } },
                onServiceReload = { reload() },
            )
            if (!data.lifecycle.commitIfAlive { startingCore = controller }) {
                controller.close()
                throw CancellationException("VPN service was destroyed before core startup")
            }
            try {
                nativeCallbackTunDescriptorLease.duringNativeCall {
                    controller.startOrReload(config, includePackages)
                }
                val monitor = RuntimeTrafficMonitor(
                    sessionProxyTag = selectorTag,
                    currentProfileId = { data.profile?.id ?: DataStore.currentProfile },
                )
                var previousMonitor: RuntimeTrafficMonitor? = null
                val accepted = data.lifecycle.commitIfAlive {
                    if (startingCore === controller) startingCore = null
                    previousMonitor = trafficMonitor
                    officialPlatform = platform
                    officialCore = controller
                    activeLocalProxyEndpoint = endpoint
                    activeRuntimeConfig = ActiveRuntimeConfig(
                        content = config,
                        includePackages = includePackages,
                        healthCheckEndpoint = healthCheckEndpoint,
                    )
                    trafficMonitor = monitor
                }
                if (!accepted) {
                    monitor.close()
                    closeTun()
                    throw CancellationException("VPN service was destroyed during core startup")
                }
                previousMonitor?.close()
                break
            } catch (error: Throwable) {
                if (startingCore === controller) startingCore = null
                runCatching { controller.close() }
                officialPlatform = null
                if (
                    bindAttempt >= MAX_LOCAL_PORT_BIND_ATTEMPTS ||
                    !isAddressAlreadyInUse(error)
                ) {
                    throw error
                }
                closeTun()
                attemptedPorts += mixedPort
                val replacement = resolveAvailableMixedPort(
                    preferredPort = persistedEndpoint.port,
                    allowLanAccess = allowLanAccess,
                    excludedPorts = attemptedPorts,
                )
                Logs.w(
                    "Local proxy port $mixedPort was claimed during startup; " +
                        "retrying on $replacement",
                )
                mixedPort = replacement
            }
        }
        publishAutomaticSelectionStatus(null)
        if (selectorProfiles.size > 1) {
            val byTag = selectorProfiles.associateBy { AutoNodeSelector.nodeTag(it.id) }
            val selector = AutoNodeSelector(
                selectorTag = selectorTag,
                profilesByTag = byTag,
                initialProfileId = profile.id,
                initiallyEnabled = DataStore.autoSwitch,
                initialNetworkIdentity = validatedPhysicalNetworkIdentity(),
                nextCandidate = { currentId, excludedIds ->
                    nextFailoverCandidate(selectorProfiles, currentId, excludedIds)
                },
                currentPathHealthy = ::isCurrentProxyPathHealthy,
                onSelected = ::commitSelection,
                onStatus = ::publishAutomaticSelectionStatus,
                canSelect = { !reloadInProgress && isSelectorProfileCurrent(it) },
            )
            selector.start()
            if (!data.lifecycle.commitIfAlive { autoNodeSelector = selector }) {
                selector.close()
                throw CancellationException("VPN service was destroyed during selector startup")
            }
        }
        scheduleRuntimeHealthCheck(
            profile = profile,
            endpoint = checkNotNull(activeRuntimeConfig).healthCheckEndpoint,
        )
    }

    /**
     * Startup proves that libbox and Android's TUN are alive. This follow-up probe checks the
     * selected node's real HTTPS exit after the connection has been published, so a connected
     * VPN can still explain an unusable node without changing the connection button to Error.
     */
    private fun scheduleRuntimeHealthCheck(
        profile: ProxyEntity,
        endpoint: DataStore.LocalProxyEndpoint,
    ) {
        val core = officialCore ?: return
        runtimeHealthJob?.cancel()
        runtimeHealthJob = data.lifecycle.scope.launch(Dispatchers.IO) {
            try {
                delay(INITIAL_RUNTIME_HEALTH_DELAY_MS)
                if (validatedPhysicalNetworkIdentity() == null) return@launch
                if (core !== officialCore || !runtimeProfileMatches(data.profile, profile)) {
                    return@launch
                }
                requireRuntimeProxyHealth(endpoint)
                clearRuntimeEgressUnavailable(profile)
            } catch (error: CancellationException) {
                throw error
            } catch (error: RuntimeProxyHealthException) {
                if (core !== officialCore || !runtimeProfileMatches(data.profile, profile)) {
                    return@launch
                }
                // Losing Wi-Fi/cellular during the probe is inconclusive; do not persist a node
                // failure that would make the UI blame every otherwise healthy server.
                if (validatedPhysicalNetworkIdentity() == null) return@launch
                markRuntimeEgressUnavailable(profile, error.message.orEmpty())
            } catch (error: Throwable) {
                // A probe implementation failure must not turn a healthy core into a false
                // unavailable result. The reload path still retains its strict health gate.
                Logs.w("Runtime egress check failed unexpectedly", error)
            }
        }
    }

    private suspend fun markRuntimeEgressUnavailable(
        profile: ProxyEntity,
        message: String,
    ) {
        val current = SagerDatabase.proxyDao.getById(profile.id) ?: return
        if (
            current.groupId != profile.groupId || current.type != profile.type ||
            current.configRevision != profile.configRevision || current.status == 2
        ) return
        current.applyNodeTestOutcome(
            NodeTestOutcome.Unavailable(
                reason = NodeTestOutcome.FailureReason.RUNTIME_EGRESS,
                message = message.ifBlank { getString(R.string.node_egress_unavailable_detail) },
            ),
        )
        ProfileManager.updateTestResults(listOf(current))
    }

    private suspend fun clearRuntimeEgressUnavailable(profile: ProxyEntity) {
        val current = SagerDatabase.proxyDao.getById(profile.id) ?: return
        if (
            current.groupId != profile.groupId || current.type != profile.type ||
            current.configRevision != profile.configRevision || current.status != 4
        ) return
        current.clearNodeTestOutcome()
        ProfileManager.updateTestResults(listOf(current))
    }

    /**
     * Reloads in two phases. The first is an isolated no-TUN core in :preflight, which proves the
     * selected outbound/DNS path can serve real egress without touching the current VPN. For the
     * normal profile/node reload, the live candidate duplicates the already-registered TUN, so the
     * native service boundary does not require an Android VPN handoff. A change to Android's VPN
     * package policy is rejected before this transaction starts; applying it requires an explicit
     * stop/start, which is the only path allowed to replace the system VPN registration.
     */
    override suspend fun reloadCore() = reloadMutex.withLock {
        val selectorBeingReloaded = autoNodeSelector
        reloadInProgress = true
        try {
            selectorBeingReloaded?.blockForReload()
            val selectorSnapshot = selectorBeingReloaded?.captureReloadSnapshot()
            reloadCoreLocked(
                selectorBeingReloaded = selectorBeingReloaded,
                selectorSnapshot = selectorSnapshot,
            )
        } finally {
            reloadInProgress = false
            if (autoNodeSelector === selectorBeingReloaded) {
                selectorBeingReloaded?.unblockAfterReload()
            }
        }
    }

    override suspend fun reconnectVpnPolicy() {
        val state = data.state
        val requestedPackages = if (state == ConnectionState.Connected) {
            runCatching { loadIncludedPackages() }.getOrElse { error ->
                // Invalid/empty per-app selections must not tear down the current VPN policy.
                data.binder.stateChanged(state, error.readableMessage)
                return
            }
        } else {
            null
        }
        if (!shouldReconnectForVpnPolicyChange(
                state = state,
                activePackages = activeRuntimeConfig?.includePackages,
                requestedPackages = requestedPackages,
            )
        ) return

        // Android has no atomic update API for Builder.addAllowedApplication(). Make the short
        // reconnect visible before the lifecycle tears down the old TUN, then let stopRunner's
        // existing restart gate start exactly one fresh VPN using the persisted policy.
        data.binder.stateChanged(data.state, getString(R.string.vpn_policy_reconnecting))
        stopRunner(restart = true)
    }

    private suspend fun reloadCoreLocked(
        selectorBeingReloaded: AutoNodeSelector?,
        selectorSnapshot: AutoNodeSelector.ReloadSnapshot?,
    ) {
        val core = checkNotNull(officialCore) { "Cannot reload before libbox is connected" }
        val activeProfileAtReload = data.profile
        val ruleAssets = RuleAssetsUpdater.runtimeSnapshot(this)
        val profile = ProfileManager.ensureValidSelection()
            ?: error(getString(R.string.profile_empty))
        // Preserve the raw persisted rows for the eventual CAS. A valid legacy profile can still
        // have an absent/stale group row, which must not make a failed reload leave Home stuck on
        // an unstarted candidate.
        val candidateSelectionAtReload = DataStore.readProxySelection()
            .takeIf { it.profileId == profile.id }
        val activeEndpoint = checkNotNull(activeLocalProxyEndpoint) {
            "Cannot reload before the local proxy is connected"
        }
        val activeRuntime = checkNotNull(activeRuntimeConfig) {
            "Cannot reload before the runtime configuration is available"
        }
        val persistedEndpoint = DataStore.prepareLocalProxyEndpoint(refresh = true)
        val endpoint = persistedEndpoint.copy(port = activeEndpoint.port)
        val candidateHealthEndpoint = endpoint.copy(
            port = allocateEphemeralLoopbackPort(
                setOf(endpoint.port, activeRuntime.healthCheckEndpoint.port),
            ),
        )
        val allowLanAccess = DataStore.allowAccess
        val includePackages = loadIncludedPackages()
        val routeRules = loadKotlinRouteRules()
        val reuseCurrentTun = activeRuntime.includePackages == includePackages
        val selectorProfiles = loadSelectorProfiles(profile)
        val selectorTag = "proxy-" +
            UUID.randomUUID().toString().replace("-", "").take(12)
        val config = try {
            check(reuseCurrentTun) {
                "Android VPN package routing changed; reconnect is required to apply it safely"
            }
            check(synchronized(tunLock) { conn != null }) {
                "Cannot reload without an active Android VPN interface"
            }
            val candidateConfig = buildKotlinSingBoxConfig(
                KotlinSingBoxConfigInput(
                    selected = profile.requireBean(),
                    selectedProfileId = profile.id,
                    selectorNodes = selectorProfiles.map {
                        KotlinSelectorNode(it.id, it.requireBean())
                    },
                    routeRules = routeRules,
                    proxyTag = selectorTag,
                    useVpn = true,
                    mixedPort = endpoint.port,
                    healthCheckPort = candidateHealthEndpoint.port,
                    mixedUsername = endpoint.username,
                    mixedPassword = endpoint.password,
                    allowAccess = allowLanAccess,
                    ruleAssetDirectory = ruleAssets.directory.absolutePath,
                ),
            )
            // Do all fallible candidate construction, schema validation and real proxy egress
            // validation while the old service, selector clients, TUN descriptor and Android
            // Connected state remain untouched.
            Libbox.checkConfig(candidateConfig)
            preflightCandidateCore(
                profile = profile,
                selectorProfiles = selectorProfiles,
                candidateEndpoint = endpoint,
                ruleAssetDirectory = ruleAssets.directory.absolutePath,
            )
            candidateConfig
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // The UI commits its selection before asking the service to reload. If this candidate
            // never crossed the native replacement boundary, restore the selection to the core
            // that is still carrying traffic so Home cannot remain in "Switching…" forever.
            restoreActiveSelectionAfterRejectedReload(
                candidateSelection = candidateSelectionAtReload,
                activeAtReload = activeProfileAtReload,
                rejected = error,
            )
            throw error
        }

        beginTunReload()
        reuseTunDuringReload = reuseCurrentTun
        try {
            if (reuseTunDuringReload) retainCurrentTunForReload()
            nativeCallbackTunDescriptorLease.duringNativeCall {
                core.startOrReload(config, includePackages)
            }
            // The candidate must pass a real proxy request before its descriptor becomes the
            // published runtime. Stable TUN-policy reloads use a duplicate of the current system
            // interface, so this health gate does not release or replace the registered VPN.
            requireRuntimeProxyHealth(candidateHealthEndpoint)
            commitTunReload()
        } catch (error: Throwable) {
            reuseTunDuringReload = false
            if (error is CancellationException) throw error
            if (error is TunRetentionException) {
                abortTunReload()
                restoreActiveSelectionAfterRejectedReload(
                    candidateSelection = candidateSelectionAtReload,
                    activeAtReload = activeProfileAtReload,
                    rejected = error,
                )
                throw error
            }
            // Keep the staged candidate descriptor available while the last-known-good runtime is
            // recreated. Closing it during recovery would make the native transition observable
            // as a released system VPN on Android implementations that still own that interface.
            promoteStagedTunForRecovery()
            if (!restoreLastKnownGoodRuntime(
                    core = core,
                    candidateFailure = error,
                    selectorBeingReloaded = selectorBeingReloaded,
                    selectorSnapshot = selectorSnapshot,
                )
            ) {
                // The old runtime is no longer trustworthy. Restore the persisted selection before
                // converging to Error so Activity reconstruction cannot point at the rejected node.
                restoreActiveSelectionAfterRejectedReload(
                    candidateSelection = candidateSelectionAtReload,
                    activeAtReload = activeProfileAtReload,
                    rejected = error,
                )
                reportReloadRecoveryNeeded()
                throw RuntimeReloadRecoveryException(error)
            }
            restoreActiveSelectionAfterRejectedReload(
                candidateSelection = candidateSelectionAtReload,
                activeAtReload = activeProfileAtReload,
                rejected = error,
            )
            throw error
        } finally {
            reuseTunDuringReload = false
        }

        val monitor = RuntimeTrafficMonitor(
            sessionProxyTag = selectorTag,
            currentProfileId = { data.profile?.id ?: DataStore.currentProfile },
        )
        val selector = if (selectorProfiles.size > 1) {
            val byTag = selectorProfiles.associateBy { AutoNodeSelector.nodeTag(it.id) }
            AutoNodeSelector(
                selectorTag = selectorTag,
                profilesByTag = byTag,
                initialProfileId = profile.id,
                initiallyEnabled = DataStore.autoSwitch,
                initialNetworkIdentity = validatedPhysicalNetworkIdentity(),
                nextCandidate = { currentId, excludedIds ->
                    nextFailoverCandidate(selectorProfiles, currentId, excludedIds)
                },
                currentPathHealthy = ::isCurrentProxyPathHealthy,
                onSelected = ::commitSelection,
                onStatus = ::publishAutomaticSelectionStatus,
                canSelect = { !reloadInProgress && isSelectorProfileCurrent(it) },
            ).also(AutoNodeSelector::start)
        } else {
            null
        }

        var previousMonitor: RuntimeTrafficMonitor? = null
        var previousSelector: AutoNodeSelector? = null
        val accepted = data.lifecycle.commitIfAlive {
            previousMonitor = trafficMonitor
            previousSelector = autoNodeSelector
            trafficMonitor = monitor
            autoNodeSelector = selector
            activeLocalProxyEndpoint = endpoint
            activeRuntimeConfig = ActiveRuntimeConfig(
                content = config,
                includePackages = includePackages,
                healthCheckEndpoint = candidateHealthEndpoint,
            )
            data.profile = profile
            data.attemptedProfileId = profile.id
        }
        if (!accepted) {
            monitor.close()
            selector?.close()
            throw CancellationException("VPN service was destroyed during candidate reload")
        }
        previousMonitor?.close()
        previousSelector?.close()
        DataStore.currentProfile = profile.id
        DataStore.configurationStore.flush()
        clearRuntimeEgressUnavailable(profile)
        data.notification?.postNotificationTitle(ServiceNotification.genTitle(profile))
        data.binder.stateChanged(data.state, null)
        publishAutomaticSelectionStatus(null)
    }

    /**
     * Reverts only the exact persisted candidate selected for this reload. A later user tap or an
     * automatic in-place selector change wins the compare-and-set and is never overwritten.
    */
    private suspend fun restoreActiveSelectionAfterRejectedReload(
        candidateSelection: ProxySelection?,
        activeAtReload: ProxyEntity?,
        rejected: Throwable,
    ) {
        try {
            val active = activeAtReload ?: return
            // A newer user tap, automatic selector change, or even a group-navigation update
            // must win over this stale reload owner.
            val expectedSelection = candidateSelection ?: return
            if (!runtimeProfileMatches(data.profile, active)) return
            val activeSelection = ProxySelection(active.id, active.groupId)
            if (!shouldRestoreSelectionAfterCandidateFailure(expectedSelection, activeSelection)) return
            if (!DataStore.compareAndSetProxySelection(expectedSelection, activeSelection)) return

            DataStore.configurationStore.flush()
            Logs.w("Candidate reload failed; restored the selected node to the active VPN runtime")
            sendBroadcast(
                Intent(Action.PROFILES_CHANGED).setPackage(packageName),
                "$packageName.permission.SERVICE_CONTROL",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Never let a best-effort UI/persistence repair mask the original rejected reload.
            rejected.addSuppressed(error)
            Logs.w("Unable to restore the selected node after a rejected reload (${error.javaClass.simpleName})")
        }
    }

    /**
     * Uses the same controller because upstream leaves it reusable after a failed reload. Each
     * retry retains the latest established Android VPN descriptor until a healthy replacement has
     * taken over. A failure here is deliberately reported to the caller but never maps to
     * stopRunner: only an explicit stop, permission revocation, or service destruction may release
     * the Android VPN registration.
     */
    private suspend fun restoreLastKnownGoodRuntime(
        core: OfficialLibboxController,
        candidateFailure: Throwable,
        selectorBeingReloaded: AutoNodeSelector?,
        selectorSnapshot: AutoNodeSelector.ReloadSnapshot?,
    ): Boolean {
        val previous = activeRuntimeConfig ?: return false
        repeat(LAST_KNOWN_GOOD_RESTORE_ATTEMPTS) { attempt ->
            beginTunReload()
            reuseTunDuringReload = true
            try {
                retainCurrentTunForReload()
                nativeCallbackTunDescriptorLease.duringNativeCall {
                    core.startOrReload(previous.content, previous.includePackages)
                }
                selectorSnapshot?.let { snapshot ->
                    check(selectorBeingReloaded?.restoreReloadSnapshot(snapshot) == true) {
                        "Unable to restore the previous selected outbound"
                    }
                }
                requireRuntimeProxyHealth(previous.healthCheckEndpoint)
                commitTunReload()
                Logs.w("Candidate reload failed; restored the last known-good VPN runtime")
                return true
            } catch (restoreFailure: Throwable) {
                reuseTunDuringReload = false
                if (restoreFailure is CancellationException) throw restoreFailure
                if (restoreFailure is TunRetentionException) {
                    abortTunReload()
                    candidateFailure.addSuppressed(restoreFailure)
                    return false
                }
                // Keep the current Android VPN interface open while the next LKG attempt starts.
                // Closing a staged/current descriptor here would release the system proxy during
                // recovery.
                promoteStagedTunForRecovery()
                candidateFailure.addSuppressed(restoreFailure)
                if (attempt + 1 < LAST_KNOWN_GOOD_RESTORE_ATTEMPTS) {
                    delay(LAST_KNOWN_GOOD_RESTORE_DELAY_MS)
                }
            } finally {
                reuseTunDuringReload = false
            }
        }
        return false
    }

    /**
     * Starts an equivalent no-TUN candidate in a process with its own libbox command socket. The
     * temporary inbound is never published through Binder or DataStore, so failure cannot alter
     * the active local proxy endpoint.
     */
    private suspend fun preflightCandidateCore(
        profile: ProxyEntity,
        selectorProfiles: List<ProxyEntity>,
        candidateEndpoint: DataStore.LocalProxyEndpoint,
        ruleAssetDirectory: String,
    ) {
        val excludedPorts = linkedSetOf(candidateEndpoint.port)
        repeat(CANDIDATE_PREFLIGHT_PORT_ATTEMPTS) { attempt ->
            val candidatePort = allocateEphemeralLoopbackPort(excludedPorts)
            excludedPorts += candidatePort
            val candidateTag = "preflight-" + UUID.randomUUID().toString().replace("-", "").take(12)
            val candidateConfig = buildKotlinSingBoxConfig(
                KotlinSingBoxConfigInput(
                    selected = profile.requireBean(),
                    selectedProfileId = profile.id,
                    selectorNodes = selectorProfiles.map {
                        KotlinSelectorNode(it.id, it.requireBean())
                    },
                    // The isolated preflight is evidence that this candidate itself can carry
                    // DNS and HTTP egress. Inheriting a user's `direct` route here would let a
                    // probe succeed through the physical network while the candidate is dead.
                    routeRules = emptyList(),
                    proxyTag = candidateTag,
                    useVpn = false,
                    mixedPort = candidatePort,
                    mixedUsername = candidateEndpoint.username,
                    mixedPassword = candidateEndpoint.password,
                    allowAccess = false,
                    ruleAssetDirectory = ruleAssetDirectory,
                ),
            )
            try {
                CorePreflightClient.requireHealthy(
                    context = this,
                    config = candidateConfig,
                    port = candidatePort,
                    username = candidateEndpoint.username,
                    password = candidateEndpoint.password,
                    probeUrls = RUNTIME_HEALTH_PROBE_URLS,
                    probeTimeoutMs = AUTOMATIC_RECOVERY_PROBE_TIMEOUT_MS,
                )
                return
            } catch (error: CandidateCorePreflightException) {
                if (!error.retryablePortConflict || attempt + 1 == CANDIDATE_PREFLIGHT_PORT_ATTEMPTS) {
                    throw error
                }
                Logs.w("Candidate preflight port was claimed; retrying on a fresh loopback port")
            }
        }
    }

    /** New connections must succeed through the exact candidate core before UI accepts the reload. */
    private fun requireRuntimeProxyHealth(endpoint: DataStore.LocalProxyEndpoint) {
        val healthy = RUNTIME_HEALTH_PROBE_URLS.any { url ->
            try {
                probeUrlThroughLocalMixedProxy(
                    url = url,
                    port = endpoint.port,
                    username = endpoint.username,
                    password = endpoint.password,
                    timeoutMs = AUTOMATIC_RECOVERY_PROBE_TIMEOUT_MS,
                )
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                false
            }
        }
        if (!healthy) {
            throw RuntimeProxyHealthException(getString(R.string.node_egress_unavailable_detail))
        }
    }

    /** Records an exhausted recovery; BaseService converges the connection to Error. */
    private suspend fun reportReloadRecoveryNeeded() {
        val message = getString(R.string.vpn_reload_recovery_needed)
        // An exhausted recovery is a terminal failure for this run. Require an explicit user
        // reconnect instead of allowing START_STICKY or the boot receiver to loop forever.
        DataStore.serviceAutoStart = false
        withContext(Dispatchers.IO) {
            runCatching { DataStore.configurationStore.flush() }
                .onFailure { Logs.w("Unable to persist terminal reload gate", it) }
        }
        Logs.e(message)
    }

    private suspend fun loadIncludedPackages(): List<String> {
        val policy = DataStore.readPerAppProxyPolicy()
        if (!policy.enabled) return emptyList()
        val selectedPackages = policy.serializedPackages.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filter { candidate ->
                runCatching { packageManager.getApplicationInfo(candidate, 0) }.isSuccess
            }
            .distinct()
            .toList()
        require(selectedPackages.isNotEmpty()) {
            getString(R.string.app_proxy_empty_selection)
        }
        return (selectedPackages + packageName).distinct().sorted()
    }

    private suspend fun loadSelectorProfiles(selected: ProxyEntity): List<ProxyEntity> {
        val candidateRows = SagerDatabase.proxyDao.getLatencyCandidates(
            excludedType = ProxyEntity.TYPE_CONFIG,
            selectedId = selected.id,
            limit = SubscriptionDataCore.MAX_FAILOVER_SESSION_CANDIDATES,
        )
        val candidates = SagerDatabase.proxyDao.getEntities(candidateRows.map { it.id })
            .associateBy(ProxyEntity::id)
        return buildList {
            add(selected)
            candidateRows.asSequence()
                .filter { it.id != selected.id }
                .take(SubscriptionDataCore.MAX_FAILOVER_SESSION_CANDIDATES - 1)
                .mapNotNull { candidates[it.id] }
                .forEach(::add)
        }
    }

    private suspend fun nextFailoverCandidate(
        sessionSnapshots: Collection<ProxyEntity>,
        currentId: Long,
        excludedIds: Set<Long>,
    ): ProxyEntity? {
        val snapshots = sessionSnapshots.associateBy(ProxyEntity::id)
        val currentRows = SagerDatabase.proxyDao.getEntities(snapshots.keys.toList())
        val candidateId = SubscriptionDataCore.selectFailoverCandidate(
            currentId = currentId,
            candidates = currentRows.map {
                SubscriptionDataCore.FailoverCandidate(it.id, it.status, it.ping)
            },
            excludedIds = excludedIds,
        ) ?: return null
        val current = currentRows.firstOrNull { it.id == candidateId } ?: return null
        val snapshot = snapshots[candidateId] ?: return null
        return current.takeIf {
            it.groupId == snapshot.groupId && it.type == snapshot.type &&
                it.configRevision == snapshot.configRevision
        }
    }

    private fun validatedPhysicalNetworkIdentity(): Long? {
        val network = SagerNet.underlyingNetwork ?: return null
        val capabilities = SagerNet.connectivity.getNetworkCapabilities(network) ?: return null
        return network.networkHandle.takeIf {
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    private suspend fun commitSelection(profile: ProxyEntity) {
        withContext(Dispatchers.IO) {
            DataStore.selectProxy(profile.id, profile.groupId)
            DataStore.currentProfile = profile.id
            DataStore.configurationStore.flush()
        }
        data.profile = profile
        data.notification?.postNotificationTitle(ServiceNotification.genTitle(profile))
        data.binder.stateChanged(data.state, null)
        activeRuntimeConfig?.healthCheckEndpoint?.let { endpoint ->
            scheduleRuntimeHealthCheck(profile, endpoint)
        }
        sendBroadcast(
            Intent(Action.PROFILES_CHANGED).setPackage(packageName),
            "$packageName.permission.SERVICE_CONTROL",
        )
    }

    private suspend fun publishAutomaticSelectionStatus(status: AutoNodeSelectionStatus?) {
        automaticStatusMutex.withLock {
            withContext(Dispatchers.IO) {
                DataStore.autoSwitchStatus = status?.encode().orEmpty()
                DataStore.configurationStore.flush()
            }
            sendBroadcast(
                Intent(Action.AUTO_SWITCH_STATUS_CHANGED).setPackage(packageName),
                "$packageName.permission.SERVICE_CONTROL",
            )
        }
    }

    /**
     * Confirms a suspected outage without creating latency results or running on a timer.
     * An unavailable physical network is deliberately treated as inconclusive so an ISP/Wi-Fi
     * outage cannot make NekoPilot rotate through every healthy proxy node.
     */
    private suspend fun isCurrentProxyPathHealthy(): Boolean {
        if (validatedPhysicalNetworkIdentity() == null) return true
        val endpoint = activeRuntimeConfig?.healthCheckEndpoint ?: return true
        // The private health inbound is pinned to the selector before any user direct route or
        // DNS rule. The fixed destinations therefore prove proxy egress rather than a bypass.
        val urls = listOf(AUTOMATIC_RECOVERY_PRIMARY_URL, AUTOMATIC_RECOVERY_SECONDARY_URL)
        val results = supervisorScope {
            urls.map { url ->
                async(Dispatchers.IO) {
                    try {
                        probeUrlThroughLocalMixedProxy(
                            url = url,
                            port = endpoint.port,
                            username = endpoint.username,
                            password = endpoint.password,
                            timeoutMs = AUTOMATIC_RECOVERY_PROBE_TIMEOUT_MS,
                        )
                        true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        false
                    }
                }
            }.awaitAll()
        }
        return results.any { it } || validatedPhysicalNetworkIdentity() == null
    }

    private suspend fun isSelectorProfileCurrent(snapshot: ProxyEntity): Boolean =
        withContext(Dispatchers.IO) {
            SagerDatabase.proxyDao.getById(snapshot.id)?.let { current ->
                current.type == snapshot.type && current.configRevision == snapshot.configRevision
            } == true
        }

    override fun selectProfile(profileId: Long): Boolean {
        if (!data.state.connected || reloadInProgress) return false
        val selector = autoNodeSelector ?: return false
        val current = SagerDatabase.proxyDao.getById(profileId) ?: return false
        // The selector contains an immutable session snapshot. Edited nodes use the normal
        // reload path; unchanged nodes switch in place without disturbing existing streams.
        return selector.selectManually(current)
    }

    override fun setAutomaticNodeSwitchingEnabled(enabled: Boolean): Boolean {
        if (!data.state.connected || reloadInProgress) return false
        val selector = autoNodeSelector ?: return false
        selector.setEnabled(enabled)
        return true
    }

    override suspend fun stopCore() {
        val monitor = trafficMonitor
        trafficMonitor = null
        val selector = autoNodeSelector
        autoNodeSelector = null
        runtimeHealthJob?.cancel()
        runtimeHealthJob = null
        val core = officialCore
        officialCore = null
        officialPlatform = null
        activeLocalProxyEndpoint = null
        activeRuntimeConfig = null

        var cleanupFailure: Throwable? = null
        fun cleanup(block: () -> Unit) {
            runCatching(block).onFailure { error ->
                if (cleanupFailure == null) cleanupFailure = error
                Logs.w("VPN runtime cleanup failed (${error.javaClass.simpleName})")
            }
        }
        cleanup { monitor?.close() }
        cleanup { selector?.close() }
        cleanup { core?.close() }
        runCatching { publishAutomaticSelectionStatus(null) }.onFailure { error ->
            if (cleanupFailure == null) cleanupFailure = error
            Logs.w("VPN status cleanup failed (${error.javaClass.simpleName})")
        }
        closeTun()
        cleanupFailure?.let { throw it }
    }

    override fun pauseCore() {
        officialCore?.pause()
    }

    override fun wakeCore() {
        officialCore?.wake()
    }

    override fun resetCoreNetwork() {
        officialCore?.resetNetwork()
    }

    override fun urlTest(): Int {
        check(data.state.connected) { "core not started" }
        val endpoint = checkNotNull(activeLocalProxyEndpoint) { "local proxy not started" }
        val started = SystemClock.elapsedRealtime()
        probeUrlThroughLocalMixedProxy(
            url = DataStore.connectionTestURL,
            port = endpoint.port,
            username = endpoint.username,
            password = endpoint.password,
            timeoutMs = 3_000,
        )
        return (SystemClock.elapsedRealtime() - started).toInt()
    }

    override fun localProxyEndpoint(): DataStore.LocalProxyEndpoint? = activeLocalProxyEndpoint

    override fun trafficSnapshot() = trafficMonitor?.snapshot()?.toBundle()

    override var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:vpn")
            .apply { acquire() }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override suspend fun killProcesses() {
        try {
            // Stop native readers/writers before releasing the descriptor they use.
            super.killProcesses()
        } finally {
            closeTun()
        }
    }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override val data = BaseService.Data(this)
    override val tag = "SagerNetVpnService"
    override fun createNotification(profileName: String) =
        ServiceNotification(this, profileName, "service-vpn")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
    }

    // Permission can be revoked between the Activity result and service startup. A background
    // service must not launch UI; the next explicit user action requests authorization again.
    override fun startupError(): String? =
        getString(R.string.vpn_permission_denied).takeIf { prepare(this) != null }

    override fun startupRecoveryReason(): ConnectionRecoveryReason? =
        ConnectionRecoveryReason.VPN_PERMISSION_REQUIRED.takeIf { prepare(this) != null }

    inner class NullConnectionException : NullPointerException(),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    /** Official libbox callback: Android owns the VPN permission, TUN FD and app routing. */
    internal fun openTunFromOfficialLibbox(options: TunOptions): Int {
        check(prepare(this) == null) { "Android VPN permission is required" }
        if (reuseTunDuringReload && reloadTun.isInProgress()) {
            // A profile/node reload does not change the Android TUN policy. Duplicate the already
            // registered interface instead of calling Builder.establish(), which would revoke the
            // current system VPN before the candidate has passed its live health probe.
            val descriptors = synchronized(tunLock) {
                val current = requireNotNull(conn) {
                    "Cannot reuse the Android VPN interface before it is established"
                }
                duplicatePairWithRollback {
                    ParcelFileDescriptor.fromFd(current.fd)
                }
            }
            return publishTunDescriptor(descriptors.first, descriptors.second)
        }
        val builder = Builder()
            .setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(options.mtu.coerceIn(MIN_TUN_MTU, MAX_TUN_MTU))

        var hasIpv4 = false
        var hasIpv6 = false
        options.inet4Address.consume { prefix ->
            builder.addAddress(prefix.address(), prefix.prefix())
            hasIpv4 = true
        }
        options.inet6Address.consume { prefix ->
            builder.addAddress(prefix.address(), prefix.prefix())
            hasIpv6 = true
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                options.dnsServerAddress.toList().forEach(builder::addDnsServer)
            }
            var hasIpv4Route = false
            var hasIpv6Route = false
            options.inet4RouteAddress.consume { prefix ->
                builder.addRoute(prefix.address(), prefix.prefix())
                hasIpv4Route = true
            }
            options.inet6RouteAddress.consume { prefix ->
                builder.addRoute(prefix.address(), prefix.prefix())
                hasIpv6Route = true
            }
            if (!hasIpv4Route) options.inet4RouteRange.consume { prefix ->
                builder.addRoute(prefix.address(), prefix.prefix())
                hasIpv4Route = true
            }
            if (!hasIpv6Route) options.inet6RouteRange.consume { prefix ->
                builder.addRoute(prefix.address(), prefix.prefix())
                hasIpv6Route = true
            }
            if (hasIpv4 && !hasIpv4Route) builder.addRoute("0.0.0.0", 0)
            if (hasIpv6 && !hasIpv6Route) builder.addRoute("::", 0)
        }

        val includedPackages = options.includePackage.toList()
        val excludedPackages = options.excludePackage.toList()
        require(includedPackages.isEmpty() || excludedPackages.isEmpty()) {
            "TUN cannot include and exclude packages simultaneously"
        }
        includedPackages.forEach { packageName ->
            try {
                builder.addAllowedApplication(packageName)
            } catch (error: Throwable) {
                throw IllegalStateException("Unable to include $packageName in VPN", error)
            }
        }
        excludedPackages.forEach { packageName ->
            try {
                builder.addDisallowedApplication(packageName)
            } catch (error: Throwable) {
                throw IllegalStateException("Unable to exclude $packageName from VPN", error)
            }
        }

        updateUnderlyingNetwork(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(metered)
        val replacement = builder.establish() ?: throw NullConnectionException()
        return publishTunDescriptor(replacement)
    }

    private fun publishTunDescriptor(
        replacement: ParcelFileDescriptor,
        nativeDescriptor: ParcelFileDescriptor = replacement,
    ): Int {
        var previous: ParcelFileDescriptor? = null
        val accepted = runCatching {
            data.lifecycle.commitIfAlive {
                synchronized(tunLock) {
                    if (reloadTun.isInProgress()) {
                        reloadTun.stage(replacement)
                    } else {
                        previous = conn
                        conn = replacement
                    }
                    if (nativeDescriptor !== replacement) {
                        // The pinned libbox implementation synchronously duplicates this FD
                        // before startOrReload returns. Keep it alive only through that call.
                        nativeCallbackTunDescriptorLease.track(nativeDescriptor)
                    }
                }
            }
        }.getOrElse {
            runCatching { replacement.close() }
            if (nativeDescriptor !== replacement) runCatching { nativeDescriptor.close() }
            throw it
        }
        if (!accepted) {
            runCatching { replacement.close() }
            if (nativeDescriptor !== replacement) runCatching { nativeDescriptor.close() }
            throw CancellationException("VPN service was destroyed before TUN publication")
        }
        runCatching { previous?.close() }.onFailure { error ->
            Logs.w("Previous TUN cleanup failed (${error.javaClass.simpleName})")
        }
        return nativeDescriptor.fd
    }

    private fun io.nekohasekai.libbox.RoutePrefixIterator.consume(action: (io.nekohasekai.libbox.RoutePrefix) -> Unit) {
        while (hasNext()) action(next())
    }

    private fun io.nekohasekai.libbox.StringIterator.toList(): List<String> = buildList {
        while (hasNext()) add(next())
    }

    fun updateUnderlyingNetwork(builder: Builder? = null) {
        val capabilities = SagerNet.underlyingNetwork?.let(SagerNet.connectivity::getNetworkCapabilities)
        metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        val networks = SagerNet.underlyingNetwork?.let { arrayOf(it) }
        if (builder != null) {
            networks?.let(builder::setUnderlyingNetworks)
        } else {
            setUnderlyingNetworks(networks)
            officialPlatform?.updateDefaultInterface(SagerNet.underlyingNetwork)
            autoNodeSelector?.networkChanged(validatedPhysicalNetworkIdentity())
        }
    }

    override fun onRevoke() {
        stopRunner(false, getString(R.string.vpn_permission_denied))
    }

    private fun closeTun() {
        val descriptors = synchronized(tunLock) {
            buildList {
                conn?.let(::add)
                reloadTun.takePending()?.let(::add)
                addAll(retiredTunDescriptors)
                retiredTunDescriptors.clear()
                conn = null
            }
        }
        descriptors.forEach { descriptor ->
            runCatching { descriptor.close() }.onFailure { error ->
                Logs.w("TUN cleanup failed (${error.javaClass.simpleName})")
            }
        }
    }

    private fun beginTunReload() = reloadTun.begin()

    private fun abortTunReload() {
        if (!reloadTun.isInProgress()) return
        val pending = reloadTun.abort()
        runCatching { pending?.close() }.onFailure { error ->
            Logs.w("Failed to close an aborted candidate TUN", error)
        }
    }

    /**
     * Keeps a descriptor that is not shared with the currently running native core. The native
     * close/reload path may close the fd it received from [openTunFromOfficialLibbox]; retaining a
     * duplicate first is what lets the same Android VPN interface survive that native transition.
     */
    private fun retainCurrentTunForReload() {
        synchronized(tunLock) {
            val current = conn ?: throw TunRetentionException(
                IllegalStateException("No active TUN descriptor to retain"),
            )
            val retained = try {
                ParcelFileDescriptor.fromFd(current.fd)
            } catch (error: Throwable) {
                Logs.w("Unable to retain the current TUN before reload", error)
                throw TunRetentionException(error)
            }
            retiredTunDescriptors += current
            conn = retained
        }
    }

    private fun commitTunReload() {
        var previous: ParcelFileDescriptor? = null
        val retired = synchronized(tunLock) {
            val candidate = requireNotNull(reloadTun.commit()) {
                "Candidate VPN runtime did not provide a TUN descriptor"
            }
            previous = conn
            conn = candidate
            retiredTunDescriptors.toList().also { retiredTunDescriptors.clear() }
        }
        (listOfNotNull(previous) + retired).forEach { descriptor ->
            runCatching { descriptor.close() }.onFailure { error ->
                Logs.w("Previous TUN cleanup failed (${error.javaClass.simpleName})")
            }
        }
    }

    /**
     * Promotes the candidate descriptor without closing the currently retained interface. The
     * candidate is only closed/replaced after a healthy LKG restart, so a failed reload never
     * intentionally unregisters the app's active system VPN.
     */
    private fun promoteStagedTunForRecovery() {
        synchronized(tunLock) {
            val candidate = if (reloadTun.isInProgress()) reloadTun.commit() else null
            candidate?.let { replacement ->
                conn?.let(retiredTunDescriptors::add)
                conn = replacement
            }
        }
    }

    override fun onDestroy() {
        // onDestroy is not guaranteed after a killed process, but when it is delivered it remains
        // the final synchronous safety net for partially started native/TUN resources.
        data.stopVpnPolicyObservation()
        data.lifecycle.close()
        if (data.closeReceiverRegistered) {
            runCatching { unregisterReceiver(data.receiver) }
            data.closeReceiverRegistered = false
        }
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
        DefaultNetworkListener.requestStop(this)
        SagerNet.underlyingNetwork = null
        upstreamInterfaceName = null
        runCatching { trafficMonitor?.close() }
        trafficMonitor = null
        runCatching { autoNodeSelector?.close() }
        autoNodeSelector = null
        val startingController = startingCore
        startingCore = null
        startingController?.requestClose()
        val runningController = officialCore
        officialCore = null
        runningController?.requestClose()
        officialPlatform = null
        activeLocalProxyEndpoint = null
        activeRuntimeConfig = null
        closeTun()
        ServiceRuntimeRegistry.unregisterVpn(this)
        ServiceRuntimeRegistry.unregisterBase(this)
        ConnectionStateRepository.remove(data)
        super.onDestroy()
        data.binder.close()
    }
}

/**
 * Duplicates a native-facing descriptor with rollback if the second duplication fails. The first
 * descriptor is otherwise easy to leak on low-FD or teardown races during a live reload.
 */
internal fun <T : AutoCloseable> duplicatePairWithRollback(duplicate: () -> T): Pair<T, T> {
    val first = duplicate()
    return try {
        first to duplicate()
    } catch (error: Throwable) {
        runCatching { first.close() }.onFailure(error::addSuppressed)
        throw error
    }
}

private class TunRetentionException(cause: Throwable) :
    IllegalStateException("Unable to retain the active Android VPN interface", cause)
