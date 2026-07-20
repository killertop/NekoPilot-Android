package io.nekohasekai.sagernet.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.RuleAssetsUpdater
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var ruleAssetFailure: String? = null
    private var ruleAssetDialog: AlertDialog? = null
    private var ruleAssetDialogContent: View? = null

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

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                ruleAdapter.remove(index)
                undoManager.remove(index to (viewHolder as RuleAdapter.RuleHolder).rule)
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
        ruleAssetFailure = null
        ruleAssetProgress = RuleAssetsUpdater.UpdateProgress(
            asset,
            RuleAssetsUpdater.UpdatePhase.CHECKING,
        )
        ruleAdapter.notifyDataSetChanged()
        showRuleAssetDialog()
        lifecycleScope.launch {
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
                if (result == RuleAssetsUpdater.UpdateResult.UPDATED && DataStore.serviceState.started) {
                    SagerNet.reloadService()
                }
                val resultWasVisible = ruleAssetDialog?.isShowing == true
                ruleAssetResult = result
                ruleAssetProgress = null
                ruleAdapter.notifyDataSetChanged()
                renderRuleAssetDialog()
                delay(RULE_ASSET_RESULT_DISPLAY_MILLIS)
                ruleAssetDialog?.dismiss()
                if (!resultWasVisible) snackbar(ruleAssetResultMessage(result, ruleName)).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val failureWasVisible = ruleAssetDialog?.isShowing == true
                val message = getString(
                    R.string.route_asset_rule_failed,
                    ruleName,
                    error.readableMessage,
                )
                ruleAssetFailure = message
                ruleAssetProgress = null
                ruleAdapter.notifyDataSetChanged()
                renderRuleAssetDialog()
                delay(RULE_ASSET_RESULT_DISPLAY_MILLIS)
                ruleAssetDialog?.dismiss()
                if (!failureWasVisible) snackbar(message).show()
            } finally {
                updatingAsset = null
                updatingRuleName = ""
                ruleAssetProgress = null
                ruleAssetResult = null
                ruleAssetFailure = null
                if (::ruleAdapter.isInitialized) ruleAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun ruleAssetResultMessage(
        result: RuleAssetsUpdater.UpdateResult,
        ruleName: String,
    ) = getString(
        when (result) {
            RuleAssetsUpdater.UpdateResult.UPDATED -> R.string.route_asset_rule_updated
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
            .setNegativeButton(R.string.minimize) { _, _ ->
                if (ruleAssetResult == null && ruleAssetFailure == null) {
                    snackbar(R.string.route_asset_update_background).show()
                }
            }
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
        dialog.show()
        renderRuleAssetDialog()
    }

    private fun renderRuleAssetDialog() {
        val content = ruleAssetDialogContent ?: return
        val status = content.findViewById<TextView>(R.id.rule_asset_update_status)
        val indicator = content.findViewById<LinearProgressIndicator>(
            R.id.rule_asset_update_progress,
        )
        val detail = content.findViewById<TextView>(R.id.rule_asset_update_detail)
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

    override fun onDestroy() {
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
        super.onDestroy()
    }

    inner class RuleAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>(), ProfileManager.RuleListener, UndoSnackbarManager.Interface<RuleEntity> {

        val ruleList = ArrayList<RuleEntity>()
        suspend fun reload(afterReload: (() -> Unit)? = null) {
            val rules = ProfileManager.getRules()
            ruleListView.post {
                ruleList.clear()
                ruleList.addAll(rules)
                ruleAdapter.notifyDataSetChanged()
                afterReload?.invoke()
            }
        }

        init {
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
            if (from == to) return false

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

        fun commitMove() = runOnDefaultDispatcher {
            if (updated.isNotEmpty()) {
                SagerDatabase.rulesDao.updateRules(updated.values.toList())
                updated.clear()
                needReload()
            }
        }

        fun remove(index: Int) {
            ruleList.removeAt(index)
            notifyItemRemoved(index)
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
            ruleListView.post {
                ruleList.add(rule)
                ruleAdapter.notifyItemInserted(ruleList.lastIndex)
                needReload()
            }
        }

        override suspend fun onUpdated(rule: RuleEntity) {
            val index = ruleList.indexOfFirst { it.id == rule.id }
            if (index == -1) return
            ruleListView.post {
                ruleList[index] = rule
                ruleAdapter.notifyItemChanged(index)
                needReload()
            }
        }

        override suspend fun onRemoved(ruleId: Long) {
            val index = ruleList.indexOfFirst { it.id == ruleId }
            if (index == -1) {
                onMainDispatcher {
                    needReload()
                }
            } else ruleListView.post {
                ruleList.removeAt(index)
                ruleAdapter.notifyItemRemoved(index)
                needReload()
            }
        }

        override suspend fun onCleared() {
            ruleListView.post {
                ruleList.clear()
                ruleAdapter.notifyDataSetChanged()
                needReload()
            }
        }

        inner class RuleHolder(binding: LayoutRouteItemBinding) : RecyclerView.ViewHolder(binding.root) {

            lateinit var rule: RuleEntity
            private var updateAnimator: ObjectAnimator? = null
            val profileName = binding.profileName
            val profileType = binding.profileType
            val routeOutbound = binding.routeOutbound
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
                editButton.setImageResource(
                    if (asset == null) R.drawable.ic_image_edit else R.drawable.ic_baseline_update_24
                )
                stopUpdateAnimation()
                val isUpdating = asset != null && asset == updatingAsset &&
                    ruleAssetResult == null && ruleAssetFailure == null
                editButton.contentDescription = getString(
                    when {
                        asset == null -> R.string.edit
                        isUpdating -> R.string.route_asset_updating_action
                        else -> R.string.route_asset_update_action
                    }
                )
                editButton.isEnabled = asset == null || updatingAsset == null || isUpdating
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
