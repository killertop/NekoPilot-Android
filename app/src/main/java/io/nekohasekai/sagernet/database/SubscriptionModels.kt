package io.nekohasekai.sagernet.database

/** User-controlled subscription source settings. */
data class SubscriptionSourceConfig(
    val type: Int,
    val link: String,
    val forceResolve: Boolean,
    val deduplication: Boolean,
    val updateWhenConnectedOnly: Boolean,
    val customUserAgent: String,
    val autoUpdate: Boolean,
    val autoUpdateDelayMinutes: Int,
)

/** Server/update-owned metadata; it is not editable source configuration. */
data class SubscriptionRuntimeState(
    val lastUpdatedSeconds: Int,
    val userInfo: String,
)

fun SubscriptionBean.sourceConfig() = SubscriptionSourceConfig(
    type = type,
    link = link,
    forceResolve = forceResolve,
    deduplication = deduplication,
    updateWhenConnectedOnly = updateWhenConnectedOnly,
    customUserAgent = customUserAgent,
    autoUpdate = autoUpdate,
    autoUpdateDelayMinutes = autoUpdateDelay,
)

fun SubscriptionBean.applySourceConfig(config: SubscriptionSourceConfig) {
    type = config.type
    link = config.link
    forceResolve = config.forceResolve
    deduplication = config.deduplication
    updateWhenConnectedOnly = config.updateWhenConnectedOnly
    customUserAgent = config.customUserAgent
    autoUpdate = config.autoUpdate
    autoUpdateDelay = config.autoUpdateDelayMinutes
}

fun SubscriptionBean.runtimeState() = SubscriptionRuntimeState(
    lastUpdatedSeconds = lastUpdated,
    userInfo = subscriptionUserinfo,
)

fun SubscriptionBean.applyRuntimeState(state: SubscriptionRuntimeState) {
    lastUpdated = state.lastUpdatedSeconds
    subscriptionUserinfo = state.userInfo
}
