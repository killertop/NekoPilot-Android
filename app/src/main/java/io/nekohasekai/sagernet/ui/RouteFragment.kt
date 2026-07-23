package io.nekohasekai.sagernet.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.RuleAssetsUpdater
import io.nekohasekai.sagernet.core.ConnectionStateRepository
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.isDefaultChinaDirectRule
import io.nekohasekai.sagernet.database.isDefaultChinaDomainDirectRule
import io.nekohasekai.sagernet.database.isDefaultChinaIpDirectRule
import io.nekohasekai.sagernet.databinding.LayoutRouteItemBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class RuleAssetDialogActions(
    val showRetry: Boolean,
    val showSecondaryAction: Boolean,
    val secondaryActionClosesUpdate: Boolean,
)

internal fun ruleAssetDialogActions(
    hasResult: Boolean,
    hasFailure: Boolean,
) = RuleAssetDialogActions(
    showRetry = hasFailure,
    showSecondaryAction = !hasResult,
    secondaryActionClosesUpdate = hasFailure,
)

class RouteFragment : ToolbarFragment(R.layout.layout_route) {

    lateinit var activity: MainActivity
    lateinit var ruleListView: RecyclerView
    lateinit var ruleAdapter: RuleAdapter
    lateinit var undoManager: UndoSnackbarManager<RuleEntity>
    private lateinit var appProxyEntry: View
    private lateinit var appProxyStatus: TextView
    private var updatingAsset: RuleAssetsUpdater.Asset? = null
    private var updatingRuleName = ""
    private var ruleAssetProgress: RuleAssetsUpdater.UpdateProgress? = null
    private var ruleAssetResult: RuleAssetsUpdater.UpdateResult? = null
    private var ruleAssetResultAppliesOnReconnect = false
    private var ruleAssetFailure: String? = null
    private var ruleAssetDialog: AlertDialog? = null
    private var ruleAssetDialogContent: View? = null
    private var ruleAssetUpdateJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity = requireActivity() as MainActivity

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_rules)

        appProxyEntry = view.findViewById(R.id.app_proxy_entry)
        appProxyStatus = view.findViewById(R.id.app_proxy_status)
        appProxyEntry.setOnClickListener {
            startActivity(Intent(requireContext(), AppManagerActivity::class.java))
        }
        updateAppProxyEntry()

        ruleListView = view.findViewById(R.id.route_list)
        ruleListView.layoutManager = FixedLinearLayoutManager(ruleListView)
        ruleAdapter = RuleAdapter()
        ProfileManager.addListener(ruleAdapter)
        ruleListView.adapter = ruleAdapter
        undoManager = UndoSnackbarManager(activity, ruleAdapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START) {

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int {
                val position = viewHolder.bindingAdapterPosition
                val rule = ruleAdapter.ruleList.getOrNull(position)
                return if (rule?.isDefaultChinaDirectRule() == true) {
                    0
                } else {
                    super.getSwipeDirs(recyclerView, viewHolder)
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                val removed = ruleAdapter.remove(index) ?: return
                undoManager.remove(index to removed)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean = ruleAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                ruleAdapter.commitMove()
            }
        }).attachToRecyclerView(ruleListView)
    }

    override fun onResume() {
        super.onResume()
        if (::appProxyStatus.isInitialized) updateAppProxyEntry()
    }

    private fun updateAppProxyEntry() {
        val selectedCount = DataStore.individual.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .count()
        appProxyStatus.text = if (DataStore.proxyApps) {
            getString(R.string.app_proxy_entry_selected, selectedCount)
        } else {
            getString(R.string.app_proxy_entry_disabled)
        }
    }

    private fun updateRuleAsset(asset: RuleAssetsUpdater.Asset, ruleName: String) {
        if (updatingAsset != null) {
            showRuleAssetDialog()
            return
        }
        updatingAsset = asset
        updatingRuleName = ruleName
        ruleAssetResult = null
        ruleAssetResultAppliesOnReconnect = false
        ruleAssetFailure = null
        ruleAssetProgress = RuleAssetsUpdater.UpdateProgress(
            asset,
            RuleAssetsUpdater.UpdatePhase.CHECKING,
        )
        ruleAdapter.notifyDataSetChanged()
        showRuleAssetDialog()
        startRuleAssetUpdate()
    }

    private fun startRuleAssetUpdate() {
        val asset = updatingAsset ?: return
        val ruleName = updatingRuleName
        if (ruleAssetUpdateJob?.isActive == true) return
        ruleAssetResult = null
        ruleAssetResultAppliesOnReconnect = false
        ruleAssetFailure = null
        ruleAssetProgress = RuleAssetsUpdater.UpdateProgress(
            asset,
            RuleAssetsUpdater.UpdatePhase.CHECKING,
        )
        if (::ruleAdapter.isInitialized) ruleAdapter.notifyDataSetChanged()
        renderRuleAssetDialog()
        ruleAssetUpdateJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    RuleAssetsUpdater.updateNow(
                        requireContext().applicationContext,
                        asset,
                    ) { progress ->
                        ruleListView.post {
                            ruleAssetProgress = progress
                            renderRuleAssetDialog()
                        }
                    }
                }
                // Replacing the SRS file is atomic. The running libbox instance safely keeps its
                // already-loaded snapshot; rebuilding TUN here would terminate every live flow.
                // Apply fresh rules on the next manual switch/reconnect instead.
                ruleAssetResultAppliesOnReconnect =
                    result == RuleAssetsUpdater.UpdateResult.UPDATED && ConnectionStateRepository.stateOrIdle.started
                val resultWasVisible = ruleAssetDialog?.isShowing == true
                ruleAssetResult = result
                ruleAssetProgress = null
                ruleAdapter.notifyDataSetChanged()
                renderRuleAssetDialog()
                delay(RULE_ASSET_RESULT_DISPLAY_MILLIS)
                ruleAssetDialog?.dismiss()
                if (!resultWasVisible) snackbar(ruleAssetResultMessage(result, ruleName)).show()
                clearRuleAssetUpdate()
            } catch (error: CancellationException) {
                clearRuleAssetUpdate()
                throw error
            } catch (error: Throwable) {
                val dialogWasVisible = ruleAssetDialog?.isShowing == true
                val message = getString(
                    R.string.route_asset_rule_failed,
                    ruleName,
                    error.readableMessage,
                )
                ruleAssetFailure = message
                ruleAssetProgress = null
                ruleAdapter.notifyDataSetChanged()
                if (dialogWasVisible) {
                    renderRuleAssetDialog()
                } else {
                    snackbar(message).setAction(R.string.retry) {
                        showRuleAssetDialog()
                        startRuleAssetUpdate()
                    }.show()
                }
            } finally {
                ruleAssetUpdateJob = null
            }
        }
    }

    private fun clearRuleAssetUpdate() {
        updatingAsset = null
        updatingRuleName = ""
        ruleAssetProgress = null
        ruleAssetResult = null
        ruleAssetResultAppliesOnReconnect = false
        ruleAssetFailure = null
        if (::ruleAdapter.isInitialized) ruleAdapter.notifyDataSetChanged()
    }

    private fun ruleAssetResultMessage(
        result: RuleAssetsUpdater.UpdateResult,
        ruleName: String,
    ) = getString(
        when (result) {
            RuleAssetsUpdater.UpdateResult.UPDATED -> if (ruleAssetResultAppliesOnReconnect) {
                R.string.route_asset_rule_updated_on_reconnect
            } else {
                R.string.route_asset_rule_updated
            }
            RuleAssetsUpdater.UpdateResult.UP_TO_DATE -> R.string.route_asset_rule_current
        },
        ruleName,
    )

    private fun showRuleAssetDialog() {
        if (ruleAssetDialog?.isShowing == true || updatingAsset == null) return
        val content = layoutInflater.inflate(R.layout.layout_rule_asset_update, null)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.route_asset_dialog_title, updatingRuleName))
            .setView(content)
            .setPositiveButton(R.string.retry, null)
            .setNegativeButton(R.string.minimize, null)
            .setCancelable(false)
            .create()
        ruleAssetDialogContent = content
        ruleAssetDialog = dialog
        dialog.setOnDismissListener {
            if (ruleAssetDialog === dialog) {
                ruleAssetDialog = null
                ruleAssetDialogContent = null
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (ruleAssetFailure != null) startRuleAssetUpdate()
            }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                if (ruleAssetFailure != null) {
                    dialog.dismiss()
                    clearRuleAssetUpdate()
                } else {
                    dialog.dismiss()
                    snackbar(R.string.route_asset_update_background).show()
                }
            }
            renderRuleAssetDialog()
        }
        dialog.show()
    }

    private fun renderRuleAssetDialog() {
        val content = ruleAssetDialogContent ?: return
        val status = content.findViewById<TextView>(R.id.rule_asset_update_status)
        val indicator = content.findViewById<LinearProgressIndicator>(
            R.id.rule_asset_update_progress,
        )
        val detail = content.findViewById<TextView>(R.id.rule_asset_update_detail)
        val dialog = ruleAssetDialog
        val actions = ruleAssetDialogActions(
            hasResult = ruleAssetResult != null,
            hasFailure = ruleAssetFailure != null,
        )
        dialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.isVisible = actions.showRetry
        dialog?.getButton(DialogInterface.BUTTON_NEGATIVE)?.apply {
            isVisible = actions.showSecondaryAction
            setText(
                if (actions.secondaryActionClosesUpdate) {
                    R.string.action_close
                } else {
                    R.string.minimize
                }
            )
        }
        ruleAssetResult?.let { result ->
            status.text = ruleAssetResultMessage(result, updatingRuleName)
            indicator.isVisible = true
            setProgressMode(indicator, indeterminate = false, value = 100)
            detail.isVisible = false
            return
        }
        ruleAssetFailure?.let { failure ->
            status.text = failure
            indicator.isVisible = false
            detail.isVisible = false
            return
        }
        val progress = ruleAssetProgress ?: return
        indicator.isVisible = true
        when (progress.phase) {
            RuleAssetsUpdater.UpdatePhase.CHECKING -> {
                status.setText(R.string.route_asset_checking_github)
                setProgressMode(indicator, indeterminate = true)
                detail.isVisible = false
            }
            RuleAssetsUpdater.UpdatePhase.SWITCHING_SOURCE -> {
                status.setText(R.string.route_asset_switching_source)
                setProgressMode(indicator, indeterminate = true)
                detail.isVisible = false
            }
            RuleAssetsUpdater.UpdatePhase.DOWNLOADING -> {
                status.setText(R.string.route_asset_downloading)
                val total = progress.totalBytes
                setProgressMode(
                    indicator,
                    indeterminate = total <= 0,
                    value = if (total > 0) {
                        ((progress.downloadedBytes * 100) / total).coerceIn(0, 100).toInt()
                    } else {
                        0
                    },
                )
                detail.isVisible = true
                detail.text = if (total > 0) {
                    getString(
                        R.string.route_asset_download_progress,
                        Formatter.formatShortFileSize(requireContext(), progress.downloadedBytes),
                        Formatter.formatShortFileSize(requireContext(), total),
                    )
                } else {
                    Formatter.formatShortFileSize(requireContext(), progress.downloadedBytes)
                }
            }
            RuleAssetsUpdater.UpdatePhase.VERIFYING -> {
                status.setText(R.string.route_asset_verifying)
                setProgressMode(indicator, indeterminate = true)
                detail.isVisible = false
            }
        }
    }

    private fun setProgressMode(
        indicator: LinearProgressIndicator,
        indeterminate: Boolean,
        value: Int = 0,
    ) {
        if (indicator.isIndeterminate != indeterminate) {
            indicator.visibility = View.INVISIBLE
            indicator.isIndeterminate = indeterminate
            indicator.visibility = View.VISIBLE
        }
        if (!indeterminate) indicator.setProgressCompat(value, true)
    }

    override fun onDestroyView() {
        ruleAssetUpdateJob?.cancel()
        ruleAssetUpdateJob = null
        clearRuleAssetUpdate()
        if (::ruleAdapter.isInitialized) {
            if (::ruleListView.isInitialized) {
                for (index in 0 until ruleListView.childCount) {
                    (ruleListView.getChildViewHolder(ruleListView.getChildAt(index)) as? RuleAdapter.RuleHolder)
                        ?.stopUpdateAnimation()
                }
            }
            ProfileManager.removeListener(ruleAdapter)
        }
        ruleAssetDialog?.dismiss()
        super.onDestroyView()
    }

    inner class RuleAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>(), ProfileManager.RuleListener, UndoSnackbarManager.Interface<RuleEntity> {

        val ruleList = ArrayList<RuleEntity>()
        private val recyclerView = ruleListView

        suspend fun reload(afterReload: (() -> Unit)? = null) {
            val rules = ProfileManager.getRules()
            recyclerView.post {
                if (recyclerView.adapter !== this@RuleAdapter) return@post
                ruleList.clear()
                ruleList.addAll(rules)
                notifyDataSetChanged()
                afterReload?.invoke()
            }
        }

        init {
            setHasStableIds(true)
            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder = RuleHolder(LayoutRouteItemBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder as RuleHolder).bind(ruleList[position])
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder as? RuleHolder)?.stopUpdateAnimation()
            super.onViewRecycled(holder)
        }

        override fun getItemCount() = ruleList.size

        override fun getItemId(position: Int) = ruleList[position].id

        private val updated = LinkedHashMap<Long, RuleEntity>()
        fun move(from: Int, to: Int): Boolean {
            if (from == to || from !in ruleList.indices || to !in ruleList.indices) return false

            val moved = ruleList.removeAt(from)
            ruleList.add(to, moved)
            ruleList.forEachIndexed { index, item ->
                val order = (index + 1).toLong()
                if (item.userOrder != order) {
                    item.userOrder = order
                    updated[item.id] = item
                }
            }
            notifyItemMoved(from, to)
            return true
        }

        fun commitMove() {
            val changes = updated.values.map { it.copy() }
            updated.clear()
            if (changes.isEmpty()) return
            runOnDefaultDispatcher {
                SagerDatabase.rulesDao.updateRules(changes)
                needReload()
            }
        }

        fun remove(index: Int): RuleEntity? {
            val rule = ruleList.getOrNull(index) ?: return null
            if (rule.isDefaultChinaDirectRule()) {
                notifyItemChanged(index)
                return null
            }
            ruleList.removeAt(index)
            notifyItemRemoved(index)
            return rule
        }

        override fun undo(actions: List<Pair<Int, RuleEntity>>) {
            for ((index, item) in actions) {
                ruleList.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, RuleEntity>>) {
            val rules = actions.map { it.second }
            runOnDefaultDispatcher {
                ProfileManager.deleteRules(rules)
            }
        }

        override suspend fun onAdd(rule: RuleEntity) {
            recyclerView.post {
                if (recyclerView.adapter !== this@RuleAdapter) return@post
                ruleList.add(rule)
                notifyItemInserted(ruleList.lastIndex)
                needReload()
            }
        }

        override suspend fun onUpdated(rule: RuleEntity) {
            recyclerView.post {
                if (recyclerView.adapter !== this@RuleAdapter) return@post
                val index = ruleList.indexOfFirst { it.id == rule.id }
                if (index == -1) return@post
                ruleList[index] = rule
                notifyItemChanged(index)
                needReload()
            }
        }

        override suspend fun onRemoved(ruleId: Long) {
            recyclerView.post {
                if (recyclerView.adapter !== this@RuleAdapter) return@post
                val index = ruleList.indexOfFirst { it.id == ruleId }
                if (index == -1) {
                    needReload()
                } else {
                    ruleList.removeAt(index)
                    notifyItemRemoved(index)
                    needReload()
                }
            }
        }

        override suspend fun onCleared() {
            recyclerView.post {
                if (recyclerView.adapter !== this@RuleAdapter) return@post
                ruleList.clear()
                notifyDataSetChanged()
                needReload()
            }
        }

        inner class RuleHolder(binding: LayoutRouteItemBinding) : RecyclerView.ViewHolder(binding.root) {

            lateinit var rule: RuleEntity
            private var updateAnimator: ObjectAnimator? = null
            val profileName = binding.profileName
            val profileType = binding.profileType
            val routeOutbound = binding.routeOutbound
            val ruleIcon = binding.ruleIcon
            val editButton = binding.edit
            val shareLayout = binding.share
            val enableSwitch = binding.enable

            fun bind(ruleEntity: RuleEntity) {
                rule = ruleEntity
                val asset = when {
                    rule.isDefaultChinaDomainDirectRule() -> RuleAssetsUpdater.Asset.GEOSITE
                    rule.isDefaultChinaIpDirectRule() -> RuleAssetsUpdater.Asset.GEOIP
                    else -> null
                }
                profileName.text = rule.displayName()
                profileType.text = rule.mkSummary()
                routeOutbound.text = rule.displayOutbound()
                ruleIcon.setImageResource(
                    when {
                        rule.isDefaultChinaDomainDirectRule() -> R.drawable.ic_baseline_domain_24
                        rule.isDefaultChinaIpDirectRule() -> R.drawable.baseline_public_24
                        else -> R.drawable.ic_baseline_rule_folder_24
                    }
                )
                itemView.setOnClickListener {
                    enableSwitch.performClick()
                }
                enableSwitch.setOnCheckedChangeListener(null)
                enableSwitch.isChecked = rule.enabled
                enableSwitch.contentDescription = getString(
                    R.string.rule_toggle_description,
                    rule.displayName(),
                )
                enableSwitch.setOnCheckedChangeListener { _, isChecked ->
                    runOnDefaultDispatcher {
                        rule.enabled = isChecked
                        SagerDatabase.rulesDao.updateRule(rule)
                        onMainDispatcher {
                            needReload()
                        }
                    }
                }
                stopUpdateAnimation()
                val isUpdating = asset != null && asset == updatingAsset &&
                    ruleAssetResult == null && ruleAssetFailure == null
                val canReopenFailure = asset != null && asset == updatingAsset &&
                    ruleAssetFailure != null
                editButton.setImageResource(
                    when {
                        asset == null -> R.drawable.ic_image_edit
                        isUpdating -> R.drawable.ic_baseline_refresh_24
                        else -> R.drawable.ic_baseline_download_24
                    }
                )
                editButton.contentDescription = when {
                    asset == null -> getString(R.string.edit_named_rule, rule.displayName())
                    isUpdating -> getString(
                        R.string.route_asset_updating_named_action,
                        rule.displayName(),
                    )
                    else -> getString(
                        R.string.route_asset_update_named_action,
                        rule.displayName(),
                    )
                }
                editButton.isEnabled = asset == null || updatingAsset == null || isUpdating ||
                    canReopenFailure
                editButton.alpha = if (editButton.isEnabled) 1f else 0.45f
                if (isUpdating) {
                    updateAnimator = ObjectAnimator.ofFloat(editButton, View.ROTATION, 0f, 360f)
                        .apply {
                            duration = 1_000L
                            interpolator = LinearInterpolator()
                            repeatCount = ValueAnimator.INFINITE
                            start()
                        }
                }
                editButton.setOnClickListener {
                    if (asset == null) {
                        startActivity(Intent(it.context, RouteSettingsActivity::class.java).apply {
                            putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, rule.id)
                        })
                    } else {
                        updateRuleAsset(asset, rule.displayName())
                    }
                }
            }

            fun stopUpdateAnimation() {
                updateAnimator?.cancel()
                updateAnimator = null
                editButton.rotation = 0f
            }
        }

    }

    private companion object {
        const val RULE_ASSET_RESULT_DISPLAY_MILLIS = 1_500L
    }

}
