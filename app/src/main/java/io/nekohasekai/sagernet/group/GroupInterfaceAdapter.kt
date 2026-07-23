package io.nekohasekai.sagernet.group

import android.content.DialogInterface
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ui.ThemedActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlin.coroutines.resume

class GroupInterfaceAdapter(val context: ThemedActivity) : GroupManager.Interface {

    private fun isUiAvailable() = !context.isFinishing && !context.isDestroyed
    private var updateDialog: AlertDialog? = null
    private var updateDialogContent: View? = null
    private var updatingGroupId = 0L

    override suspend fun onUpdateStarted(group: ProxyGroup, byUser: Boolean) {
        if (!byUser) return
        onMainDispatcher {
            if (!isUiAvailable()) return@onMainDispatcher
            updateDialog?.takeIf { it.isShowing }?.dismiss()
            updatingGroupId = group.id
            val content = context.layoutInflater.inflate(R.layout.layout_rule_asset_update, null)
            content.findViewById<TextView>(R.id.rule_asset_update_status).text =
                context.getString(R.string.subscription_update_running, group.displayName())
            content.findViewById<TextView>(R.id.rule_asset_update_detail).isVisible = false
            content.findViewById<LinearProgressIndicator>(R.id.rule_asset_update_progress)
                .isIndeterminate = true
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.subscription_update)
                .setView(content)
                .setNegativeButton(R.string.minimize, null)
                .setCancelable(false)
                .create()
            updateDialog = dialog
            updateDialogContent = content
            dialog.setOnDismissListener {
                if (updateDialog === dialog) {
                    updateDialog = null
                    updateDialogContent = null
                    updatingGroupId = 0L
                }
            }
            dialog.show()
        }
    }

    override suspend fun confirm(message: String): Boolean = onMainDispatcher {
        if (!isUiAvailable()) return@onMainDispatcher false
        suspendCancellableCoroutine { continuation ->
            val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.confirm)
                .setMessage(message)
                .setPositiveButton(R.string.yes) { _, _ ->
                    if (continuation.isActive) continuation.resume(true)
                }
                .setNegativeButton(R.string.no) { _, _ ->
                    if (continuation.isActive) continuation.resume(false)
                }
                .create()
            dialog.setOnDismissListener {
                if (continuation.isActive) continuation.resume(false)
            }
            continuation.invokeOnCancellation {
                context.runOnUiThread { dialog.dismiss() }
            }
            dialog.show()
        }
    }

    override suspend fun onUpdateSuccess(
        group: ProxyGroup,
        changed: Int,
        added: List<String>,
        updated: Map<String, String>,
        deleted: List<String>,
        duplicate: List<String>,
        byUser: Boolean
    ) {
        onMainDispatcher {
            if (!isUiAvailable()) return@onMainDispatcher
            val message = if (changed == 0 && duplicate.isEmpty()) {
                context.getString(R.string.group_no_difference, group.displayName())
            } else {
                context.getString(R.string.group_updated, group.displayName(), changed)
            }
            val dialog = updateDialog?.takeIf {
                byUser && updatingGroupId == group.id && it.isShowing
            }
            val content = updateDialogContent
            if (dialog != null && content != null) {
                content.findViewById<TextView>(R.id.rule_asset_update_status).text = message
                content.findViewById<TextView>(R.id.rule_asset_update_detail).isVisible = false
                content.findViewById<LinearProgressIndicator>(R.id.rule_asset_update_progress)
                    .apply {
                        isIndeterminate = false
                        setProgressCompat(100, true)
                    }
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isVisible = false
                delay(1500L)
                dialog.dismiss()
            } else if (byUser) {
                context.snackbar(message).show()
            }
        }
    }

    override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {
        onMainDispatcher {
            if (!isUiAvailable()) return@onMainDispatcher
            val dialog = updateDialog?.takeIf {
                updatingGroupId == group.id && it.isShowing
            }
            val content = updateDialogContent
            if (dialog != null && content != null) {
                content.findViewById<TextView>(R.id.rule_asset_update_status).text = message
                content.findViewById<LinearProgressIndicator>(R.id.rule_asset_update_progress)
                    .isVisible = false
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).apply {
                    isVisible = true
                    setText(R.string.action_close)
                    setOnClickListener { dialog.dismiss() }
                }
            } else {
                context.snackbar(message).show()
            }
        }
    }

    override suspend fun onUpdateBusy(group: ProxyGroup, message: String) {
        onMainDispatcher {
            if (isUiAvailable()) context.snackbar(message).show()
        }
    }

    override suspend fun alert(message: String) = onMainDispatcher {
        if (!isUiAvailable()) return@onMainDispatcher
        suspendCancellableCoroutine { continuation ->
            val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.ooc_warning)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (continuation.isActive) continuation.resume(Unit)
                }
                .create()
            dialog.setOnDismissListener {
                if (continuation.isActive) continuation.resume(Unit)
            }
            continuation.invokeOnCancellation {
                context.runOnUiThread { dialog.dismiss() }
            }
            dialog.show()
        }
    }
}
