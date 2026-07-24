package io.nekohasekai.sagernet.utils

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Physical-upstream identity is independent from the transient validation state. */
internal fun NetworkCapabilities.isPhysicalInternetCandidate(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

@Suppress("DEPRECATION")
internal fun ConnectivityManager.isPhysicalInternetNetwork(network: Network): Boolean =
    getNetworkCapabilities(network)?.isPhysicalInternetCandidate() == true

@Suppress("DEPRECATION")
internal fun ConnectivityManager.findPhysicalInternetNetwork(
    preferred: Network? = null,
): Network? = sequenceOf(preferred, activeNetwork)
    .filterNotNull()
    .plus(allNetworks.asSequence())
    .distinct()
    .firstOrNull(::isPhysicalInternetNetwork)
