package io.nekohasekai.sagernet.database

import android.os.Binder
import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.DEFAULT_CONNECTION_TEST_CONCURRENCY
import io.nekohasekai.sagernet.DEFAULT_CONNECTION_TEST_URL
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.LEGACY_CONNECTION_TEST_URL
import io.nekohasekai.sagernet.core.ConnectionRecoveryReason
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.preference.InMemoryPreferenceDataStore
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.ktx.boolean
import io.nekohasekai.sagernet.ktx.int
import io.nekohasekai.sagernet.ktx.long
import io.nekohasekai.sagernet.ktx.parsePort
import io.nekohasekai.sagernet.ktx.string
import io.nekohasekai.sagernet.ktx.stringToInt
import io.nekohasekai.sagernet.ktx.stringToIntIfExists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID

private const val INITIAL_PER_APP_POLICY_REVISION = 0L
private fun canonicalizePerAppPackages(serializedPackages: String): String =
    serializedPackages.lineSequence()
        .map { it.trim().removePrefix("\uFEFF") }
        .filter(String::isNotEmpty)
        .distinct()
        .sorted()
        .joinToString("\n")

object DataStore : OnPreferenceDataStoreChangeListener {

    data class LocalProxyEndpoint(
        val port: Int,
        val username: String,
        val password: String,
    )

    /** A consistent persisted per-app VPN policy, independent of this process' preference cache. */
    data class PerAppProxyPolicySnapshot(
        val enabled: Boolean,
        val serializedPackages: String,
        val setupDone: Boolean,
        val desiredRevision: Long,
        val appliedRevision: Long?,
        val appliedEnabled: Boolean,
        val appliedSerializedPackages: String,
        val appliedTunGeneration: Long,
        val status: PerAppPolicyStatus,
        val failureKind: String,
    ) {
        val isApplied: Boolean
            get() = status == PerAppPolicyStatus.APPLIED &&
                appliedRevision == desiredRevision &&
                appliedTunGeneration > 0L &&
                appliedEnabled == enabled &&
                canonicalizePerAppPackages(appliedSerializedPackages) ==
                canonicalizePerAppPackages(serializedPackages)
    }

    enum class PerAppPolicyStatus(val persistedValue: String) {
        PENDING("pending"),
        APPLYING("applying"),
        APPLIED("applied"),
        REJECTED("rejected"),
        FAILED_RECOVERED("failed_recovered"),
        FAILED("failed");

        companion object {
            fun fromStorage(value: String): PerAppPolicyStatus =
                entries.firstOrNull { it.persistedValue == value } ?: PENDING
        }
    }

    sealed interface PerAppPolicyCommitResult {
        data class Committed(val desiredRevision: Long) : PerAppPolicyCommitResult
        data class Conflict(val latest: PerAppProxyPolicySnapshot) : PerAppPolicyCommitResult
    }

    data class PerAppPolicyAttempt(
        val token: String,
        val tunGeneration: Long,
    )

    val configurationStore = RoomPreferenceDataStore(
        PublicDatabase.instance,
        PublicDatabase.kvPairDao,
    )
    val profileCacheStore = InMemoryPreferenceDataStore()

    // last used, but may not be running
    var currentProfile by configurationStore.long(Key.PROFILE_CURRENT)
    var lastConnectionError by configurationStore.string(Key.CONNECTION_ERROR)
    var lastConnectionErrorProfile by configurationStore.long(Key.CONNECTION_ERROR_PROFILE)
    var lastConnectionErrorTime by configurationStore.long(Key.CONNECTION_ERROR_TIME)
    var connectionRecoveryReason by configurationStore.string(Key.CONNECTION_RECOVERY_REASON)
    /** True when the user last requested a VPN start and has not explicitly stopped it. */
    var serviceAutoStart by configurationStore.boolean(Key.SERVICE_AUTOSTART)

