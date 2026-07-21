package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

/**
 * Root-level node source manager. The home screen owns selection and connection; this screen
 * owns the lifecycle of airport subscriptions and local node sources.
 */
class GroupFragment : ToolbarFragment(R.layout.layout_group), Toolbar.OnMenuItemClickListener {

    private lateinit var activity: MainActivity
    private lateinit var groupListView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var groupAdapter: GroupAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as MainActivity

        toolbar.setTitle(R.string.menu_nodes)
        toolbar.inflateMenu(R.menu.node_sources_menu)
        toolbar.setOnMenuItemClickListener(this)

        emptyState = view.findViewById(R.id.node_sources_empty)
        groupListView = view.findViewById(R.id.group_list)
        groupListView.layoutManager = LinearLayoutManager(requireContext())
        groupAdapter = GroupAdapter()
        GroupManager.addListener(groupAdapter)
        groupListView.adapter = groupAdapter
    }

    override fun onMenuItemClick(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_update_all -> {
            updateAllSubscriptions()
            true
        }

        else -> NodeImportCoordinator.handle(this, item.itemId)
    }

    private fun updateAllSubscriptions() {
        runOnDefaultDispatcher {
            val subscriptions = SagerDatabase.groupDao.allGroups()
                .filter { it.type == GroupType.SUBSCRIPTION }
            if (subscriptions.isEmpty()) {
                onMainDispatcher {
                    if (!isAdded) return@onMainDispatcher
                    activity.snackbar(R.string.no_airport_subscriptions).show()
                }
                return@runOnDefaultDispatcher
            }
            // User-triggered updates are intentionally serialized. GroupUpdater protects one
            // interactive update at a time, so firing all groups concurrently would update only
            // the first one and report the rest as busy.
            subscriptions.forEach { GroupUpdater.executeUpdate(it, true) }
        }
    }

    private fun showSourceActions(source: NodeSource) {
        if (source.group.type != GroupType.SUBSCRIPTION) {
            activity.displayFragmentWithId(R.id.nav_home)
            return
        }
        val actions = arrayOf(
            getString(R.string.update_current_subscription),
            getString(R.string.delete_airport_subscription),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(source.group.displayName())
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        GroupUpdater.startUpdate(source.group, true)
                        groupAdapter.refreshSource(source.group.id)
                    }

                    1 -> confirmDelete(source)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(source: NodeSource) {
        if (source.group.id in GroupUpdater.updating) {
            activity.snackbar(R.string.subscription_update_already_running).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_airport_subscription)
            .setMessage(
                getString(
                    R.string.delete_airport_subscription_message,
                    source.group.displayName(),
                    source.nodeCount,
                ),
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                runOnDefaultDispatcher {
                    runCatching { GroupManager.deleteGroup(source.group.id) }
                        .onSuccess {
                            onMainDispatcher {
                                if (isAdded) {
                                    activity.snackbar(R.string.airport_subscription_deleted).show()
                                }
                            }
                        }
                        .onFailure { error ->
                            Logs.w(error)
                            onMainDispatcher {
                                if (isAdded) activity.snackbar(error.readableMessage).show()
                            }
                        }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        if (::groupAdapter.isInitialized) GroupManager.removeListener(groupAdapter)
        super.onDestroyView()
    }

    private data class NodeSource(
        val group: ProxyGroup,
        val nodeCount: Int,
    )

    private inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>(), GroupManager.Listener {
        val sources = mutableListOf<NodeSource>()

        init {
            setHasStableIds(true)
            reload()
        }

        private fun reload() {
            runOnDefaultDispatcher {
                val snapshot = SagerDatabase.groupDao.allGroups().mapNotNull { group ->
                    val count = SagerDatabase.proxyDao.countByGroup(group.id).toInt()
                    if (group.type != GroupType.SUBSCRIPTION && count == 0) null
                    else NodeSource(group, count)
                }
                onMainDispatcher {
                    if (!isAdded) return@onMainDispatcher
                    sources.clear()
                    sources.addAll(snapshot)
                    notifyDataSetChanged()
                    emptyState.isVisible = sources.isEmpty()
                    groupListView.isVisible = sources.isNotEmpty()
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = GroupHolder(
            LayoutGroupItemBinding.inflate(layoutInflater, parent, false),
        )

        override fun onBindViewHolder(holder: GroupHolder, position: Int) =
            holder.bind(sources[position])

        override fun getItemCount() = sources.size
        override fun getItemId(position: Int) = sources[position].group.id

        fun refreshSource(groupId: Long) {
            sources.indexOfFirst { it.group.id == groupId }
                .takeIf { it >= 0 }
                ?.let(::notifyItemChanged)
        }

        override suspend fun groupAdd(group: ProxyGroup) = reload()
        override suspend fun groupUpdated(group: ProxyGroup) = reload()
        override suspend fun groupRemoved(groupId: Long) = reload()
        override suspend fun groupUpdated(groupId: Long) = reload()
    }

    private inner class GroupHolder(
        private val binding: LayoutGroupItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(source: NodeSource) = with(binding) {
            val subscription = source.group.type == GroupType.SUBSCRIPTION
            groupName.text = if (subscription) {
                source.group.displayName()
            } else {
                getString(R.string.local_nodes)
            }
            sourceIcon.setImageResource(
                if (subscription) R.drawable.baseline_public_24
                else R.drawable.ic_baseline_layers_24,
            )
            groupStatus.text = if (subscription) {
                val lastUpdated = source.group.subscription?.lastUpdated?.toLong() ?: 0L
                if (lastUpdated > 0L) {
                    val updatedAt = lastUpdated * 1000L
                    if (System.currentTimeMillis() - updatedAt < DateUtils.MINUTE_IN_MILLIS) {
                        getString(R.string.subscription_nodes_just_updated, source.nodeCount)
                    } else {
                        getString(
                            R.string.subscription_nodes_updated,
                            source.nodeCount,
                            DateUtils.getRelativeTimeSpanString(
                                updatedAt,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                                DateUtils.FORMAT_ABBREV_RELATIVE,
                            ),
                        )
                    }
                } else {
                    getString(R.string.subscription_nodes_never_updated, source.nodeCount)
                }
            } else {
                getString(R.string.local_nodes_summary, source.nodeCount)
            }

            val updating = source.group.id in GroupUpdater.updating
            subscriptionUpdateProgress.isVisible = updating
            groupUpdate.isVisible = subscription && !updating
            groupUpdate.setOnClickListener {
                GroupUpdater.startUpdate(source.group, true)
                groupAdapter.refreshSource(source.group.id)
            }
            root.setOnClickListener { showSourceActions(source) }
        }
    }
}
