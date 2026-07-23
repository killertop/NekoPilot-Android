package io.nekohasekai.sagernet.ui

import android.text.format.DateUtils
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutSubscriptionManagerItemBinding
import io.nekohasekai.sagernet.databinding.LayoutSubscriptionManagerSheetBinding
import io.nekohasekai.sagernet.group.GroupManager
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.snackbar

private data class SubscriptionSource(
    val group: ProxyGroup,
    val row: SubscriptionSourceRow,
)

/** Owns subscription source presentation and releases its listener with the dialog. */
internal class SubscriptionManagerSheet(
    private val host: ConfigurationFragment,
) {
    private var dialog: BottomSheetDialog? = null
    private var binding: LayoutSubscriptionManagerSheetBinding? = null
    private var onDismiss: (() -> Unit)? = null
    private val listener = object : GroupManager.Listener {
        override suspend fun groupAdd(group: ProxyGroup) = reload()
        override suspend fun groupUpdated(group: ProxyGroup) = reload()
        override suspend fun groupRemoved(groupId: Long) = reload()
        override suspend fun groupUpdated(groupId: Long) = reload()
    }

    fun show(onDismiss: (() -> Unit)? = null) {
        if (onDismiss != null) this.onDismiss = onDismiss
        dialog?.let {
            if (!it.isShowing) it.show()
            reload()
            return
        }
        val sheetBinding = LayoutSubscriptionManagerSheetBinding.inflate(host.layoutInflater)
        val sheetDialog = BottomSheetDialog(host.requireContext())
        binding = sheetBinding
        dialog = sheetDialog
        GroupManager.addListener(listener)
        sheetDialog.setContentView(sheetBinding.root)
        sheetDialog.setOnDismissListener {
            GroupManager.removeListener(listener)
            binding = null
            dialog = null
            this.onDismiss?.invoke()
            this.onDismiss = null
        }
        sheetDialog.show()
        reload()
    }

    fun dismiss() = dialog?.dismiss()

    private fun reload() {
        val sheetBinding = binding ?: return
        host.runOnLifecycleDispatcher {
            val sources = SagerDatabase.groupDao.allGroups()
                .asSequence()
                .filter { it.type == GroupType.SUBSCRIPTION }
                .map { group ->
                    SubscriptionSource(
                        group = group,
                        row = SubscriptionSourceRow(
                            groupId = group.id,
                            displayName = group.displayName(),
                            nodeCount = SagerDatabase.proxyDao.countByGroup(group.id).toInt(),
                            lastUpdatedSeconds =
                                group.subscription?.lastUpdated?.toLong() ?: 0L,
                            updating = group.id in GroupUpdater.updating,
                        ),
                    )
                }
                .toList()
            onMainDispatcher {
                if (binding !== sheetBinding || !host.isAdded) return@onMainDispatcher
                render(sources)
            }
        }
    }

    private fun render(sources: List<SubscriptionSource>) {
        val sheetBinding = binding ?: return
        sheetBinding.subscriptionManagerRows.removeAllViews()
        sheetBinding.subscriptionManagerEmpty.isVisible = sources.isEmpty()
        sources.forEach { source ->
            val item = LayoutSubscriptionManagerItemBinding.inflate(
                host.layoutInflater,
                sheetBinding.subscriptionManagerRows,
                false,
            )
            bind(item, source)
            sheetBinding.subscriptionManagerRows.addView(item.root)
        }
    }

    private fun bind(item: LayoutSubscriptionManagerItemBinding, source: SubscriptionSource) {
        val row = source.row
        val now = System.currentTimeMillis()
        item.subscriptionName.text = row.displayName
        item.subscriptionStatus.text = when (val state = row.updateState(now)) {
            SubscriptionUpdateState.Updating -> host.getString(
                R.string.subscription_nodes_updating,
                row.nodeCount,
            )
            SubscriptionUpdateState.NeverUpdated -> host.getString(
                R.string.subscription_nodes_never_updated,
                row.nodeCount,
            )
            SubscriptionUpdateState.JustUpdated -> host.getString(
                R.string.subscription_nodes_just_updated,
                row.nodeCount,
            )
            is SubscriptionUpdateState.UpdatedAt -> host.getString(
                R.string.subscription_nodes_updated,
                row.nodeCount,
                DateUtils.getRelativeTimeSpanString(
                    state.timestampMillis,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE,
                ),
            )
        }
        item.subscriptionProgress.isVisible = row.updating
        item.subscriptionUpdate.isVisible = !row.updating
        item.subscriptionUpdate.setOnClickListener {
            GroupUpdater.startUpdate(source.group, true)
            reload()
        }
        item.subscriptionMore.setOnClickListener { anchor ->
            PopupMenu(host.requireContext(), anchor).apply {
                menuInflater.inflate(R.menu.subscription_source_actions, menu)
                setOnMenuItemClickListener { action ->
                    if (action.itemId == R.id.action_delete_subscription) {
                        confirmDelete(source)
                        true
                    } else false
                }
                show()
            }
        }
    }

    private fun confirmDelete(source: SubscriptionSource) {
        if (source.row.updating) {
            host.snackbar(R.string.subscription_update_already_running).show()
            return
        }
        MaterialAlertDialogBuilder(host.requireContext())
            .setTitle(R.string.delete_airport_subscription)
            .setMessage(
                host.getString(
                    R.string.delete_airport_subscription_message,
                    source.row.displayName,
                    source.row.nodeCount,
                ),
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                runOnDefaultDispatcher {
                    runCatching { GroupManager.deleteGroup(source.group.id) }
                        .onSuccess {
                            onMainDispatcher {
                                if (host.isAdded) {
                                    host.snackbar(R.string.airport_subscription_deleted).show()
                                }
                            }
                        }
                        .onFailure { error ->
                            Logs.w(error)
                            onMainDispatcher {
                                if (host.isAdded) host.snackbar(error.readableMessage).show()
                            }
                        }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
