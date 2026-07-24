package io.nekohasekai.sagernet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applicationScope
import kotlinx.coroutines.launch

/** Restores a connection that the user explicitly left running before a device reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            return
        }

        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                DataStore.configurationStore.awaitReady()
                if (!DataStore.serviceAutoStart) return@launch
                if (VpnService.prepare(context) != null) {
                    // A revoked VPN grant cannot be repaired from a background receiver. Clear
                    // the gate and wait for the next explicit foreground connect flow.
                    DataStore.serviceAutoStart = false
                    DataStore.configurationStore.flush()
                    return@launch
                }
                SagerNet.startServicePrepared()
            } catch (error: Throwable) {
                Logs.w("Unable to restore VPN after boot", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
