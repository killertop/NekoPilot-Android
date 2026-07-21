package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceDataStore
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.CONNECTION_TEST_URL
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SelectedProfileReloadCoordinator
import io.nekohasekai.sagernet.bg.proto.TestInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.resolveGroupId
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applicationScope
import io.nekohasekai.sagernet.ktx.broadcastReceiver
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false, val selectedItem: ProxyEntity? = null, val titleRes: Int = 0
) : ToolbarFragment(R.layout.layout_group_list),
    Toolbar.OnMenuItemClickListener,
    OnPreferenceDataStoreChangeListener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    private companion object {
        const val UNIFIED_PAGE_ID = Long.MIN_VALUE
        const val TEST_COMPLETION_VISIBLE_MS = 1_800L
        const val TEST_RESULT_BATCH_MS = 120L
        const val LARGE_LIST_DIFF_THRESHOLD = 2_000
        const val CONNECTION_ERROR_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }

    lateinit var adapter: GroupPagerAdapter
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2
    lateinit var emptyState: View

    private var connectionFab: View? = null
    private var connectionToggle: MaterialButton? = null
    private var connectionProgress: CircularProgressIndicator? = null
    private var hasSelectedProfile = false
    private val emptyStateRevision = AtomicInteger()
    private var activeTestCancel: (() -> Unit)? = null
    private var profilesChangedReceiverRegistered = false
    private val profilesChangedReceiver = broadcastReceiver { _, _ ->
        if (isAdded && ::adapter.isInitialized) adapter.reload()
    }

    fun getCurrentGroupFragment(): GroupFragment? {
        return try {
            childFragmentManager.findFragmentByTag("f$UNIFIED_PAGE_ID") as GroupFragment?
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!select) {
            toolbar.setTitle(R.string.menu_home)
            toolbar.inflateMenu(R.menu.home_actions_menu)
            toolbar.setOnMenuItemClickListener(this)
        } else {
            toolbar.setTitle(titleRes)
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationContentDescription(R.string.navigate_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
        }

        groupPager = view.findViewById(R.id.group_pager)
        tabLayout = view.findViewById(R.id.group_tab)
        emptyState = view.findViewById(R.id.nodes_empty_state)
        if (!select) {
            emptyState.setOnClickListener {
                (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_nodes)
            }
        }
        adapter = GroupPagerAdapter()
        ProfileManager.addListener(adapter)
        GroupManager.addListener(adapter)

        groupPager.adapter = adapter
        groupPager.offscreenPageLimit = 1
        tabLayout.isGone = true
        // The pager must own the adapter before its first asynchronous database snapshot is
        // applied. Starting this from GroupPagerAdapter.init could race on a cold launch and
        // leave both the page list and the empty-state overlay unbound.
        adapter.reload()

        TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                tab.text = adapter.groupList[position].displayName()
            }
            tab.view.setOnLongClickListener { // clear toast
                true
            }
        }.attach()

        if (!select) setupConnectionAction(view)

        toolbar.setOnClickListener {
            val fragment = getCurrentGroupFragment()

            if (fragment != null) {
                val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                val selectedProfileIndex =
                    fragment.adapter!!.configurationIdList.indexOf(selectedProxy)
                if (selectedProfileIndex != -1) {
                    val layoutManager = fragment.layoutManager
                    val first = layoutManager.findFirstVisibleItemPosition()
                    val last = layoutManager.findLastVisibleItemPosition()

                    if (selectedProfileIndex !in first..last) {
                        fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                        return@setOnClickListener
                    }

                }

                fragment.configurationListView.scrollTo(0)
            }

        }

        DataStore.profileCacheStore.registerChangeListener(this)
        if (!select) DataStore.configurationStore.registerChangeListener(this)
        if (!select) {
            ContextCompat.registerReceiver(
                requireContext(),
                profilesChangedReceiver,
                IntentFilter(Action.PROFILES_CHANGED),
                "${requireContext().packageName}.permission.SERVICE_CONTROL",
                null,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            profilesChangedReceiverRegistered = true
        }
    }

    private fun setupConnectionAction(view: View) {
        connectionFab = view.findViewById(R.id.connection_fab)
        connectionToggle = view.findViewById<MaterialButton>(R.id.connection_toggle).also {
            it.setOnClickListener { (requireActivity() as MainActivity).toggleService() }
        }
        connectionProgress = view.findViewById(R.id.connection_progress)
        renderConnectionState(DataStore.serviceState)
        refreshConnectionProfile()
    }

    private fun setEmptyStateVisible(visible: Boolean) {
        emptyState.isVisible = visible
        connectionFab?.isVisible = !visible
    }

    private fun refreshEmptyState() {
        runOnMainDispatcher {
            if (
                !isAdded || !::emptyState.isInitialized ||
                !::adapter.isInitialized || !::groupPager.isInitialized
            ) return@runOnMainDispatcher
            val revision = emptyStateRevision.incrementAndGet()
            this@ConfigurationFragment.runOnLifecycleDispatcher {
                val isEmpty = SagerDatabase.proxyDao.countAll() == 0L
                onMainDispatcher {
                    if (revision == emptyStateRevision.get()) {
                        setEmptyStateVisible(isEmpty)
                    }
                }
            }
        }
    }

    fun renderConnectionState(state: BaseService.State) {
        if (select || connectionToggle == null) return
        val statusColor = requireContext().getColour(
            when (state) {
                BaseService.State.Connected -> R.color.np_success
                BaseService.State.Connecting, BaseService.State.Stopping -> R.color.np_warning
                else -> R.color.np_disconnected
            }
        )
        val busy = state == BaseService.State.Connecting || state == BaseService.State.Stopping
        connectionProgress?.apply {
            isVisible = busy
            setIndicatorColor(statusColor)
        }
        connectionToggle?.apply {
            val canStart = hasSelectedProfile &&
                (state == BaseService.State.Stopped || state == BaseService.State.Idle)
            backgroundTintList = ColorStateList.valueOf(
                requireContext().getColour(
                    when {
                        state == BaseService.State.Connected -> R.color.np_success
                        state == BaseService.State.Connecting || state == BaseService.State.Stopping -> R.color.np_warning
                        canStart -> R.color.np_connection_ready
                        else -> R.color.np_blue_soft
                    }
                )
            )
            iconTint = ColorStateList.valueOf(
                requireContext().getColour(
                    if (state == BaseService.State.Connected || busy) {
                        R.color.white
                    } else if (canStart) {
                        R.color.np_navy
                    } else {
                        R.color.np_text_secondary
                    }
                )
            )
            isEnabled = state.canStop || canStart
            alpha = if (isEnabled) 1f else 0.62f
            contentDescription = getString(
                if (state.canStop) R.string.disconnect else R.string.connect
            )
        }
        refreshVisibleConnectionStatuses()
    }

    private fun refreshVisibleConnectionStatuses() {
        adapter.groupFragments.values.forEach { groupFragment ->
            groupFragment.refreshVisibleConnectionStatuses()
        }
    }

    private fun connectionStatus(profile: ProxyEntity): ConnectionStatus? {
        val profileId = profile.id
        if (select) return null
        val state = DataStore.serviceState
        val isSelected = profileId == DataStore.selectedProxy
        val isCurrent = profileId == DataStore.currentProfile
        if (!isSelected && !(isCurrent && (state == BaseService.State.Connected || state == BaseService.State.Stopping))) {
            return null
        }
        return when (state) {
            BaseService.State.Connecting -> ConnectionStatus(
                getString(R.string.connection_status_connecting),
                R.color.np_warning,
            )

            BaseService.State.Connected -> if (isCurrent) {
                ConnectionStatus(
                    getString(R.string.connection_status_connected),
                    R.color.np_success,
                )
            } else {
                ConnectionStatus(
                    getString(R.string.connection_status_switching),
                    R.color.np_warning,
                )
            }

            BaseService.State.Stopping -> ConnectionStatus(
                getString(
                    if (isCurrent) R.string.connection_status_stopping
                    else R.string.connection_status_switching
                ),
                R.color.np_warning,
            )

            BaseService.State.Stopped, BaseService.State.Idle -> DataStore.lastConnectionError
                .takeIf {
                    it.isNotBlank() && DataStore.lastConnectionErrorProfile == profileId &&
                        System.currentTimeMillis() - DataStore.lastConnectionErrorTime <
                        CONNECTION_ERROR_MAX_AGE_MS
                }
                ?.let {
                val friendly = Protocols.genFriendlyMsg(it).takeIf { message ->
                    message.isNotBlank() && message != it
                } ?: it
                ConnectionStatus(
                    getString(R.string.connection_status_failed, friendly),
                    R.color.np_error,
                    it,
                )
            } ?: if (profile.status == 0) {
                ConnectionStatus(
                    getString(R.string.connection_status_disconnected),
                    R.color.np_disconnected,
                )
            } else {
                null
            }
        }
    }

    private data class ConnectionStatus(
        val text: String,
        val colorRes: Int,
        val error: String? = null,
    )

    fun refreshConnectionProfile() {
        if (select || connectionToggle == null) return
        val selectedId = DataStore.selectedProxy
        runOnLifecycleDispatcher {
            val profile = ProfileManager.getProfile(selectedId)
            onMainDispatcher { updateConnectionProfile(profile) }
        }
    }

    fun updateConnectionProfile(profile: ProxyEntity?) {
        if (select || connectionToggle == null) return
        hasSelectedProfile = profile != null
        renderConnectionState(DataStore.serviceState)
    }

    private fun clearConnectionError() {
        DataStore.lastConnectionError = ""
        DataStore.lastConnectionErrorProfile = 0L
        DataStore.lastConnectionErrorTime = 0L
    }

    override fun onResume() {
        super.onResume()
        if (!select) {
            renderConnectionState(DataStore.serviceState)
            refreshConnectionProfile()
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            if (store === DataStore.configurationStore && key == Key.PROFILE_ID) {
                adapter.groupFragments.values.forEach { groupFragment ->
                    groupFragment.adapter?.notifyDataSetChanged()
                }
                refreshConnectionProfile()
            } else if (
                store === DataStore.configurationStore && key == Key.PROFILE_CURRENT
            ) {
                // The service process publishes the authoritative active node separately from
                // the user's desired selection. Repaint once cross-process persistence lands.
                refreshVisibleConnectionStatuses()
            } else if (store === DataStore.profileCacheStore && key == Key.PROFILE_GROUP) {
                // A profile editor records the destination group in its private cache.
                val targetId = DataStore.editingGroup
                val currentGroup = DataStore.configurationStore.getLong(Key.PROFILE_GROUP, 0L)
                if (targetId > 0 && targetId != currentGroup) {
                    DataStore.selectedGroup = targetId
                }
            }
        }
    }

    override fun onDestroyView() {
        activeTestCancel?.invoke()
        activeTestCancel = null
        if (profilesChangedReceiverRegistered) {
            requireContext().unregisterReceiver(profilesChangedReceiver)
            profilesChangedReceiverRegistered = false
        }
        DataStore.profileCacheStore.unregisterChangeListener(this)
        if (!select) DataStore.configurationStore.unregisterChangeListener(this)

        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }
        emptyStateRevision.incrementAndGet()
        super.onDestroyView()
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        val fragment = getCurrentGroupFragment()
        fragment?.configurationListView?.apply {
            if (!hasFocus()) requestFocus()
        }
        return super.onKeyDown(ketCode, event)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_connection_test_delete_unavailable -> {
                runOnDefaultDispatcher {
                    val subscriptionGroupIds = SagerDatabase.groupDao.allGroups()
                        .filter { it.type == GroupType.SUBSCRIPTION }
                        .mapTo(hashSetOf(), ProxyGroup::id)
                    val profiles = SagerDatabase.proxyDao.getNodeList().map(
                        ProxyEntity.NodeListItem::toStub,
                    )
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (
                            profile.groupId !in subscriptionGroupIds &&
                            profile.status != 0 && profile.status != 1
                        ) {
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(R.string.delete_confirm_prompt)
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    adapter.groupFragments[UNIFIED_PAGE_ID]?.adapter
                                        ?.removeProfiles(toClear.mapTo(hashSetOf()) { it.id })
                                    runOnDefaultDispatcher {
                                        // Keep the parent adapter and empty state informed when
                                        // the last unavailable node is removed.
                                        ProfileManager.deleteProfiles(toClear)
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_node_speed_test -> {
                nodeSpeedTest()
            }
        }
        return true
    }

    inner class TestDialog {
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.connection_test)
            .setView(binding.root)
            .setPositiveButton(R.string.connection_test_background) { _, _ ->
                minimize()
            }
            .setNegativeButton(R.string.connection_test_stop) { _, _ ->
                cancel()
            }
            .setCancelable(false)

        // Initialized to safe no-ops so even an immediate dialog callback can never race a
        // lateinit assignment. nodeSpeedTest replaces both before the dialog is shown.
        var cancel: () -> Unit = {}
        var minimize: () -> Unit = {}

        val dialogStatus = AtomicInteger(0) // 1: hidden 2: closed 3: completed and visible
        val results: MutableSet<ProxyEntity> = ConcurrentHashMap.newKeySet()
        var proxyN = 0
        val finishedN = AtomicInteger(0)
        val availableN = AtomicInteger(0)
        val unavailableN = AtomicInteger(0)

        fun setTotal(total: Int) {
            proxyN = total
            runOnMainDispatcher {
                if (!isAdded) return@runOnMainDispatcher
                binding.testProgress.apply {
                    isIndeterminate = total <= 0
                    max = total.coerceAtLeast(1)
                    setProgressCompat(0, false)
                }
                binding.testSummary.setText(R.string.connection_test_preparing)
                binding.progress.text = if (total > 0) {
                    getString(R.string.connection_test_progress, 0, total)
                } else {
                    getString(R.string.connection_test_preparing)
                }
                binding.nowTesting.setText(R.string.connection_test_waiting)
            }
        }

        fun update(profile: ProxyEntity) {
            if (dialogStatus.get() == 2) return
            results.add(profile)
            if (profile.status == 1) {
                availableN.incrementAndGet()
            } else {
                unavailableN.incrementAndGet()
            }
            runOnMainDispatcher {
                val context = context ?: return@runOnMainDispatcher
                val progress = finishedN.addAndGet(1)
                val status = dialogStatus.get()
                adapter.groupFragments[UNIFIED_PAGE_ID]?.applyTestResult(profile)
                if (status >= 1) return@runOnMainDispatcher
                if (!isAdded) return@runOnMainDispatcher

                // refresh dialog

                var profileStatusText: String? = null
                var profileStatusColor = 0

                when (profile.status) {
                    -1 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    0 -> {
                        profileStatusText = getString(R.string.connection_test_testing)
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    1 -> {
                        profileStatusText = profile.downloadMbps?.let {
                            getString(R.string.connection_test_available_speed, profile.ping, it)
                        } ?: getString(R.string.available, profile.ping)
                        profileStatusColor = context.getColour(R.color.np_success)
                    }

                    2 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColour(R.color.np_error)
                    }

                    3 -> {
                        val err = profile.error ?: ""
                        val msg = Protocols.genFriendlyMsg(err)
                        profileStatusText = if (msg != err) msg else getString(R.string.unavailable)
                        profileStatusColor = context.getColour(R.color.np_error)
                    }
                }

                val text = SpannableStringBuilder().apply {
                    append(profile.displayName())
                    append("\n")
                    append(
                        profile.displayType(),
                        ForegroundColorSpan(context.getProtocolColor(profile.type)),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append(" ")
                    append(
                        profileStatusText,
                        ForegroundColorSpan(profileStatusColor),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                binding.nowTesting.text = text
                binding.testProgress.apply {
                    isIndeterminate = false
                    max = proxyN.coerceAtLeast(1)
                    setProgressCompat(progress.coerceAtMost(proxyN), true)
                }
                binding.progress.text = getString(
                    R.string.connection_test_progress,
                    progress,
                    proxyN,
                )
                binding.testSummary.text = getString(
                    R.string.connection_test_summary,
                    availableN.get(),
                    unavailableN.get(),
                )
            }
        }

        fun showCompleted(dialog: AlertDialog?) {
            binding.testProgress.apply {
                isIndeterminate = false
                max = proxyN.coerceAtLeast(1)
                setProgressCompat(proxyN, true)
            }
            binding.progress.text = getString(
                R.string.connection_test_completed_progress,
                finishedN.get(),
                proxyN,
            )
            binding.testSummary.text = getString(
                R.string.connection_test_completed_summary,
                availableN.get(),
                unavailableN.get(),
            )
            binding.nowTesting.setText(R.string.connection_test_completed_hint)
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isVisible = false
            dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.setText(R.string.action_close)
        }

    }

    fun nodeSpeedTest() {
        if (DataStore.runningTest) return
        DataStore.runningTest = true
        refreshVisibleConnectionStatuses()
        // Query before constructing a dialog. The former empty-list path could finish before
        // its cancel callback was initialized, crashing and leaving the global test flag stuck.
        runOnDefaultDispatcher {
            val profilesList = try {
                SagerDatabase.proxyDao.getAll()
            } catch (e: Exception) {
                Logs.w(e)
                DataStore.runningTest = false
                onMainDispatcher {
                    if (isAdded) {
                        refreshVisibleConnectionStatuses()
                        snackbar(getString(R.string.connection_test_error, e.readableMessage)).show()
                    }
                }
                return@runOnDefaultDispatcher
            }
            if (profilesList.isEmpty()) {
                DataStore.runningTest = false
                onMainDispatcher {
                    if (isAdded) {
                        refreshVisibleConnectionStatuses()
                        snackbar(R.string.connection_test_no_group).show()
                    }
                }
                return@runOnDefaultDispatcher
            }
            onMainDispatcher {
                if (!isAdded) {
                    DataStore.runningTest = false
                    return@onMainDispatcher
                }
                startNodeSpeedTest(profilesList)
            }
        }
    }

    private fun startNodeSpeedTest(profilesList: List<ProxyEntity>) {
        val test = TestDialog()
        val finalized = AtomicBoolean(false)
        var dialog: AlertDialog? = null
        var mainJob: Job? = null

        fun finishTest(cancelWorkers: Boolean) {
            if (!finalized.compareAndSet(false, true)) return
            activeTestCancel = null
            val wasMinimized = test.dialogStatus.get() == 1
            if (cancelWorkers || wasMinimized) {
                test.dialogStatus.set(2)
                dialog?.dismiss()
            } else {
                // Fast tests used to dismiss the dialog before the user could tell whether
                // anything worked. Keep the final summary visible while the node list settles
                // into its updated latency order.
                test.dialogStatus.set(3)
                test.showCompleted(dialog)
                test.binding.root.postDelayed({
                    if (test.dialogStatus.compareAndSet(3, 2)) dialog?.dismiss()
                }, TEST_COMPLETION_VISIBLE_MS)
            }

            runOnDefaultDispatcher {
                try {
                    if (cancelWorkers) mainJob?.cancelAndJoin()
                    // Visible rows already receive each result as it arrives. Persist without
                    // sending N redundant adapter updates at completion.
                    ProfileManager.updateTestResults(test.results, notifyListeners = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                } finally {
                    DataStore.runningTest = false
                    onMainDispatcher {
                        if (isAdded) refreshVisibleConnectionStatuses()
                    }
                }
            }
        }

        test.cancel = { finishTest(cancelWorkers = true) }
        activeTestCancel = test.cancel
        test.minimize = {
            if (test.dialogStatus.compareAndSet(0, 1)) {
                dialog?.hide()
            }
        }
        mainJob = applicationScope.launch(
            context = Dispatchers.Default,
            start = CoroutineStart.LAZY,
        ) {
            try {
                val workerCount = DataStore.connectionTestConcurrent.coerceIn(1, 3)
                    .coerceAtMost(profilesList.size)
                val chunks = profilesList.chunked(
                    (profilesList.size + workerCount - 1) / workerCount
                )
                kotlinx.coroutines.coroutineScope {
                    chunks.map { chunk ->
                        launch(Dispatchers.IO) {
                            val testRunner = TestInstance(
                                chunk.first(),
                                CONNECTION_TEST_URL,
                                5000,
                                chunk,
                                DataStore.connectionTestDownload,
                            )
                            testRunner.runBatch(
                                chunk,
                                onResult = { profile, result ->
                                    profile.status = 1
                                    profile.ping = result.latencyMs
                                    profile.downloadMbps = result.downloadMbps
                                    profile.error = null
                                    test.update(profile)
                                },
                                onError = { profile, error ->
                                    profile.status = if (
                                        error is PluginManager.PluginNotFoundException
                                    ) 2 else 3
                                    profile.ping = 0
                                    profile.downloadMbps = null
                                    profile.error = error.readableMessage
                                    test.update(profile)
                                },
                            )
                        }
                    }.joinAll()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
            }
        }.also { job ->
            job.invokeOnCompletion {
                runOnMainDispatcher { finishTest(cancelWorkers = false) }
            }
        }
        dialog = test.builder.show()
        test.setTotal(profilesList.size)
        mainJob?.start()
    }

    inner class GroupPagerAdapter : FragmentStateAdapter(this),
        ProfileManager.Listener,
        GroupManager.Listener {

        var groupList: ArrayList<ProxyGroup> = ArrayList()
        var groupFragments: HashMap<Long, GroupFragment> = HashMap()
        private val reloadRevision = AtomicInteger()

        fun reload() {
            val revision = reloadRevision.incrementAndGet()
            this@ConfigurationFragment.runOnLifecycleDispatcher {
                var newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                if (newGroupList.isEmpty()) {
                    SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                    newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                }
                val selectedProfileGroup = selectedItem?.groupId
                    ?: DataStore.selectedProxy.takeIf { it > 0L }
                        ?.let(SagerDatabase.proxyDao::getById)
                        ?.groupId
                val requestedGroup = selectedItem?.groupId
                    ?: DataStore.configurationStore.getLong(Key.PROFILE_GROUP, -1L)
                val resolvedGroup = newGroupList.resolveGroupId(
                    requestedGroup,
                    selectedProfileGroup,
                )

                onMainDispatcher {
                    if (revision == reloadRevision.get()) {
                        groupList = newGroupList
                        groupFragments.keys.retainAll(setOf(UNIFIED_PAGE_ID))
                        notifyDataSetChanged()
                        DataStore.selectedGroup = resolvedGroup
                        groupPager.setCurrentItem(0, false)
                        tabLayout.isGone = true
                        toolbar.elevation = 0F
                        groupFragments[UNIFIED_PAGE_ID]?.adapter?.reloadProfiles()
                        refreshEmptyState()
                    }
                }
            }
        }

        override fun getItemCount(): Int {
            return if (groupList.isEmpty()) 0 else 1
        }

        override fun createFragment(position: Int): Fragment {
            return GroupFragment.newUnifiedInstance().apply {
                groupFragments[UNIFIED_PAGE_ID] = this
                selected = true
            }
        }

        override fun getItemId(position: Int): Long {
            return UNIFIED_PAGE_ID
        }

        override fun containsItem(itemId: Long): Boolean {
            return itemId == UNIFIED_PAGE_ID && groupList.isNotEmpty()
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            DataStore.selectedGroup = group.id
            reload()
        }

        override suspend fun groupRemoved(groupId: Long) {
            reload()
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            refreshEmptyState()
            tabLayout.post {
                val index = groupList.indexOfFirst { it.id == group.id }
                if (index < 0) {
                    reload()
                } else {
                    groupList[index] = group
                }
            }
        }

        override suspend fun groupUpdated(groupId: Long) {
            refreshEmptyState()
            // The child adapter is also a GroupManager listener and owns profile snapshots.
            // Reloading here as well doubled full-table reads and bean decoding for every
            // subscription progress event. Cross-process changes use PROFILES_CHANGED above.
        }

        override suspend fun onAdd(profile: ProxyEntity) {
            refreshEmptyState()
            if (!select && profile.id == DataStore.selectedProxy) {
                connectionToggle?.post { updateConnectionProfile(profile) }
            }
            groupPager.post {
                if (groupList.none { it.id == profile.groupId }) {
                    DataStore.selectedGroup = profile.groupId
                    reload()
                }
            }
        }

        override suspend fun onUpdated(profile: ProxyEntity) {
            if (!select && profile.id == DataStore.selectedProxy) {
                connectionToggle?.post { updateConnectionProfile(profile) }
            }
        }

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            refreshEmptyState()
            if (!select && profileId == DataStore.selectedProxy) {
                connectionToggle?.post { refreshConnectionProfile() }
            }
        }
    }

    class GroupFragment : Fragment() {

        companion object {
            private const val ARG_PROXY_GROUP = "proxyGroup"
            private const val ARG_UNIFIED = "unified"

            fun newUnifiedInstance() = GroupFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_UNIFIED, true)
                }
            }
        }

        lateinit var proxyGroup: ProxyGroup
        var unified = false
        var selected = false

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val state = savedInstanceState ?: arguments ?: Bundle()
            unified = state.getBoolean(ARG_UNIFIED, false)
            if (unified) {
                proxyGroup = ProxyGroup(id = ConfigurationFragment.UNIFIED_PAGE_ID)
                return
            }
            BundleCompat.getParcelable(
                state,
                ARG_PROXY_GROUP,
                ProxyGroup::class.java,
            )?.let { proxyGroup = it }
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            return LayoutProfileListBinding.inflate(inflater).root
        }

        lateinit var undoManager: UndoSnackbarManager<ProxyEntity>
        var adapter: ConfigurationAdapter? = null
        private var showNodeIp = DataStore.showNodeIp

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)

            if (::proxyGroup.isInitialized) {
                outState.putParcelable("proxyGroup", proxyGroup)
            }
            outState.putBoolean(ARG_UNIFIED, unified)
        }

        override fun onViewStateRestored(savedInstanceState: Bundle?) {
            super.onViewStateRestored(savedInstanceState)

            savedInstanceState?.let {
                unified = it.getBoolean(ARG_UNIFIED, unified)
                BundleCompat.getParcelable(it, "proxyGroup", ProxyGroup::class.java)
            }?.also {
                proxyGroup = it
            }
        }

        private val isEnabled: Boolean
            get() {
                return DataStore.serviceState.let { it.canStop || it == BaseService.State.Stopped }
            }

        lateinit var layoutManager: LinearLayoutManager
        lateinit var configurationListView: RecyclerView

        val select by lazy {
            try {
                (parentFragment as ConfigurationFragment).select
            } catch (e: Exception) {
                Logs.e(e)
                false
            }
        }
        val selectedItem by lazy {
            try {
                (parentFragment as ConfigurationFragment).selectedItem
            } catch (e: Exception) {
                Logs.e(e)
                null
            }
        }

        override fun onResume() {
            super.onResume()

            val currentShowNodeIp = DataStore.showNodeIp
            if (showNodeIp != currentShowNodeIp) {
                showNodeIp = currentShowNodeIp
                adapter?.notifyDataSetChanged()
            }

            if (!::configurationListView.isInitialized) return
            configurationListView.requestFocus()
            refreshVisibleConnectionStatuses()
        }

        fun refreshVisibleConnectionStatuses() {
            if (!::configurationListView.isInitialized) return
            repeat(configurationListView.childCount) { index ->
                (configurationListView.getChildViewHolder(configurationListView.getChildAt(index))
                    as? ConfigurationHolder)?.renderStatus()
            }
        }

        fun applyTestResult(profile: ProxyEntity) {
            if (!unified && profile.groupId != proxyGroup.id) return
            adapter?.applyTestResult(profile)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            if (!::proxyGroup.isInitialized) return

            // FragmentStateAdapter can restore an existing page without calling createFragment().
            // Re-register the live page so incremental latency results can still reorder it.
            (parentFragment as? ConfigurationFragment)?.adapter?.groupFragments
                ?.set(ConfigurationFragment.UNIFIED_PAGE_ID, this)

            configurationListView = view.findViewById(R.id.configuration_list)
            layoutManager = FixedLinearLayoutManager(configurationListView)
            configurationListView.layoutManager = layoutManager
            adapter = ConfigurationAdapter()
            ProfileManager.addListener(adapter!!)
            GroupManager.addListener(adapter!!)
            configurationListView.adapter = adapter
            // A large latency test can move hundreds of rows at once. Instant updates keep the
            // visible order accurate without retaining obsolete holders for move animations.
            configurationListView.itemAnimator = null
            configurationListView.setItemViewCacheSize(8)

            // Always load from the database after the page owns its new adapter. RecyclerView's
            // child count is a rendering detail and may still be non-zero while a restored view
            // the page permanently blank even though its profiles still existed in Room.
            runOnLifecycleDispatcher {
                adapter?.reloadProfiles()
            }

            if (!select) {

                undoManager = UndoSnackbarManager(activity as MainActivity, adapter!!)

                ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
                ) {
                    override fun getSwipeDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ): Int {
                        return 0
                    }

                    override fun getDragDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) = if (!unified && isEnabled) {
                        super.getDragDirs(recyclerView, viewHolder)
                    } else {
                        0
                    }

                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    }

                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
                    ): Boolean {
                        adapter?.move(
                            viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                        )
                        return true
                    }

                    override fun clearView(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) {
                        super.clearView(recyclerView, viewHolder)
                        adapter?.commitMove()
                    }
                }).attachToRecyclerView(configurationListView)

            }

        }

        override fun onDestroyView() {
            (parentFragment as? ConfigurationFragment)?.adapter?.groupFragments?.let { fragments ->
                if (fragments[ConfigurationFragment.UNIFIED_PAGE_ID] === this) {
                    fragments.remove(ConfigurationFragment.UNIFIED_PAGE_ID)
                }
            }
            adapter?.let {
                it.cancelPendingTestResults()
                ProfileManager.removeListener(it)
                GroupManager.removeListener(it)
            }
            adapter = null
            if (::undoManager.isInitialized) undoManager.flush()
            super.onDestroyView()
        }

        inner class ConfigurationAdapter : RecyclerView.Adapter<ConfigurationHolder>(),
            ProfileManager.Listener,
            GroupManager.Listener,
            UndoSnackbarManager.Interface<ProxyEntity> {

            init {
                setHasStableIds(true)
            }

            var configurationIdList: MutableList<Long> = mutableListOf()
            val configurationList = HashMap<Long, ProxyEntity>()
            private val snapshotRevision = AtomicInteger()
            private var hasCompletedInitialLoad = false
            private val pendingTestResults = LinkedHashMap<Long, ProxyEntity>()
            private var testResultFlushScheduled = false
            private val testResultFlush = Runnable { flushTestResults() }

            private fun isAttachedAdapter(): Boolean =
                this@ConfigurationAdapter === this@GroupFragment.adapter

            private fun replaceVisibleIds(newIds: List<Long>): Boolean {
                val oldIds = configurationIdList.toList()
                if (maxOf(oldIds.size, newIds.size) >= LARGE_LIST_DIFF_THRESHOLD) {
                    configurationIdList.clear()
                    configurationIdList.addAll(newIds)
                    notifyDataSetChanged()
                    return true
                }
                val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = oldIds.size
                    override fun getNewListSize() = newIds.size
                    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                        oldIds[oldItemPosition] == newIds[newItemPosition]
                    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = true
                })
                configurationIdList.clear()
                configurationIdList.addAll(newIds)
                diff.dispatchUpdatesTo(this)
                return false
            }

            private fun getItem(profileId: Long): ProxyEntity =
                checkNotNull(configurationList[profileId]) {
                    "Profile $profileId is missing from the loaded group snapshot"
                }

            private fun getItemAt(index: Int) = getItem(configurationIdList[index])

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): ConfigurationHolder {
                return ConfigurationHolder(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.layout_profile, parent, false)
                )
            }

            override fun getItemId(position: Int): Long {
                return configurationIdList[position]
            }

            override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
                configurationList[configurationIdList[position]]?.let(holder::bind)
            }

            override fun getItemCount(): Int {
                return configurationIdList.size
            }

            private val updated = HashSet<ProxyEntity>()

            fun move(from: Int, to: Int) {
                val first = getItemAt(from)
                var previousOrder = first.userOrder
                val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                    -1, to + 1 downTo from
                )
                for (i in range) {
                    val next = getItemAt(i + step)
                    val order = next.userOrder
                    next.userOrder = previousOrder
                    previousOrder = order
                    configurationIdList[i] = next.id
                    updated.add(next)
                }
                first.userOrder = previousOrder
                configurationIdList[to] = first.id
                updated.add(first)
                notifyItemMoved(from, to)
            }

            fun commitMove() {
                val pendingUpdates = updated.map {
                    ProxyEntity.OrderUpdate(it.id, it.userOrder)
                }
                updated.clear()
                runOnDefaultDispatcher {
                    SagerDatabase.proxyDao.updateOrders(pendingUpdates)
                }
            }

            fun remove(pos: Int) {
                if (pos < 0) return
                snapshotRevision.incrementAndGet()
                configurationIdList.removeAt(pos)
                notifyItemRemoved(pos)
            }

            fun removeProfiles(profileIds: Set<Long>) {
                if (profileIds.isEmpty()) return
                snapshotRevision.incrementAndGet()
                profileIds.forEach {
                    configurationList.remove(it)
                }
                replaceVisibleIds(configurationIdList.filterNot(profileIds::contains))
            }

            fun applyTestResult(profile: ProxyEntity) {
                val current = configurationList[profile.id] ?: return
                // A subscription refresh or editor save may replace the endpoint while a test
                // is still running. Never paint the old endpoint's result onto the new node.
                if (
                    current.groupId != profile.groupId || current.type != profile.type ||
                    current.configRevision != profile.configRevision
                ) return
                pendingTestResults[profile.id] = profile
                if (testResultFlushScheduled) return
                testResultFlushScheduled = true
                // Coalesce concurrent workers so a large airport performs one sort/diff per
                // visual frame window instead of one full-list sort for every result.
                val batchDelay = when {
                    configurationIdList.size >= 5_000 -> 500L
                    configurationIdList.size >= 1_000 -> 250L
                    else -> TEST_RESULT_BATCH_MS
                }
                configurationListView.postDelayed(testResultFlush, batchDelay)
            }

            private fun flushTestResults() {
                testResultFlushScheduled = false
                if (!isAttachedAdapter() || pendingTestResults.isEmpty()) {
                    pendingTestResults.clear()
                    return
                }
                val changedIds = pendingTestResults.keys.toList()
                pendingTestResults.values.forEach { result ->
                    val current = configurationList[result.id] ?: return@forEach
                    current.status = result.status
                    current.ping = result.ping
                    current.error = result.error
                    current.downloadMbps = result.downloadMbps
                }
                pendingTestResults.clear()
                snapshotRevision.incrementAndGet()
                val comparator = NodeLatencyOrder.comparator<Long>(
                    status = { configurationList[it]?.status ?: 0 },
                    latencyMs = { configurationList[it]?.ping ?: 0 },
                    stableOrder = {
                        configurationList[it]?.let { profile ->
                            if (unified) profile.id else profile.userOrder
                        } ?: Long.MAX_VALUE
                    },
                )
                val distinctChangedIds = changedIds.distinct()
                if (distinctChangedIds.size == 1) {
                    val profileId = distinctChangedIds.single()
                    val oldIndex = configurationIdList.indexOf(profileId)
                    if (oldIndex < 0) return
                    configurationIdList.removeAt(oldIndex)
                    val found = java.util.Collections.binarySearch(
                        configurationIdList,
                        profileId,
                        comparator,
                    )
                    val newIndex = if (found >= 0) found else -found - 1
                    configurationIdList.add(newIndex, profileId)
                    if (oldIndex != newIndex) notifyItemMoved(oldIndex, newIndex)
                    notifyItemChanged(newIndex)
                } else {
                    // Once two results change, the old list no longer satisfies the comparator:
                    // removing one item and binary-searching through the remaining stale order
                    // can place later results incorrectly. Re-sort the immutable snapshot once.
                    val fullRefresh = replaceVisibleIds(configurationIdList.sortedWith(comparator))
                    if (!fullRefresh) {
                        val positionById = configurationIdList.withIndex()
                            .associate { (index, profileId) -> profileId to index }
                        distinctChangedIds.forEach { profileId ->
                            positionById[profileId]?.let(::notifyItemChanged)
                        }
                    }
                }
            }

            fun cancelPendingTestResults() {
                if (::configurationListView.isInitialized) {
                    configurationListView.removeCallbacks(testResultFlush)
                }
                testResultFlushScheduled = false
                pendingTestResults.clear()
            }

            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
                snapshotRevision.incrementAndGet()
                for ((index, item) in actions) {
                    configurationListView.post {
                        if (!isAttachedAdapter()) return@post
                        configurationList[item.id] = item.toListStub()
                        configurationIdList.add(index, item.id)
                        notifyItemInserted(index)
                    }
                }
            }

            override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
                val profiles = actions.map { it.second }
                runOnDefaultDispatcher {
                    ProfileManager.deleteProfiles(profiles)
                }
            }

            override suspend fun onAdd(profile: ProxyEntity) {
                if (!unified && profile.groupId != proxyGroup.id) return
                snapshotRevision.incrementAndGet()

                configurationListView.post {
                    if (!isAttachedAdapter()) return@post
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val pos = itemCount
                    configurationList[profile.id] = profile.toListStub()
                    configurationIdList.add(profile.id)
                    notifyItemInserted(pos)
                }
            }

            override suspend fun onUpdated(profile: ProxyEntity) {
                if (!unified && profile.groupId != proxyGroup.id) return
                snapshotRevision.incrementAndGet()
                configurationListView.post {
                    if (!isAttachedAdapter()) return@post
                    val index = configurationIdList.indexOf(profile.id)
                    if (index < 0) return@post
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val previous = configurationList[profile.id]
                    configurationList[profile.id] = profile.toListStub()
                    if (
                        previous != null &&
                        (previous.status != profile.status || previous.ping != profile.ping ||
                            previous.error != profile.error)
                    ) {
                        applyTestResult(profile)
                    } else {
                        notifyItemChanged(index)
                    }
                }
            }

            override suspend fun onRemoved(groupId: Long, profileId: Long) {
                if (!unified && groupId != proxyGroup.id) return
                snapshotRevision.incrementAndGet()

                configurationListView.post {
                    if (!isAttachedAdapter()) return@post
                    val index = configurationIdList.indexOf(profileId)
                    if (index < 0) return@post
                    configurationIdList.removeAt(index)
                    configurationList.remove(profileId)
                    notifyItemRemoved(index)
                }
            }

            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit

            override suspend fun groupUpdated(group: ProxyGroup) {
                if (!unified && group.id != proxyGroup.id) return
                if (!unified) proxyGroup = group
                reloadProfiles()
            }

            override suspend fun groupUpdated(groupId: Long) {
                if (!unified && groupId != proxyGroup.id) return
                if (!unified) {
                    proxyGroup = SagerDatabase.groupDao.getById(groupId) ?: return
                }
                reloadProfiles()
            }

            fun reloadProfiles() {
                val revision = snapshotRevision.incrementAndGet()
                this@GroupFragment.runOnLifecycleDispatcher {
                    val newProfiles = NodeLatencyOrder.sort(
                        if (unified) {
                            SagerDatabase.proxyDao.getNodeList()
                        } else {
                            SagerDatabase.proxyDao.getNodeListByGroup(proxyGroup.id)
                        },
                        status = ProxyEntity.NodeListItem::status,
                        latencyMs = ProxyEntity.NodeListItem::ping,
                        stableOrder = if (unified) {
                            ProxyEntity.NodeListItem::id
                        } else {
                            ProxyEntity.NodeListItem::userOrder
                        },
                    ).map(ProxyEntity.NodeListItem::toStub)
                    val newProfileIds = newProfiles.map { it.id }
                    val selectedProfileIndex = if (selected) {
                        val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                        newProfileIds.indexOf(selectedProxy)
                    } else {
                        -1
                    }

                    onMainDispatcher {
                        if (revision != snapshotRevision.get() || !isAttachedAdapter()) {
                            return@onMainDispatcher
                        }
                        configurationList.clear()
                        configurationList.putAll(newProfiles.associateBy { it.id })
                        val fullRefresh = replaceVisibleIds(newProfileIds)
                        if (!fullRefresh && itemCount > 0) {
                            notifyItemRangeChanged(0, itemCount)
                        }

                        if (!hasCompletedInitialLoad) {
                            hasCompletedInitialLoad = true
                            if (selectedProfileIndex != -1) {
                                configurationListView.scrollTo(selectedProfileIndex, true)
                            } else if (newProfiles.isNotEmpty()) {
                                configurationListView.scrollTo(0, true)
                            }
                        }
                    }
                }
            }

        }

        val profileAccess = Mutex()

        inner class ConfigurationHolder(val view: View) : RecyclerView.ViewHolder(view) {

            lateinit var entity: ProxyEntity

            val profileName: TextView = view.findViewById(R.id.profile_name)
            val profileType: TextView = view.findViewById(R.id.profile_type)
            val profileAddress: TextView = view.findViewById(R.id.profile_address)
            val profileStatus: TextView = view.findViewById(R.id.profile_status)

            val selectedView: LinearLayout = view.findViewById(R.id.selected_view)
            val editButton: ImageView = view.findViewById(R.id.edit)
            val removeButton: ImageView = view.findViewById(R.id.remove)

            fun bind(proxyEntity: ProxyEntity) {
                val pf = parentFragment as? ConfigurationFragment ?: return

                entity = proxyEntity

                if (select) {
                    view.setOnClickListener {
                        (requireActivity() as SelectCallback).returnProfile(proxyEntity.id)
                    }
                } else {
                    view.setOnClickListener {
                        runOnDefaultDispatcher {
                            var update: Boolean
                            var lastSelected: Long
                            profileAccess.withLock {
                                update = DataStore.selectedProxy != proxyEntity.id
                                lastSelected = DataStore.selectedProxy
                                DataStore.selectedProxy = proxyEntity.id
                                DataStore.selectedGroup = proxyEntity.groupId
                                onMainDispatcher ui@{
                                    if (!isAdded) return@ui
                                    val currentAdapter = this@GroupFragment.adapter
                                        ?: return@ui
                                    // Never mutate this holder after the background write: it may
                                    // already represent another row after a fast scroll. Rebind the
                                    // actual old/new positions from stable ids instead.
                                    listOf(lastSelected, proxyEntity.id)
                                        .filter { it > 0L }
                                        .distinct()
                                        .forEach { profileId ->
                                            currentAdapter.configurationIdList
                                                .indexOf(profileId)
                                                .takeIf { it >= 0 }
                                                ?.let(currentAdapter::notifyItemChanged)
                                        }
                                    if (update) pf.clearConnectionError()
                                    pf.updateConnectionProfile(proxyEntity)
                                }
                            }

                            if (update) {
                                ProfileManager.postUpdate(lastSelected)
                                SelectedProfileReloadCoordinator.request(proxyEntity.id)
                            } else if (SagerNet.isTv) {
                                if (DataStore.serviceState.started) {
                                    SagerNet.stopService()
                                } else {
                                    SagerNet.startService()
                                }
                            }
                        }

                    }
                }

                val displayName = proxyEntity.displayName()
                profileName.text = displayName
                profileType.text = proxyEntity.displayType()
                profileType.setTextColor(requireContext().getProtocolColor(proxyEntity.type))
                editButton.contentDescription = getString(R.string.edit_named_node, displayName)
                removeButton.contentDescription = getString(R.string.delete_named_node, displayName)

                var address = if (showNodeIp) proxyEntity.displayAddress() else ""

                if (!proxyEntity.hasExplicitName) {
                    address = ""
                }

                profileAddress.text = address
                (profileAddress.parent as View).isGone = address.isBlank()

                renderStatus(proxyEntity)

                val isSubscription = (parentFragment as? ConfigurationFragment)
                    ?.adapter
                    ?.groupList
                    ?.any { group ->
                        group.id == proxyEntity.groupId && group.type == GroupType.SUBSCRIPTION
                    } == true
                editButton.setOnClickListener { anchor ->
                    if (!isSubscription) {
                        anchor.context.startActivity(proxyEntity.settingIntent(anchor.context, false))
                        return@setOnClickListener
                    }
                    MaterialAlertDialogBuilder(anchor.context)
                        .setTitle(R.string.subscription_node_managed_title)
                        .setMessage(R.string.subscription_node_managed_summary)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.copy_as_local_node) { _, _ ->
                            runOnDefaultDispatcher {
                                val source = SagerDatabase.proxyDao.getById(proxyEntity.id)
                                    ?: return@runOnDefaultDispatcher
                                val targetGroup = DataStore.selectedGroupForImport()
                                val bean = source.requireBean().clone()
                                val draft = ProxyEntity(groupId = targetGroup).apply { putBean(bean) }
                                onMainDispatcher {
                                    if (isAdded) {
                                        requireContext().startActivity(
                                            draft.settingIntent(requireContext(), false).apply {
                                                putExtra(
                                                    ProfileSettingsActivity.EXTRA_INITIAL_BEAN,
                                                    bean,
                                                )
                                                putExtra(
                                                    ProfileSettingsActivity.EXTRA_INITIAL_GROUP_ID,
                                                    targetGroup,
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        .show()
                }

                removeButton.setOnClickListener {
                    runOnDefaultDispatcher {
                        val fullProfile = SagerDatabase.proxyDao.getById(proxyEntity.id)
                            ?: return@runOnDefaultDispatcher
                        onMainDispatcher {
                            adapter?.let {
                                val index = it.configurationIdList.indexOf(proxyEntity.id)
                                if (index < 0) return@let
                                it.remove(index)
                                undoManager.remove(index to fullProfile)
                            }
                        }
                    }
                }

                editButton.isGone = select
                removeButton.isGone = select || isSubscription

                // All values below are in-memory and cheap. Binding them synchronously prevents
                // a recycled holder from receiving a late selection/share callback for another
                // node after a fast scroll.
                val isSelected = (selectedItem?.id ?: DataStore.selectedProxy) == proxyEntity.id
                renderActionAvailability(proxyEntity)
                selectedView.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
                view.isActivated = isSelected
                ViewCompat.setStateDescription(
                    view,
                    getString(
                        if (isSelected) R.string.node_selected else R.string.node_not_selected
                    ),
                )

            }

            fun renderStatus(proxyEntity: ProxyEntity = entity) {
                val pf = parentFragment as? ConfigurationFragment ?: return
                renderActionAvailability(proxyEntity)
                val serviceStatus = pf.connectionStatus(proxyEntity)
                val testStatus = when {
                    DataStore.runningTest && proxyEntity.status == 0 -> ConnectionStatus(
                        getString(R.string.connection_status_testing),
                        R.color.np_warning,
                    )

                    proxyEntity.status == 1 -> ConnectionStatus(
                        proxyEntity.downloadMbps?.let {
                            getString(
                                R.string.connection_test_available_speed,
                                proxyEntity.ping,
                                it,
                            )
                        } ?: getString(R.string.available, proxyEntity.ping),
                        R.color.np_success,
                    )

                    proxyEntity.status == 2 -> ConnectionStatus(
                        proxyEntity.error?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.unavailable),
                        R.color.np_error,
                        proxyEntity.error,
                    )

                    proxyEntity.status == 3 -> {
                        val error = proxyEntity.error ?: "<?>"
                        val friendly = Protocols.genFriendlyMsg(error)
                        ConnectionStatus(
                            friendly.takeIf { it.isNotBlank() && it != error }
                                ?: getString(R.string.unavailable),
                            R.color.np_error,
                            error,
                        )
                    }

                    else -> null
                }
                val status = serviceStatus ?: testStatus
                profileStatus.text = status?.text.orEmpty()
                profileStatus.setTextColor(
                    requireContext().getColour(status?.colorRes ?: R.color.np_text_secondary)
                )
                if (status?.error != null) {
                    profileStatus.minWidth = dp2px(48)
                    profileStatus.minHeight = dp2px(48)
                    profileStatus.gravity = android.view.Gravity.CENTER
                    profileStatus.contentDescription = getString(
                        R.string.profile_error_details,
                        status.text,
                    )
                    profileStatus.setOnClickListener {
                        alert(status.error).tryToShow()
                    }
                } else {
                    profileStatus.minWidth = 0
                    profileStatus.minHeight = 0
                    profileStatus.gravity = android.view.Gravity.NO_GRAVITY
                    profileStatus.contentDescription = null
                    profileStatus.setOnClickListener(null)
                }
            }

            private fun renderActionAvailability(proxyEntity: ProxyEntity) {
                val active = DataStore.serviceState.started &&
                    DataStore.currentProfile == proxyEntity.id
                editButton.isEnabled = !active
                removeButton.isEnabled = !active
                val alpha = if (active) 0.38f else 1f
                editButton.alpha = alpha
                removeButton.alpha = alpha
            }

        }

    }

}