    /**
     * Persists a safe recovery hint for failures before a Binder state can reach the foreground
     * activity. Callers must flush [configurationStore] at their lifecycle boundary.
     */
    fun recordConnectionRecovery(
        reason: ConnectionRecoveryReason,
        message: String,
        profileId: Long = selectedProxy,
    ) {
        connectionRecoveryReason = reason.persistedValue
        lastConnectionError = message
        lastConnectionErrorProfile = profileId
        lastConnectionErrorTime = System.currentTimeMillis()
    }

    fun clearConnectionRecoveryReason() {
        connectionRecoveryReason = ""
    }

    val selectedProxy: Long
        get() = configurationStore.getLong(Key.PROFILE_ID) ?: 0L
    var selectedGroup: Long
        get() = configurationStore.getLong(Key.PROFILE_GROUP) ?: 0L
        set(value) = configurationStore.putLong(Key.PROFILE_GROUP, value)

    /**
     * Reads the two-row node selection from one Room transaction. Call this at process and
     * subscription boundaries instead of combining values from a process-local preference cache.
     */
    suspend fun readProxySelection(): ProxySelection {
        val snapshot = configurationStore.getLongPair(Key.PROFILE_ID, Key.PROFILE_GROUP)
        return ProxySelection.fromPersisted(snapshot.first, snapshot.second)
    }

    /** Atomically publishes a user or automatic node choice to every app process. */
    suspend fun selectProxy(profileId: Long, groupId: Long) {
        require(profileId >= 0L && groupId >= 0L) { "Invalid proxy selection" }
        configurationStore.putLongPair(
            Key.PROFILE_ID,
            profileId,
            Key.PROFILE_GROUP,
            groupId,
        )
    }

    /**
     * Replaces a missing/stale selection only when both persisted rows still match [expected].
     * A node picked by the user or auto-selector after the caller's snapshot always wins.
     */
    suspend fun compareAndSetProxySelection(
        expected: ProxySelection,
        replacement: ProxySelection,
    ): Boolean {
        require(replacement.profileId >= 0L && replacement.groupId >= 0L) {
            "Invalid proxy selection"
        }
        return configurationStore.compareAndSetLongPair(
            Key.PROFILE_ID,
            expected.persistedProfileId,
            Key.PROFILE_GROUP,
            expected.persistedGroupId,
            replacement.profileId,
            replacement.groupId,
        )
    }

    // main

    var runningTest = false

    fun currentGroupId(): Long {
        val currentSelected = configurationStore.getLong(Key.PROFILE_GROUP, -1)
        val groups = SagerDatabase.groupDao.allGroups()
        if (groups.isNotEmpty()) {
            val selectedProfileGroup = selectedProxy.takeIf { it > 0L }
                ?.let(SagerDatabase.proxyDao::getById)
                ?.groupId
            val groupId = groups.resolveGroupId(currentSelected, selectedProfileGroup)
            selectedGroup = groupId
            return groupId
        }
        val groupId = SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
        selectedGroup = groupId
        return groupId
    }

