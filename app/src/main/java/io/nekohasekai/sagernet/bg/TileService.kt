package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.core.ConnectionState
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import android.service.quicksettings.TileService as BaseTileService
import android.net.VpnService as AndroidVpnService

@RequiresApi(24)
class TileService : BaseTileService(), SagerConnection.Callback {
    private val iconIdle by lazy { Icon.createWithResource(this, R.drawable.ic_service_idle) }
    private val iconBusy by lazy { Icon.createWithResource(this, R.drawable.ic_service_busy) }
    private val iconConnected by lazy {
        Icon.createWithResource(this, R.drawable.ic_service_active)
    }
    private var tapPending = false

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_TILE)
    override fun stateChanged(state: ConnectionState, profileName: String?, msg: String?) =
        updateTile(state, profileName)

    override fun onServiceConnected(service: ISagerNetService) {
        updateTile(
            ConnectionState.fromWireValue(service.state) ?: ConnectionState.Idle,
            service.profileName,
        )
        if (tapPending) {
            tapPending = false
            onClick()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        connection.connect(this, this)
    }

    override fun onStopListening() {
        connection.disconnect(this)
        super.onStopListening()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(this::toggle) else toggle()
    }

    private fun updateTile(serviceState: ConnectionState, profileName: String?) {
        qsTile?.apply {
            label = null
            when (serviceState) {
                ConnectionState.Preparing,
                ConnectionState.Connecting -> {
                    icon = iconBusy
                    state = Tile.STATE_ACTIVE
                }

                ConnectionState.Connected -> {
                    icon = iconConnected
                    label = profileName
                    state = Tile.STATE_ACTIVE
                }

                ConnectionState.Stopping -> {
                    icon = iconBusy
                    state = Tile.STATE_UNAVAILABLE
                }

                ConnectionState.Idle,
                ConnectionState.Error -> {
                    icon = iconIdle
                    state = Tile.STATE_INACTIVE
                }
            }
            label = label ?: getString(R.string.app_name)
            updateTile()
        }
    }

    private fun toggle() {
        val service = connection.service
        if (service == null) tapPending =
            true else (ConnectionState.fromWireValue(service.state) ?: ConnectionState.Idle).let { state ->
            when {
                state.canStop -> SagerNet.stopService()
                state.canStart -> startFromTile()
            }
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startFromTile() {
        if (AndroidVpnService.prepare(this) == null) {
            SagerNet.startService()
            return
        }
        val intent = Intent(this, VpnRequestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
