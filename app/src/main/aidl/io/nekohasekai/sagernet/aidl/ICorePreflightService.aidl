package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ICorePreflightCallback;

/**
 * Runs an unprivileged, no-TUN libbox candidate in a dedicated process.
 * It must never own or reconfigure the app's Android VPN.
 */
interface ICorePreflightService {
  void probe(
    String config,
    int port,
    String username,
    String password,
    in String[] probeUrls,
    int timeoutMillis,
    ICorePreflightCallback callback
  );
}