    fun currentGroup(): ProxyGroup {
        var group: ProxyGroup? = null
        val currentSelected = configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) {
            group = SagerDatabase.groupDao.getById(currentSelected)
        }
        if (group != null) return group
        val groups = SagerDatabase.groupDao.allGroups()
        if (groups.isEmpty()) {
            group = ProxyGroup(ungrouped = true).apply {
                id = SagerDatabase.groupDao.createGroup(this)
            }
        } else {
            group = groups[0]
        }
        selectedGroup = group.id
        return group
    }

    fun selectedGroupForImport(): Long {
        val current = currentGroup()
        val groups = SagerDatabase.groupDao.allGroups()
        groups.basicGroupForImport(current)?.let { return it.id }
        return SagerDatabase.groupDao.createGroup(
            ProxyGroup(
                userOrder = SagerDatabase.groupDao.nextOrder() ?: 1L,
                ungrouped = true,
            )
        )
    }

    //

    var isExpert by configurationStore.boolean(Key.APP_EXPERT)

    var allowAccess by configurationStore.boolean(Key.ALLOW_ACCESS)
    var autoSwitch by configurationStore.boolean(Key.AUTO_SWITCH)
    var autoSwitchStatus by configurationStore.string(Key.AUTO_SWITCH_STATUS)

    var ruleDefaultsVersion by configurationStore.int(Key.RULE_DEFAULTS_VERSION)
    // hopefully hashCode = mHandle doesn't change, currently this is true from KitKat to Nougat
    private val userIndex by lazy { Binder.getCallingUserHandle().hashCode() }
    var mixedPort: Int
        get() = getLocalPort(Key.MIXED_PORT, 20_880)
        set(value) = saveLocalPort(Key.MIXED_PORT, value)

    val mixedProxyUsername: String
        get() = getOrCreateSecret(Key.MIXED_PROXY_USERNAME, "nekopilot")

    val mixedProxyPassword: String
        get() = getOrCreateSecret(Key.MIXED_PROXY_PASSWORD)

    /**
     * Prepares the local HTTP proxy endpoint without blocking the caller thread.
     *
     * Secret creation remains a database-level put-if-absent operation, so the main and :bg
     * processes can never publish different first-run credentials. The final flush happens
     * before callers are allowed to start the VPN process.
     */
    suspend fun prepareLocalProxyEndpoint(refresh: Boolean = false): LocalProxyEndpoint {
        configurationStore.awaitReady()
        if (refresh) configurationStore.refresh()
        if (configurationStore.getString(Key.CONNECTION_TEST_URL) == LEGACY_CONNECTION_TEST_URL) {
            connectionTestURL = DEFAULT_CONNECTION_TEST_URL
        }
        if (configurationStore.getString(Key.MIXED_PORT) == null) {
            mixedPort = mixedPort
        }
        val endpoint = LocalProxyEndpoint(
            port = mixedPort,
            username = getOrCreateSecretAsync(Key.MIXED_PROXY_USERNAME, "nekopilot"),
            password = getOrCreateSecretAsync(Key.MIXED_PROXY_PASSWORD),
        )
        configurationStore.flush()
        return endpoint
    }

    fun localProxyEndpoint(refresh: Boolean = false): LocalProxyEndpoint =
        runBlocking(Dispatchers.IO) {
            prepareLocalProxyEndpoint(refresh)
        }

    private suspend fun getOrCreateSecretAsync(key: String, fixedValue: String? = null): String {
        configurationStore.getString(key)?.takeIf { it.isNotBlank() }?.let { return it }
        return withContext(Dispatchers.IO) {
            configurationStore.getOrPutString(key) {
                fixedValue ?: UUID.randomUUID().toString().replace("-", "")
            }
        }
    }


    private fun getLocalPort(key: String, default: Int): Int {
        return parsePort(configurationStore.getString(key), default + userIndex)
    }

    private fun saveLocalPort(key: String, value: Int) {
        configurationStore.putString(key, "$value")
    }

    private fun getOrCreateSecret(key: String, fixedValue: String? = null): String {
        configurationStore.getString(key)?.takeIf { it.isNotBlank() }?.let { return it }
        return configurationStore.getOrPutStringBlocking(key) {
            fixedValue ?: UUID.randomUUID().toString().replace("-", "")
        }
    }

    // The per-app VPN feature is opt-in.  When enabled, the app always uses the
    // selected-app allow list; the legacy bypass key remains readable for upgrades.
    var proxyApps by configurationStore.boolean(Key.PROXY_APPS)
        private set
    var individual by configurationStore.string(Key.INDIVIDUAL)
        private set
    var appProxySetupDone by configurationStore.boolean(Key.APP_PROXY_SETUP_DONE)
    var appProxyShowSystemApps by configurationStore.boolean(Key.APP_PROXY_SHOW_SYSTEM_APPS) { true }

    /**
     * Reads the mode, allow-list and first-run marker from a single committed database snapshot.
     * Do not assemble this policy from the process-local delegated properties.
     */
    suspend fun readPerAppProxyPolicy(): PerAppProxyPolicySnapshot {
        val values = configurationStore.readValuesAtomically(
            listOf(
                Key.PROXY_APPS,
                Key.INDIVIDUAL,
                Key.APP_PROXY_SETUP_DONE,
                Key.APP_PROXY_DESIRED_REVISION,
                Key.APP_PROXY_APPLIED_REVISION,
                Key.APP_PROXY_APPLIED_ENABLED,
                Key.APP_PROXY_APPLIED_PACKAGES,
                Key.APP_PROXY_APPLIED_TUN_GENERATION,
                Key.APP_PROXY_APPLY_STATUS,
                Key.APP_PROXY_APPLY_FAILURE,
            ),
        )
        return PerAppProxyPolicySnapshot(
            enabled = values[Key.PROXY_APPS]?.boolean ?: false,
            serializedPackages = values[Key.INDIVIDUAL]?.string.orEmpty(),
            setupDone = values[Key.APP_PROXY_SETUP_DONE]?.boolean ?: false,
            desiredRevision = values[Key.APP_PROXY_DESIRED_REVISION]?.long
                ?: INITIAL_PER_APP_POLICY_REVISION,
            appliedRevision = values[Key.APP_PROXY_APPLIED_REVISION]?.long,
            appliedEnabled = values[Key.APP_PROXY_APPLIED_ENABLED]?.boolean ?: false,
            appliedSerializedPackages = values[Key.APP_PROXY_APPLIED_PACKAGES]?.string.orEmpty(),
            appliedTunGeneration = values[Key.APP_PROXY_APPLIED_TUN_GENERATION]?.long ?: 0L,
            status = PerAppPolicyStatus.fromStorage(
                values[Key.APP_PROXY_APPLY_STATUS]?.string.orEmpty(),
            ),
            failureKind = values[Key.APP_PROXY_APPLY_FAILURE]?.string.orEmpty(),
        )
    }

    /**
     * Saves one complete per-app VPN policy before the caller asks the service to rebuild TUN.
     * The mode and allow-list are one logical unit; publishing them independently can leave the
     * VPN process with a mismatched policy after a storage error.
     */
    suspend fun savePerAppProxyPolicy(
        expectedRevision: Long,
        enabled: Boolean,
        serializedPackages: String,
        markSetupDone: Boolean,
    ): PerAppPolicyCommitResult {
        require(expectedRevision >= INITIAL_PER_APP_POLICY_REVISION) {
            "Invalid per-app policy revision"
        }
        check(expectedRevision < Long.MAX_VALUE) { "Per-app policy revision exhausted" }
        val canonicalPackages = canonicalizePerAppPackages(serializedPackages)
        val newRevision = expectedRevision + 1L
        val values = buildList {
            add(KeyValuePair(Key.PROXY_APPS).put(enabled))
            add(KeyValuePair(Key.INDIVIDUAL).put(canonicalPackages))
            add(KeyValuePair(Key.APP_PROXY_APPLY_STATUS).put(PerAppPolicyStatus.PENDING.persistedValue))
            add(KeyValuePair(Key.APP_PROXY_APPLY_FAILURE).put(""))
            if (markSetupDone) add(KeyValuePair(Key.APP_PROXY_SETUP_DONE).put(true))
        }
        val committed = configurationStore.compareAndSetLongWithValues(
            revisionKey = Key.APP_PROXY_DESIRED_REVISION,
            expectedRevision = expectedRevision,
            missingRevision = INITIAL_PER_APP_POLICY_REVISION,
            newRevision = newRevision,
            values = values,
        )
        return if (committed) {
            PerAppPolicyCommitResult.Committed(newRevision)
        } else {
            PerAppPolicyCommitResult.Conflict(readPerAppProxyPolicy())
        }
    }

    /**
     * Claims one immutable runtime attempt and reserves its TUN generation before Android creates
     * a descriptor. A failed attempt leaves a harmless generation gap; a later receipt never
     * invents or increments the generation again.
     */
    suspend fun claimPerAppProxyPolicyAttempt(
        expectedDesiredRevision: Long,
    ): PerAppPolicyAttempt? {
        val token = UUID.randomUUID().toString()
        val generation = configurationStore.compareLongAndIncrementCounterWithValues(
            conditionKey = Key.APP_PROXY_DESIRED_REVISION,
            expectedCondition = expectedDesiredRevision,
            missingCondition = INITIAL_PER_APP_POLICY_REVISION,
            counterKey = Key.APP_PROXY_TUN_GENERATION_COUNTER,
            missingCounter = 0L,
            counterMirrorKey = Key.APP_PROXY_ATTEMPT_TUN_GENERATION,
            values = listOf(
                KeyValuePair(Key.APP_PROXY_ATTEMPT_REVISION).put(expectedDesiredRevision),
                KeyValuePair(Key.APP_PROXY_ATTEMPT_TOKEN).put(token),
                KeyValuePair(Key.APP_PROXY_APPLY_STATUS)
                    .put(PerAppPolicyStatus.APPLYING.persistedValue),
                KeyValuePair(Key.APP_PROXY_APPLY_FAILURE).put(""),
            ),
        ) ?: return null
        return PerAppPolicyAttempt(token, generation)
    }

    /**
     * Records the exact policy adopted by a connected TUN. A stale revision can neither confirm
     * nor overwrite a newer desired policy. The returned generation identifies this TUN adoption.
     */
    suspend fun markPerAppProxyPolicyApplied(
        expectedDesiredRevision: Long,
        attempt: PerAppPolicyAttempt,
        enabled: Boolean,
        serializedPackages: String,
    ): Boolean = configurationStore.compareLongAndStringWithValues(
        longConditionKey = Key.APP_PROXY_DESIRED_REVISION,
        expectedLong = expectedDesiredRevision,
        missingLong = INITIAL_PER_APP_POLICY_REVISION,
        stringConditionKey = Key.APP_PROXY_ATTEMPT_TOKEN,
        expectedString = attempt.token,
        values = listOf(
            KeyValuePair(Key.APP_PROXY_APPLIED_REVISION).put(expectedDesiredRevision),
            KeyValuePair(Key.APP_PROXY_APPLIED_ENABLED).put(enabled),
            KeyValuePair(Key.APP_PROXY_APPLIED_PACKAGES)
                .put(canonicalizePerAppPackages(serializedPackages)),
            KeyValuePair(Key.APP_PROXY_APPLIED_TUN_GENERATION).put(attempt.tunGeneration),
            KeyValuePair(Key.APP_PROXY_APPLY_STATUS).put(PerAppPolicyStatus.APPLIED.persistedValue),
            KeyValuePair(Key.APP_PROXY_APPLY_FAILURE).put(""),
        ),
    )

    /**
     * Confirms a newer revision whose canonical policy is already carried by the current TUN.
     * This changes the durable policy identity without inventing a new TUN generation.
     */
    suspend fun adoptPerAppProxyPolicyOnExistingTun(
        expectedDesiredRevision: Long,
        activeAttempt: PerAppPolicyAttempt,
        enabled: Boolean,
        serializedPackages: String,
    ): Boolean = configurationStore.compareLongAndStringWithValues(
        longConditionKey = Key.APP_PROXY_DESIRED_REVISION,
        expectedLong = expectedDesiredRevision,
        missingLong = INITIAL_PER_APP_POLICY_REVISION,
        stringConditionKey = Key.APP_PROXY_ATTEMPT_TOKEN,
        expectedString = activeAttempt.token,
        values = listOf(
            KeyValuePair(Key.APP_PROXY_APPLIED_REVISION).put(expectedDesiredRevision),
            KeyValuePair(Key.APP_PROXY_APPLIED_ENABLED).put(enabled),
            KeyValuePair(Key.APP_PROXY_APPLIED_PACKAGES)
                .put(canonicalizePerAppPackages(serializedPackages)),
            KeyValuePair(Key.APP_PROXY_APPLIED_TUN_GENERATION)
                .put(activeAttempt.tunGeneration),
            KeyValuePair(Key.APP_PROXY_APPLY_STATUS).put(PerAppPolicyStatus.APPLIED.persistedValue),
            KeyValuePair(Key.APP_PROXY_APPLY_FAILURE).put(""),
        ),
    )

    /**
     * Rejects only an unclaimed desired revision. A delayed package validation must never replace
     * the state of an attempt that has already reached APPLYING or APPLIED for the same revision.
     */
    suspend fun markPerAppProxyPolicyRejected(
        expectedDesiredRevision: Long,
        failureKind: String,
    ): Boolean = configurationStore.compareLongAndStringWithValues(
        longConditionKey = Key.APP_PROXY_DESIRED_REVISION,
        expectedLong = expectedDesiredRevision,
        missingLong = INITIAL_PER_APP_POLICY_REVISION,
        stringConditionKey = Key.APP_PROXY_APPLY_STATUS,
        expectedString = PerAppPolicyStatus.PENDING.persistedValue,
        missingString = PerAppPolicyStatus.PENDING.persistedValue,
        values = listOf(
            KeyValuePair(Key.APP_PROXY_APPLY_STATUS).put(PerAppPolicyStatus.REJECTED.persistedValue),
            KeyValuePair(Key.APP_PROXY_APPLY_FAILURE).put(failureKind.take(80)),
        ),
    )

    /** Marks a failed desired revision while retaining the last durable applied-policy identity. */
    suspend fun markPerAppProxyPolicyFailedRecovered(
        expectedDesiredRevision: Long,
        attempt: PerAppPolicyAttempt,
        appliedRevision: Long,
        appliedEnabled: Boolean,
        appliedSerializedPackages: String,
        failureKind: String,
    ): Boolean = configurationStore.compareLongAndStringWithValues(
        longConditionKey = Key.APP_PROXY_DESIRED_REVISION,
        expectedLong = expectedDesiredRevision,
        missingLong = INITIAL_PER_APP_POLICY_REVISION,
        stringConditionKey = Key.APP_PROXY_ATTEMPT_TOKEN,
        expectedString = attempt.token,
        values = listOf(
            KeyValuePair(Key.APP_PROXY_APPLIED_REVISION).put(appliedRevision),
            KeyValuePair(Key.APP_PROXY_APPLIED_ENABLED).put(appliedEnabled),
            KeyValuePair(Key.APP_PROXY_APPLIED_PACKAGES)
                .put(canonicalizePerAppPackages(appliedSerializedPackages)),
            KeyValuePair(Key.APP_PROXY_APPLIED_TUN_GENERATION).put(attempt.tunGeneration),
            KeyValuePair(Key.APP_PROXY_APPLY_STATUS)
                .put(PerAppPolicyStatus.FAILED_RECOVERED.persistedValue),
            KeyValuePair(Key.APP_PROXY_APPLY_FAILURE).put(failureKind.take(80)),
        ),
    )

    /** Records that no trustworthy VPN runtime could be established for this desired revision. */
    suspend fun markPerAppProxyPolicyFailed(
        expectedDesiredRevision: Long,
        attempt: PerAppPolicyAttempt,
        failureKind: String,
    ): Boolean = configurationStore.compareLongAndStringWithValues(
        longConditionKey = Key.APP_PROXY_DESIRED_REVISION,
        expectedLong = expectedDesiredRevision,
        missingLong = INITIAL_PER_APP_POLICY_REVISION,
        stringConditionKey = Key.APP_PROXY_ATTEMPT_TOKEN,
        expectedString = attempt.token,
        values = listOf(
            KeyValuePair(Key.APP_PROXY_APPLY_STATUS).put(PerAppPolicyStatus.FAILED.persistedValue),
            KeyValuePair(Key.APP_PROXY_APPLY_FAILURE).put(failureKind.take(80)),
        ),
    )

    /** Records an explicit first-run choice without changing the active VPN policy. */
    suspend fun markPerAppProxySetupDone() {
        configurationStore.putValuesAtomically(
            listOf(KeyValuePair(Key.APP_PROXY_SETUP_DONE).put(true)),
        )
    }

    var showNodeIp by configurationStore.boolean(Key.SHOW_NODE_IP)
    var showServerLocation by configurationStore.boolean(Key.SHOW_SERVER_LOCATION)

    var connectionTestURL by configurationStore.string(Key.CONNECTION_TEST_URL) {
        DEFAULT_CONNECTION_TEST_URL
    }
    var connectionTestConcurrent by configurationStore.int("connectionTestConcurrent") {
        DEFAULT_CONNECTION_TEST_CONCURRENCY
    }
    var connectionTestDownload by configurationStore.boolean("connectionTestDownload") { false }
    // protocol

    // old cache, DO NOT ADD

    var dirty by profileCacheStore.boolean(Key.PROFILE_DIRTY)
    var editingId by profileCacheStore.long(Key.PROFILE_ID)
    var editingGroup by profileCacheStore.long(Key.PROFILE_GROUP)
    var profileName by profileCacheStore.string(Key.PROFILE_NAME)
    var serverAddress by profileCacheStore.string(Key.SERVER_ADDRESS)
    var serverPort by profileCacheStore.stringToInt(Key.SERVER_PORT)
    var serverPorts by profileCacheStore.string("serverPorts")
    var serverUsername by profileCacheStore.string(Key.SERVER_USERNAME)
    var serverPassword by profileCacheStore.string(Key.SERVER_PASSWORD)
    var serverPassword1 by profileCacheStore.string(Key.SERVER_PASSWORD1)
    var serverMethod by profileCacheStore.string(Key.SERVER_METHOD)

    var sharedStorage by profileCacheStore.string("sharedStorage")

    var serverProtocol by profileCacheStore.string(Key.SERVER_PROTOCOL)
    var serverObfs by profileCacheStore.string(Key.SERVER_OBFS)

    var serverNetwork by profileCacheStore.string(Key.SERVER_NETWORK)
    var serverHost by profileCacheStore.string(Key.SERVER_HOST)
    var serverPath by profileCacheStore.string(Key.SERVER_PATH)
    var serverSNI by profileCacheStore.string(Key.SERVER_SNI)
    var serverEncryption by profileCacheStore.string(Key.SERVER_ENCRYPTION)
    var serverALPN by profileCacheStore.string(Key.SERVER_ALPN)
    var serverCertificates by profileCacheStore.string(Key.SERVER_CERTIFICATES)
    var serverMTU by profileCacheStore.stringToInt(Key.SERVER_MTU)
    var serverHeaders by profileCacheStore.string(Key.SERVER_HEADERS)
    var serverAllowInsecure by profileCacheStore.boolean(Key.SERVER_ALLOW_INSECURE)

    var serverAuthType by profileCacheStore.stringToInt(Key.SERVER_AUTH_TYPE)
    var serverUploadSpeed by profileCacheStore.stringToInt(Key.SERVER_UPLOAD_SPEED)
    var serverDownloadSpeed by profileCacheStore.stringToInt(Key.SERVER_DOWNLOAD_SPEED)
    var serverStreamReceiveWindow by profileCacheStore.stringToIntIfExists(Key.SERVER_STREAM_RECEIVE_WINDOW)
    var serverConnectionReceiveWindow by profileCacheStore.stringToIntIfExists(Key.SERVER_CONNECTION_RECEIVE_WINDOW)
    var serverDisableMtuDiscovery by profileCacheStore.boolean(Key.SERVER_DISABLE_MTU_DISCOVERY)
    var serverHopInterval by profileCacheStore.stringToInt(Key.SERVER_HOP_INTERVAL) { 10 }

    var protocolVersion by profileCacheStore.stringToInt(Key.PROTOCOL_VERSION) { 2 } // default is SOCKS5

    var serverProtocolInt by profileCacheStore.stringToInt(Key.SERVER_PROTOCOL)
    var serverPrivateKey by profileCacheStore.string(Key.SERVER_PRIVATE_KEY)
    var serverInsecureConcurrency by profileCacheStore.stringToInt(Key.SERVER_INSECURE_CONCURRENCY)

    var serverUDPRelayMode by profileCacheStore.string(Key.SERVER_UDP_RELAY_MODE)
    var serverCongestionController by profileCacheStore.string(Key.SERVER_CONGESTION_CONTROLLER)
    var serverDisableSNI by profileCacheStore.boolean(Key.SERVER_DISABLE_SNI)
    var serverReduceRTT by profileCacheStore.boolean(Key.SERVER_REDUCE_RTT)

    var routeName by profileCacheStore.string(Key.ROUTE_NAME)
    var routeDomain by profileCacheStore.string(Key.ROUTE_DOMAIN)
    var routeIP by profileCacheStore.string(Key.ROUTE_IP)
    var routePort by profileCacheStore.string(Key.ROUTE_PORT)
    var routeSourcePort by profileCacheStore.string(Key.ROUTE_SOURCE_PORT)
    var routeNetwork by profileCacheStore.string(Key.ROUTE_NETWORK)
    var routeSource by profileCacheStore.string(Key.ROUTE_SOURCE)
    var routeProtocol by profileCacheStore.string(Key.ROUTE_PROTOCOL)
    var routeOutbound by profileCacheStore.stringToInt(Key.ROUTE_OUTBOUND)
    var routeOutboundRule by profileCacheStore.long(Key.ROUTE_OUTBOUND + "Long")
    var routePackages by profileCacheStore.string(Key.ROUTE_PACKAGES)

    var frontProxy by profileCacheStore.long(Key.GROUP_FRONT_PROXY + "Long")
    var landingProxy by profileCacheStore.long(Key.GROUP_LANDING_PROXY + "Long")
    var frontProxyTmp by profileCacheStore.stringToInt(Key.GROUP_FRONT_PROXY)
    var landingProxyTmp by profileCacheStore.stringToInt(Key.GROUP_LANDING_PROXY)

    var serverConfig by profileCacheStore.string(Key.SERVER_CONFIG)
    var serverCustom by profileCacheStore.string(Key.SERVER_CUSTOM)
    var serverCustomOutbound by profileCacheStore.string(Key.SERVER_CUSTOM_OUTBOUND)

    var groupName by profileCacheStore.string(Key.GROUP_NAME)
    var groupType by profileCacheStore.stringToInt(Key.GROUP_TYPE)

    var subscriptionLink by profileCacheStore.string(Key.SUBSCRIPTION_LINK)
    var subscriptionForceResolve by profileCacheStore.boolean(Key.SUBSCRIPTION_FORCE_RESOLVE)
    var subscriptionUpdateWhenConnectedOnly by profileCacheStore.boolean(Key.SUBSCRIPTION_UPDATE_WHEN_CONNECTED_ONLY)
    var subscriptionUserAgent by profileCacheStore.string(Key.SUBSCRIPTION_USER_AGENT)
    var subscriptionAutoUpdate by profileCacheStore.boolean(Key.SUBSCRIPTION_AUTO_UPDATE)
    var subscriptionAutoUpdateDelay by profileCacheStore.stringToInt(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY) { 360 }

    var rulesFirstCreate by profileCacheStore.boolean("rulesFirstCreate")

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
    }
}
