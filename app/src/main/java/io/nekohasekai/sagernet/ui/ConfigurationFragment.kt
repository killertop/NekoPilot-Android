package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.core.widget.doAfterTextChanged
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
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.CONNECTION_TEST_URL
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.TestInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.indexOfGroupOrFirst
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false, val selectedItem: ProxyEntity? = null, val titleRes: Int = 0
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener,
    OnPreferenceDataStoreChangeListener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    lateinit var adapter: GroupPagerAdapter
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2
    lateinit var emptyState: View

    private var connectionFab: View? = null
    private var connectionToggle: MaterialButton? = null
    private var connectionProgress: CircularProgressIndicator? = null
    private var hasSelectedProfile = false
    private var connectionErrorMessage: String? = null
    private val emptyStateRevision = AtomicInteger()

    private fun currentVisibleGroup(): ProxyGroup? =
        adapter.groupList.getOrNull(groupPager.currentItem)
            ?: adapter.groupList.firstOrNull { it.id == DataStore.selectedGroup }

    fun getCurrentGroupFragment(): GroupFragment? {
        return try {
            childFragmentManager.findFragmentByTag("f" + DataStore.selectedGroup) as GroupFragment?
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (adapter.groupList.size > position) {
                DataStore.selectedGroup = adapter.groupList[position].id
            }
        }
    }

    @SuppressLint("DetachAndAttachSameFragment")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(false)
                .detach(this)
                .attach(this)
                .commit()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!select) {
            toolbar.setTitle(R.string.menu_home)
            toolbar.inflateMenu(R.menu.add_profile_menu)
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
        adapter = GroupPagerAdapter()
        ProfileManager.addListener(adapter)
        GroupManager.addListener(adapter)

        groupPager.adapter = adapter
        groupPager.offscreenPageLimit = 1

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
        val revision = emptyStateRevision.incrementAndGet()
        runOnDefaultDispatcher {
            val isEmpty = SagerDatabase.proxyDao.countAll() == 0L
            emptyState.post {
                if (revision == emptyStateRevision.get()) {
                    setEmptyStateVisible(isEmpty)
                }
            }
        }
    }

    fun renderConnectionState(state: BaseService.State, message: String? = null) {
        if (select || connectionToggle == null) return
        if (message != null) {
            connectionErrorMessage = message
        } else if (state != BaseService.State.Stopped) {
            connectionErrorMessage = null
        }
        val connected = state == BaseService.State.Connected
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
                        else -> R.color.np_blue_soft
                    }
                )
            )
            iconTint = ColorStateList.valueOf(
                requireContext().getColour(
                    if (state == BaseService.State.Connected || busy) {
                        R.color.white
                    } else {
                        R.color.np_text_secondary
                    }
                )
            )
            isEnabled = state.canStop || canStart
            alpha = if (isEnabled) 1f else 0.62f
            contentDescription = getString(
                if (connected) R.string.disconnect else R.string.connect
            )
        }
        refreshVisibleConnectionStatuses()
    }

    private fun refreshVisibleConnectionStatuses() {
        adapter.groupFragments.values.forEach { groupFragment ->
            groupFragment.refreshVisibleConnectionStatuses()
        }
    }

    private fun connectionStatus(profileId: Long): ConnectionStatus? {
        if (select || profileId != DataStore.selectedProxy) return null
        return when (DataStore.serviceState) {
            BaseService.State.Connecting -> ConnectionStatus(
                getString(R.string.connection_status_connecting),
                R.color.np_warning,
            )

            BaseService.State.Connected -> ConnectionStatus(
                getString(R.string.connection_status_connected),
                R.color.np_success,
            )

            BaseService.State.Stopping -> ConnectionStatus(
                getString(R.string.connection_status_stopping),
                R.color.np_warning,
            )

            BaseService.State.Stopped, BaseService.State.Idle -> connectionErrorMessage?.let {
                val friendly = Protocols.genFriendlyMsg(it).takeIf { message ->
                    message.isNotBlank() && message != it
                } ?: it
                ConnectionStatus(
                    getString(R.string.connection_status_failed, friendly),
                    R.color.material_red_500,
                    it,
                )
            } ?: ConnectionStatus(
                getString(R.string.connection_status_disconnected),
                R.color.np_disconnected,
            )
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
        connectionErrorMessage = null
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
            // editingGroup
            if (key == Key.PROFILE_GROUP) {
                val targetId = DataStore.editingGroup
                if (targetId > 0 && targetId != DataStore.selectedGroup) {
                    DataStore.selectedGroup = targetId
                    val targetIndex = adapter.groupList.indexOfFirst { it.id == targetId }
                    if (targetIndex >= 0) {
                        groupPager.setCurrentItem(targetIndex, false)
                    } else {
                        adapter.reload()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)

        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }

        super.onDestroy()
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        val fragment = getCurrentGroupFragment()
        fragment?.configurationListView?.apply {
            if (!hasFocus()) requestFocus()
        }
        return super.onKeyDown(ketCode, event)
    }

    suspend fun import(proxies: List<AbstractBean>) {
        val targetId = DataStore.selectedGroupForImport()
        for (proxy in proxies) {
            ProfileManager.createProfile(targetId, proxy)
        }
        onMainDispatcher {
            DataStore.editingGroup = targetId
            snackbar(
                requireContext().resources.getQuantityString(
                    R.plurals.added, proxies.size, proxies.size
                )
            ).show()
        }

    }

    private fun showSubscriptionImportDialog() {
        val content = layoutInflater.inflate(R.layout.layout_subscription_import, null)
        val inputLayout = content.findViewById<TextInputLayout>(R.id.subscription_link_layout)
        val input = content.findViewById<TextInputEditText>(R.id.subscription_link_input)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_subscription_link)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_import_confirm, null)
            .show()
        val importButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
        fun submit() {
            val raw = input.text?.toString()?.trim().orEmpty()
            if (raw.isBlank()) {
                inputLayout.error = getString(R.string.subscription_link_empty)
                return
            }
            val parsed = raw.toUri()
            val subscriptionUri = if (parsed.scheme == "sn" && parsed.host == "subscription" ||
                parsed.scheme == "clash"
            ) {
                parsed
            } else if ((parsed.scheme == "https" || parsed.scheme == "http") &&
                !parsed.host.isNullOrBlank()
            ) {
                Uri.Builder()
                    .scheme("sn")
                    .authority("subscription")
                    .appendQueryParameter("url", raw)
                    .build()
            } else {
                inputLayout.error = getString(R.string.subscription_link_invalid)
                return
            }
            inputLayout.error = null
            dialog.dismiss()
            val activity = requireActivity() as MainActivity
            runOnDefaultDispatcher {
                activity.importSubscription(subscriptionUri)
            }
        }
        importButton.isEnabled = false
        importButton.setOnClickListener { submit() }
        input.doAfterTextChanged {
            inputLayout.error = null
            importButton.isEnabled = !it.isNullOrBlank()
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && importButton.isEnabled) {
                submit()
                true
            } else {
                false
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_manage_groups -> {
                (requireActivity() as MainActivity).displaySecondaryFragment(
                    io.nekohasekai.sagernet.ui.GroupFragment()
                )
            }

            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
            }

            R.id.action_import_clipboard -> {
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else runOnDefaultDispatcher {
                    try {
                        val proxies = RawUpdater.parseRaw(text)
                        if (proxies.isNullOrEmpty()) onMainDispatcher {
                            snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                        } else import(proxies)
                    } catch (e: SubscriptionFoundException) {
                        (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                    } catch (e: Exception) {
                        Logs.w(e)

                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }

            R.id.action_import_subscription -> showSubscriptionImportDialog()

            R.id.action_update_subscription -> {
                val group = currentVisibleGroup() ?: return false
                if (group.type != GroupType.SUBSCRIPTION) {
                    snackbar(R.string.group_not_subscription).show()
                    Logs.e("onMenuItemClick: Group(${group.displayName()}) is not subscription")
                } else {
                    runOnLifecycleDispatcher {
                        GroupUpdater.startUpdate(group, true)
                    }
                }
            }

            R.id.action_connection_test_clear_results -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0) {
                            profile.status = 0
                            profile.ping = 0
                            profile.error = null
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                }
            }

            R.id.action_connection_test_delete_unavailable -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0 && profile.status != 1) {
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(R.string.delete_confirm_prompt)
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    adapter.groupFragments[DataStore.selectedGroup]?.adapter
                                        ?.removeProfiles(toClear.mapTo(hashSetOf()) { it.id })
                                    runOnDefaultDispatcher {
                                        ProfileManager.deleteProfilesSilently(toClear)
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_connection_tcp_ping -> {
                pingTest(false)
            }

            R.id.action_connection_url_test -> {
                urlTest()
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

        lateinit var cancel: () -> Unit
        lateinit var minimize: () -> Unit

        val dialogStatus = AtomicInteger(0) // 1: hidden 2: cancelled
        var notification: ConnectionTestNotification? = null

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
            if (dialogStatus.get() != 2) {
                results.add(profile)
            }
            if (profile.status == 1) {
                availableN.incrementAndGet()
            } else {
                unavailableN.incrementAndGet()
            }
            runOnMainDispatcher {
                val context = context ?: return@runOnMainDispatcher
                val progress = finishedN.addAndGet(1)
                val status = dialogStatus.get()
                notification?.updateNotification(
                    progress,
                    proxyN,
                    progress >= proxyN || status == 2
                )
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
                        profileStatusColor = context.getColour(R.color.material_green_500)
                    }

                    2 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }

                    3 -> {
                        val err = profile.error ?: ""
                        val msg = Protocols.genFriendlyMsg(err)
                        profileStatusText = if (msg != err) msg else getString(R.string.unavailable)
                        profileStatusColor = context.getColour(R.color.material_red_500)
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

    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("EXPERIMENTAL_API_USAGE")
    fun pingTest(icmpPing: Boolean) {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        refreshVisibleConnectionStatuses()
        val group = currentVisibleGroup() ?: run {
            DataStore.runningTest = false
            refreshVisibleConnectionStatuses()
            snackbar(R.string.connection_test_no_group).show()
            return
        }
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()

        val mainJob = runOnDefaultDispatcher {
            val profilesList = SagerDatabase.proxyDao.getByGroup(group.id).filter {
                if (icmpPing) {
                    if (it.requireBean().canICMPing()) {
                        return@filter true
                    }
                } else {
                    if (it.requireBean().canTCPing()) {
                        return@filter true
                    }
                }
                return@filter false
            }
            test.setTotal(profilesList.size)
            val profiles = ConcurrentLinkedQueue(profilesList)
            repeat(DataStore.connectionTestConcurrent.coerceIn(1, 3)) {
                testJobs.add(launch(Dispatchers.IO) {
                    while (isActive) {
                        val profile = profiles.poll() ?: break

                        profile.status = 0
                        var address = profile.requireBean().serverAddress
                        if (!address.isIpAddress()) {
                            try {
                                SagerNet.underlyingNetwork!!.getAllByName(address).apply {
                                    if (isNotEmpty()) {
                                        address = this[0].hostAddress
                                    }
                                }
                            } catch (ignored: UnknownHostException) {
                            }
                        }
                        if (!isActive) break
                        if (!address.isIpAddress()) {
                            profile.status = 2
                            profile.error = app.getString(R.string.connection_test_domain_not_found)
                            test.update(profile)
                            continue
                        }
                        try {
                            if (icmpPing) {
                                // removed
                            } else {
                                val socket =
                                    SagerNet.underlyingNetwork?.socketFactory?.createSocket()
                                        ?: Socket()
                                try {
                                    socket.soTimeout = 3000
                                    socket.bind(InetSocketAddress(0))
                                    val start = SystemClock.elapsedRealtime()
                                    socket.connect(
                                        InetSocketAddress(
                                            address, profile.requireBean().serverPort
                                        ), 3000
                                    )
                                    if (!isActive) break
                                    profile.status = 1
                                    profile.ping = (SystemClock.elapsedRealtime() - start).toInt()
                                    test.update(profile)
                                } finally {
                                    runCatching { socket.close() }
                                }
                            }
                        } catch (e: Exception) {
                            if (!isActive) break
                            val message = e.readableMessage

                            if (icmpPing) {
                                profile.status = 2
                                profile.error = getString(R.string.connection_test_unreachable)
                            } else {
                                profile.status = 2
                                when {
                                    !message.contains("failed:") -> profile.error =
                                        getString(R.string.connection_test_timeout)

                                    else -> when {
                                        message.contains("ECONNREFUSED") -> {
                                            profile.error =
                                                getString(R.string.connection_test_refused)
                                        }

                                        message.contains("ENETUNREACH") -> {
                                            profile.error =
                                                getString(R.string.connection_test_unreachable)
                                        }

                                        else -> {
                                            profile.status = 3
                                            profile.error = message
                                        }
                                    }
                                }
                            }
                            test.update(profile)
                        }
                    }
                })
            }

            testJobs.joinAll()

            runOnMainDispatcher {
                test.cancel()
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                try {
                    ProfileManager.updateProfile(test.results.toList())
                } catch (e: Exception) {
                    Logs.w(e)
                }
                GroupManager.postReload(DataStore.currentGroupId())
                DataStore.runningTest = false
                onMainDispatcher { refreshVisibleConnectionStatuses() }
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[${group.displayName()}] ${getString(R.string.connection_test)}"
            )
            dialog.hide()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun urlTest() {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        refreshVisibleConnectionStatuses()
        val group = currentVisibleGroup() ?: run {
            DataStore.runningTest = false
            refreshVisibleConnectionStatuses()
            snackbar(R.string.connection_test_no_group).show()
            return
        }
        val test = TestDialog()
        val dialog = test.builder.show()

        val mainJob = runOnDefaultDispatcher {
            val profilesList = SagerDatabase.proxyDao.getByGroup(group.id)
            test.setTotal(profilesList.size)
            if (profilesList.isEmpty()) {
                runOnMainDispatcher {
                    test.cancel()
                }
                return@runOnDefaultDispatcher
            }

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
                                test.update(profile)
                            },
                            onError = { profile, error ->
                                profile.status = if (error is PluginManager.PluginNotFoundException) 2 else 3
                                profile.error = error.readableMessage
                                test.update(profile)
                            },
                        )
                    }
                }.joinAll()
            }

            runOnMainDispatcher {
                test.cancel()
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                try {
                    ProfileManager.updateProfile(test.results.toList())
                } catch (e: Exception) {
                    Logs.w(e)
                }
                GroupManager.postReload(DataStore.currentGroupId())
                DataStore.runningTest = false
                onMainDispatcher { refreshVisibleConnectionStatuses() }
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[${group.displayName()}] ${getString(R.string.connection_test)}"
            )
            dialog.hide()
        }
    }

    inner class GroupPagerAdapter : FragmentStateAdapter(this),
        ProfileManager.Listener,
        GroupManager.Listener {

        var selectedGroupIndex = 0
        var groupList: ArrayList<ProxyGroup> = ArrayList()
        var groupFragments: HashMap<Long, GroupFragment> = HashMap()

        fun reload(now: Boolean = false) {

            if (!select) {
                groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            }

            runOnDefaultDispatcher {
                var newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                if (newGroupList.isEmpty()) {
                    SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                    newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                }
                newGroupList.find { it.ungrouped }?.takeIf { newGroupList.size > 1 }?.let {
                    if (SagerDatabase.proxyDao.countByGroup(it.id) == 0L) {
                        newGroupList.remove(it)
                    }
                }

                val requestedGroup = selectedItem?.groupId ?: DataStore.currentGroupId()
                selectedGroupIndex = newGroupList.indexOfGroupOrFirst(requestedGroup)
                val set = selectedGroupIndex >= 0
                if (set) {
                    val validGroup = newGroupList[selectedGroupIndex].id
                    if (DataStore.selectedGroup != validGroup) DataStore.selectedGroup = validGroup
                }

                val runFunc = if (now) activity?.let { it::runOnUiThread } else groupPager::post
                if (runFunc != null) {
                    runFunc {
                        groupList = newGroupList
                        val liveGroupIds = newGroupList.mapTo(hashSetOf()) { it.id }
                        groupFragments.keys.retainAll(liveGroupIds)
                        notifyDataSetChanged()
                        if (set) groupPager.setCurrentItem(selectedGroupIndex, false)
                        val hideTab = groupList.size < 2
                        tabLayout.isGone = hideTab
                        toolbar.elevation = if (hideTab) 0F else dp2px(4).toFloat()
                        // The initial group reload and a subscription update can finish in
                        // either order. Re-query after applying the group snapshot so a stale
                        // "empty" result can never cover newly inserted profile cards.
                        refreshEmptyState()
                        if (!select) {
                            groupPager.registerOnPageChangeCallback(updateSelectedCallback)
                        }
                    }
                }
            }
        }

        init {
            reload(true)
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun createFragment(position: Int): Fragment {
            return GroupFragment.newInstance(groupList[position]).apply {
                groupFragments[groupList[position].id] = this
                if (position == selectedGroupIndex) {
                    selected = true
                }
            }
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        override fun containsItem(itemId: Long): Boolean {
            return groupList.any { it.id == itemId }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            tabLayout.post {
                groupList.add(group)

                if (groupList.any { !it.ungrouped }) tabLayout.post {
                    tabLayout.visibility = View.VISIBLE
                }

                notifyItemInserted(groupList.size - 1)
                tabLayout.getTabAt(groupList.size - 1)?.select()
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return

            tabLayout.post {
                groupList.removeAt(index)
                groupFragments.remove(groupId)
                notifyItemRemoved(index)
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            // Subscription updates replace profiles in one transaction and only emit a
            // group update. Refresh the global empty state here as no per-profile add
            // callback is guaranteed to arrive for that path.
            refreshEmptyState()
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) return

            tabLayout.post {
                tabLayout.getTabAt(index)?.text = group.displayName()
            }
        }

        override suspend fun groupUpdated(groupId: Long) {
            refreshEmptyState()
        }

        override suspend fun onAdd(profile: ProxyEntity) {
            // A reload can start before the profile transaction commits. Invalidate
            // that older empty-state result so it cannot cover the newly added card.
            emptyStateRevision.incrementAndGet()
            emptyState.post { setEmptyStateVisible(false) }
            if (!select && profile.id == DataStore.selectedProxy) {
                connectionToggle?.post { updateConnectionProfile(profile) }
            }
            if (groupList.find { it.id == profile.groupId } == null) {
                DataStore.selectedGroup = profile.groupId
                reload()
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
            val group = groupList.find { it.id == groupId } ?: return
            if (group.ungrouped && SagerDatabase.proxyDao.countByGroup(groupId) == 0L) {
                reload()
            }
        }
    }

    class GroupFragment : Fragment() {

        companion object {
            private const val ARG_PROXY_GROUP = "proxyGroup"

            fun newInstance(proxyGroup: ProxyGroup) = GroupFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_PROXY_GROUP, proxyGroup)
                }
            }
        }

        lateinit var proxyGroup: ProxyGroup
        var selected = false

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            BundleCompat.getParcelable(
                savedInstanceState ?: arguments ?: Bundle(),
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

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)

            if (::proxyGroup.isInitialized) {
                outState.putParcelable("proxyGroup", proxyGroup)
            }
        }

        override fun onViewStateRestored(savedInstanceState: Bundle?) {
            super.onViewStateRestored(savedInstanceState)

            savedInstanceState?.let {
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

            if (::configurationListView.isInitialized && configurationListView.size == 0) {
                configurationListView.adapter = adapter
                runOnDefaultDispatcher {
                    adapter?.reloadProfiles()
                }
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

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            if (!::proxyGroup.isInitialized) return

            configurationListView = view.findViewById(R.id.configuration_list)
            layoutManager = FixedLinearLayoutManager(configurationListView)
            configurationListView.layoutManager = layoutManager
            adapter = ConfigurationAdapter()
            ProfileManager.addListener(adapter!!)
            GroupManager.addListener(adapter!!)
            configurationListView.adapter = adapter
            configurationListView.setItemViewCacheSize(8)

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
                    ) = if (isEnabled) super.getDragDirs(recyclerView, viewHolder) else 0

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
            adapter?.let {
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
            private val searchIndex = HashMap<Long, String>()
            private var allProfileIds: List<Long> = emptyList()

            private fun searchText(profile: ProxyEntity) = buildString {
                append(profile.displayName().lowercase())
                append('\n')
                append(profile.displayType().lowercase())
                append('\n')
                append(profile.displayAddress().lowercase())
            }

            private fun replaceVisibleIds(newIds: List<Long>) {
                val oldIds = configurationIdList.toList()
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

            fun filter(name: String) {
                if (name.isEmpty()) {
                    replaceVisibleIds(allProfileIds)
                    return
                }
                val lower = name.lowercase()
                replaceVisibleIds(allProfileIds.filter { id ->
                    val text = searchIndex[id] ?: configurationList[id]?.let(::searchText)
                        ?.also { searchIndex[id] = it }
                    text?.contains(lower) == true
                })
            }

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

            fun commitMove() = runOnDefaultDispatcher {
                SagerDatabase.proxyDao.updateProxy(updated.toList())
                updated.clear()
            }

            fun remove(pos: Int) {
                if (pos < 0) return
                configurationIdList.removeAt(pos)
                notifyItemRemoved(pos)
            }

            fun removeProfiles(profileIds: Set<Long>) {
                if (profileIds.isEmpty()) return
                profileIds.forEach {
                    configurationList.remove(it)
                    searchIndex.remove(it)
                }
                allProfileIds = allProfileIds.filterNot(profileIds::contains)
                replaceVisibleIds(configurationIdList.filterNot(profileIds::contains))
            }

            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
                for ((index, item) in actions) {
                    configurationListView.post {
                        configurationList[item.id] = item
                        searchIndex.remove(item.id)
                        allProfileIds = allProfileIds.toMutableList().apply { add(index, item.id) }
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
                if (profile.groupId != proxyGroup.id) return

                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val pos = itemCount
                    configurationList[profile.id] = profile
                    searchIndex.remove(profile.id)
                    allProfileIds = allProfileIds + profile.id
                    configurationIdList.add(profile.id)
                    notifyItemInserted(pos)
                }
            }

            override suspend fun onUpdated(profile: ProxyEntity) {
                if (profile.groupId != proxyGroup.id) return
                val index = configurationIdList.indexOf(profile.id)
                if (index < 0) return
                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    configurationList[profile.id] = profile
                    searchIndex.remove(profile.id)
                    notifyItemChanged(index)
                }
            }

            override suspend fun onRemoved(groupId: Long, profileId: Long) {
                if (groupId != proxyGroup.id) return
                val index = configurationIdList.indexOf(profileId)
                if (index < 0) return

                configurationListView.post {
                    configurationIdList.removeAt(index)
                    configurationList.remove(profileId)
                    searchIndex.remove(profileId)
                    allProfileIds = allProfileIds - profileId
                    notifyItemRemoved(index)
                }
            }

            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit

            override suspend fun groupUpdated(group: ProxyGroup) {
                if (group.id != proxyGroup.id) return
                proxyGroup = group
                reloadProfiles()
            }

            override suspend fun groupUpdated(groupId: Long) {
                if (groupId != proxyGroup.id) return
                proxyGroup = SagerDatabase.groupDao.getById(groupId)!!
                reloadProfiles()
            }

            fun reloadProfiles() {
                val newProfiles = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
                    .sortedWith(
                        compareBy<ProxyEntity> { if (it.status == 1) it.ping else Int.MAX_VALUE }
                            .thenBy(ProxyEntity::userOrder)
                    )

                configurationList.clear()
                configurationList.putAll(newProfiles.associateBy { it.id })
                searchIndex.clear()
                val newProfileIds = newProfiles.map { it.id }
                allProfileIds = newProfileIds

                var selectedProfileIndex = -1

                if (selected) {
                    val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                    selectedProfileIndex = newProfileIds.indexOf(selectedProxy)
                }

                configurationListView.post {
                    replaceVisibleIds(newProfileIds)

                    if (selectedProfileIndex != -1) {
                        configurationListView.scrollTo(selectedProfileIndex, true)
                    } else if (newProfiles.isNotEmpty()) {
                        configurationListView.scrollTo(0, true)
                    }

                }
            }

        }

        val profileAccess = Mutex()
        val reloadAccess = Mutex()

        inner class ConfigurationHolder(val view: View) : RecyclerView.ViewHolder(view),
            PopupMenu.OnMenuItemClickListener {

            lateinit var entity: ProxyEntity

            val profileName: TextView = view.findViewById(R.id.profile_name)
            val profileType: TextView = view.findViewById(R.id.profile_type)
            val profileAddress: TextView = view.findViewById(R.id.profile_address)
            val profileStatus: TextView = view.findViewById(R.id.profile_status)

            val selectedView: LinearLayout = view.findViewById(R.id.selected_view)
            val editButton: ImageView = view.findViewById(R.id.edit)
            val shareLayout: LinearLayout = view.findViewById(R.id.share)
            val shareLayer: LinearLayout = view.findViewById(R.id.share_layer)
            val shareButton: ImageView = view.findViewById(R.id.shareIcon)
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
                            onMainDispatcher {
                                selectedView.visibility = View.VISIBLE
                                if (update) pf.clearConnectionError()
                                renderStatus()
                                pf.updateConnectionProfile(proxyEntity)
                            }
                            }

                            if (update) {
                                ProfileManager.postUpdate(lastSelected)
                                if (DataStore.serviceState.canStop && reloadAccess.tryLock()) {
                                    SagerNet.reloadService()
                                    reloadAccess.unlock()
                                }
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

                profileName.text = proxyEntity.displayName()
                profileType.text = proxyEntity.displayType()
                profileType.setTextColor(requireContext().getProtocolColor(proxyEntity.type))

                var address = proxyEntity.displayAddress()

                if (proxyEntity.requireBean().name.isBlank()) {
                    address = ""
                }

                profileAddress.text = address
                (profileAddress.parent as View).isGone = address.isBlank()

                renderStatus(proxyEntity)

                editButton.setOnClickListener {
                    it.context.startActivity(
                        proxyEntity.settingIntent(
                            it.context, proxyGroup.type == GroupType.SUBSCRIPTION
                        )
                    )
                }

                removeButton.setOnClickListener {
                    adapter?.let {
                        val index = it.configurationIdList.indexOf(proxyEntity.id)
                        it.remove(index)
                        undoManager.remove(index to proxyEntity)
                    }
                }

                val selectOrChain = select || proxyEntity.type == ProxyEntity.TYPE_CHAIN
                shareLayout.isGone = selectOrChain
                editButton.isGone = select
                removeButton.isGone = select

                proxyEntity.nekoBean?.apply {
                    shareLayout.isGone = true
                }

                runOnDefaultDispatcher {
                    val selected = (selectedItem?.id ?: DataStore.selectedProxy) == proxyEntity.id
                    val started =
                        selected && DataStore.serviceState.started && DataStore.currentProfile == proxyEntity.id
                    onMainDispatcher {
                        editButton.isEnabled = !started
                        removeButton.isEnabled = !started
                        selectedView.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                    }

                    fun showShare(anchor: View) {
                        val popup = PopupMenu(
                            android.view.ContextThemeWrapper(
                                requireContext(), R.style.ThemeOverlay_NekoPilot_PopupMenu
                            ), anchor
                        )
                        popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

                        when {
                            !proxyEntity.haveStandardLink() -> {
                                popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
                                popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                                    R.id.action_standard_clipboard
                                )
                            }

                            !proxyEntity.haveLink() -> {
                                popup.menu.removeItem(R.id.action_group_qr)
                                popup.menu.removeItem(R.id.action_group_clipboard)
                            }
                        }

                        if (proxyEntity.nekoBean != null) {
                            popup.menu.removeItem(R.id.action_group_configuration)
                        }

                        popup.setOnMenuItemClickListener(this@ConfigurationHolder)
                        popup.show()
                    }

                    if (!(select || proxyEntity.type == ProxyEntity.TYPE_CHAIN)) {
                        onMainDispatcher {
                            shareLayer.setBackgroundColor(Color.TRANSPARENT)
                            shareButton.setImageResource(R.drawable.ic_social_share)
                            shareButton.setColorFilter(Color.GRAY)
                            shareButton.isVisible = true

                            shareLayout.setOnClickListener {
                                showShare(it)
                            }
                        }
                    }
                }

            }

            fun renderStatus(proxyEntity: ProxyEntity = entity) {
                val pf = parentFragment as? ConfigurationFragment ?: return
                val serviceStatus = pf.connectionStatus(proxyEntity.id)
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
                        R.color.material_green_500,
                    )

                    proxyEntity.status == 2 -> ConnectionStatus(
                        proxyEntity.error?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.unavailable),
                        R.color.material_red_500,
                        proxyEntity.error,
                    )

                    proxyEntity.status == 3 -> {
                        val error = proxyEntity.error ?: "<?>"
                        val friendly = Protocols.genFriendlyMsg(error)
                        ConnectionStatus(
                            friendly.takeIf { it.isNotBlank() && it != error }
                                ?: getString(R.string.unavailable),
                            R.color.material_red_500,
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

            var currentName = ""
            fun showCode(link: String) {
                QRCodeDialog(link, currentName).showAllowingStateLoss(parentFragmentManager)
            }

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                (activity as MainActivity).snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            override fun onMenuItemClick(item: MenuItem): Boolean {
                try {
                    currentName = entity.displayName()!!
                    when (item.itemId) {
                        R.id.action_standard_qr -> showCode(entity.toStdLink())
                        R.id.action_standard_clipboard -> export(entity.toStdLink())
                        R.id.action_universal_qr -> showCode(entity.requireBean().toUniversalLink())
                        R.id.action_universal_clipboard -> export(
                            entity.requireBean().toUniversalLink()
                        )

                        R.id.action_config_export_clipboard -> export(entity.exportConfig().first)
                        R.id.action_config_export_file -> {
                            val cfg = entity.exportConfig()
                            DataStore.serverConfig = cfg.first
                            startFilesForResult(
                                (parentFragment as ConfigurationFragment).exportConfig, cfg.second
                            )
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    (activity as MainActivity).snackbar(e.readableMessage).show()
                    return true
                }
                return true
            }
        }

    }

    private val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(data)!!
                            .bufferedWriter()
                            .use {
                                it.write(DataStore.serverConfig)
                            }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }

                }
            }
        }

}
