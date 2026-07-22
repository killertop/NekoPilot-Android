package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.CONNECTION_TEST_URL
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.SelectedProfileReloadCoordinator
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.fmt.displayNameForUi
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import moe.matsuri.nb4a.utils.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.IDN
import java.util.concurrent.atomic.AtomicLong

private const val MAX_SUBSCRIPTION_URL_UTF16_UNITS = 8 * 1024

internal fun canonicalSubscriptionUrlKey(raw: String): String? {
    if (raw.length > MAX_SUBSCRIPTION_URL_UTF16_UNITS) return null
    val trimmed = raw.trim()
    val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    val host = uri.host ?: return null
    if (scheme !in setOf("http", "https") || host.isEmpty()) return null
    val canonicalHost = runCatching {
        if (':' in host) host.lowercase() else IDN.toASCII(host.trimEnd('.')).lowercase()
    }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
    val rawPort = uri.port
    val port = when {
        rawPort < 0 -> ""
        rawPort > 65535 -> return null
        scheme == "http" && rawPort == 80 -> ""
        scheme == "https" && rawPort == 443 -> ""
        else -> rawPort.toString()
    }
    val authorityStart = trimmed.indexOf("://") + 3
    val authorityEnd = trimmed.substring(authorityStart).indexOfFirst { it == '/' || it == '?' || it == '#' }
        .let { if (it < 0) trimmed.length else authorityStart + it }
    val userInfo = trimmed.substring(authorityStart, authorityEnd).substringBeforeLast('@', "")
        .takeIf(String::isNotEmpty)?.plus("").orEmpty()
        .let { if (it.isEmpty()) "" else "$it@" }
    val authorityHost = if (':' in canonicalHost) "[$canonicalHost]" else canonicalHost
    val path = uri.encodedPath?.takeIf(String::isNotEmpty) ?: "/"
    val queryPresent = trimmed.substringBefore('#').endsWith("?") || uri.encodedQuery != null
    return buildString {
        append(scheme).append("://").append(userInfo).append(authorityHost)
        if (port.isNotEmpty()) append(':').append(port)
        append(path)
        if (queryPresent) append('?').append(uri.encodedQuery.orEmpty())
    }
}

internal fun sameSubscriptionUrl(first: String, second: String): Boolean {
    if (first.length > MAX_SUBSCRIPTION_URL_UTF16_UNITS || second.length > MAX_SUBSCRIPTION_URL_UTF16_UNITS) {
        return false
    }
    val firstKey = canonicalSubscriptionUrlKey(first)
    val secondKey = canonicalSubscriptionUrlKey(second)
    return if (firstKey != null && secondKey != null) firstKey == secondKey else first.trim() == second.trim()
}

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener {

    lateinit var binding: LayoutMainBinding
    private var viewIntentResolved = false
    private var viewIntentDispatchStarted = false
    private var bottomNavigationVisible = true
    private var pendingImportDialog: Dialog? = null
    private var navigationBarInsetLeft = 0
    private var navigationBarInsetRight = 0
    private var navigationBarInsetBottom = 0
    private val requestInsetsRunnable = Runnable { requestSystemBarInsets() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewIntentResolved = savedInstanceState?.getBoolean(STATE_VIEW_INTENT_RESOLVED) == true

        binding = LayoutMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installMainWindowInsets()
        applyBottomNavigationLabels()
        binding.bottomNavigation.setOnItemSelectedListener { displayFragmentWithId(it.itemId) }
        supportFragmentManager.addOnBackStackChangedListener {
            setBottomNavigationVisible(supportFragmentManager.backStackEntryCount == 0)
        }

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_home)
        } else {
            // FragmentManager restores secondary pages and their back stack before this point.
            // Keep the navigation visibility in sync after rotation/process recreation instead
            // of briefly exposing primary navigation on top of Tools, Logs, or About.
            setBottomNavigationVisible(supportFragmentManager.backStackEntryCount == 0)
        }
        onBackPressedDispatcher.addCallback {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            } else if (supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment) {
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_home)
            }
        }

        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (!viewIntentResolved) consumeViewIntent(intent)

    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_VIEW_INTENT_RESOLVED, viewIntentResolved)
        super.onSaveInstanceState(outState)
    }

    fun toggleService() {
        if (DataStore.serviceState.canStop) {
            SelectedProfileReloadCoordinator.cancel()
            SagerNet.stopService()
            return
        }
        // Only the Android VPN authorization belongs in the connection flow. The foreground
        // service remains visible in Android's active-apps surface without notification access.
        if (DataStore.selectedProxy <= 0L) {
            snackbar(R.string.profile_empty).show()
            return
        }
        connect.launch(null)
    }

    fun testConnection() {
        if (!DataStore.serviceState.connected) return
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val elapsed = urlTest()
                onMainDispatcher {
                    snackbar(
                        getString(
                            if (CONNECTION_TEST_URL.startsWith("https://")) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            }, elapsed
                        )
                    ).show()
                }
            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    snackbar(getString(R.string.connection_test_error, e.readableMessage)).show()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewIntentResolved = false
        viewIntentDispatchStarted = false
        consumeViewIntent(intent)
    }

    private fun consumeViewIntent(source: Intent?) {
        if (source?.action != Intent.ACTION_VIEW) return
        val uri = source.data ?: return
        if (viewIntentDispatchStarted) return

        // MainActivity is singleTask, and Android otherwise retains the VIEW intent
        // across recreation. Keep the request unresolved until the user accepts or
        // dismisses it, so rotation can safely recreate an interrupted confirmation.
        source.putExtra(EXTRA_VIEW_INTENT_DISPATCHED, true)
        setIntent(source)
        viewIntentDispatchStarted = true

        lifecycleScope.launch(Dispatchers.Default) {
            if ((uri.scheme == "sn" && uri.host == "subscription") || uri.scheme == "clash") {
                importSubscription(
                    uri,
                    externalViewIntent = true,
                    destinationTab = source.getIntExtra(
                        EXTRA_IMPORT_DESTINATION_TAB,
                        R.id.nav_home,
                    ),
                )
            } else {
                importProfile(uri, externalViewIntent = true)
            }
        }
    }

    private fun resolveViewIntent(externalViewIntent: Boolean) {
        if (externalViewIntent) viewIntentResolved = true
    }

    fun requestSubscriptionImport(uri: Uri, destinationTab: Int = R.id.nav_home) {
        lifecycleScope.launch(Dispatchers.Default) {
            importSubscription(uri, destinationTab = destinationTab)
        }
    }

    companion object {
        internal const val EXTRA_IMPORT_DESTINATION_TAB = "main.extra_import_destination_tab"
        private const val STATE_VIEW_INTENT_RESOLVED = "main.view_intent_resolved"
        private const val EXTRA_VIEW_INTENT_DISPATCHED = "main.extra_view_intent_dispatched"
        private const val MAX_SUBSCRIPTION_URL_CHARS = 8 * 1024
        private val subscriptionImportMutex = Mutex()
    }

    fun urlTest(): Int {
        val service = connection.service
        if (!DataStore.serviceState.connected || service == null) {
            error("not started")
        }
        return service.urlTest()
    }

    fun selectProfileInRunningService(profileId: Long): Boolean = runCatching {
        DataStore.serviceState.connected && connection.service?.selectProfile(profileId) == true
    }.getOrElse {
        Logs.w("In-place node selection unavailable", it)
        false
    }

    fun setAutomaticNodeSelectionEnabled(enabled: Boolean): Boolean = runCatching {
        connection.service?.setAutomaticNodeSelectionEnabled(enabled) == true
    }.getOrElse {
        Logs.w("Unable to update automatic node selection", it)
        false
    }

    suspend fun importSubscription(
        uri: Uri,
        externalViewIntent: Boolean = false,
        destinationTab: Int = R.id.nav_home,
    ) {
        // Nodes and subscriptions now both return to Home. A subscription is a data source,
        // not a primary navigation destination.
        val resolvedDestinationTab = R.id.nav_home
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            val parsedUrl = Uri.parse(url)
            if ((parsedUrl.scheme != "https" && parsedUrl.scheme != "http") ||
                parsedUrl.host.isNullOrBlank()
            ) {
                onMainDispatcher {
                    resolveViewIntent(externalViewIntent)
                    alert(getString(R.string.subscription_link_invalid)).show()
                }
                return
            }
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: run {
                onMainDispatcher { resolveViewIntent(externalViewIntent) }
                return
            }
            try {
                group = KryoConverters.deserializeStrict(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    resolveViewIntent(externalViewIntent)
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val normalizedGroup = try {
            normalizeImportedSubscription(group)
        } catch (error: Exception) {
            onMainDispatcher {
                resolveViewIntent(externalViewIntent)
                alert(error.readableMessage).show()
            }
            return
        }

        val safeName = normalizedGroup.name
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotBlank() && it.length <= 80 && !it.contains("://") }
        val name = safeName
            ?: normalizedGroup.subscription?.link?.let { Uri.parse(it).host }
            ?: getString(R.string.subscription_unknown_name)

        normalizedGroup.name = name
        val existing = SagerDatabase.groupDao.allGroups().firstOrNull { candidate ->
            candidate.type == GroupType.SUBSCRIPTION &&
                sameSubscriptionUrl(
                    candidate.subscription?.link.orEmpty(),
                    normalizedGroup.subscription!!.link,
                )
        }

        onMainDispatcher {
            if (isFinishing || isDestroyed) return@onMainDispatcher

            displayFragmentWithId(resolvedDestinationTab)

            val dialog = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(
                    if (existing == null) R.string.subscription_import
                    else R.string.subscription_already_exists
                )
                .setMessage(
                    if (existing == null) getString(R.string.subscription_import_message, name)
                    else getString(R.string.subscription_update_existing_message, name)
                )
                .setPositiveButton(
                    if (existing == null) R.string.action_import_confirm
                    else R.string.update_current_subscription
                ) { _, _ ->
                    resolveViewIntent(externalViewIntent)
                    if (existing == null) {
                        runOnIoDispatcher { finishImportSubscription(normalizedGroup) }
                    } else {
                        GroupUpdater.startUpdate(existing, true)
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    resolveViewIntent(externalViewIntent)
                }
                .setOnCancelListener { resolveViewIntent(externalViewIntent) }
                .create()
            showImportDialog(dialog)

        }

    }

    private fun normalizeImportedSubscription(group: ProxyGroup): ProxyGroup {
        require(group.type == GroupType.SUBSCRIPTION && group.subscription != null) {
            getString(R.string.subscription_link_invalid)
        }
        val link = group.subscription!!.link?.trim().orEmpty()
        require(link.length in 1..MAX_SUBSCRIPTION_URL_CHARS) {
            getString(R.string.subscription_link_invalid)
        }
        val parsed = Uri.parse(link)
        val scheme = parsed.scheme?.lowercase()
        require(
            (scheme == "https" || scheme == "http") &&
                !parsed.host.isNullOrBlank()
        ) {
            getString(R.string.subscription_link_invalid)
        }
        group.apply {
            id = 0L
            userOrder = 0L
            ungrouped = false
            type = GroupType.SUBSCRIPTION
            order = GroupOrder.BY_DELAY
            isSelector = false
            frontProxy = -1L
            landingProxy = -1L
            subscription!!.link = link
        }
        return group
    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        val (target, newlyCreated) = subscriptionImportMutex.withLock {
            val existing = SagerDatabase.groupDao.allGroups().firstOrNull { candidate ->
                candidate.type == GroupType.SUBSCRIPTION &&
                    sameSubscriptionUrl(
                        candidate.subscription?.link.orEmpty(),
                        subscription.subscription!!.link,
                    )
            }
            if (existing != null) existing to false
            else GroupManager.createGroup(subscription) to true
        }
        // Make the newly imported subscription the visible group immediately.  This is
        // especially important on a fresh install where the home page only contains the
        // empty-state card and otherwise keeps showing the old empty group.
        DataStore.selectedGroup = target.id
        DataStore.configurationStore.flushBlocking()
        onMainDispatcher {
            if (!isFinishing && !isDestroyed) {
                snackbar(
                    if (newlyCreated) R.string.subscription_import_started
                    else R.string.subscription_already_imported_updating
                ).show()
            }
        }
        // Keep the imported source even when its first refresh fails. A transient network or
        // provider error must not silently undo the user's import; retaining the selected source
        // also makes "Update airport subscription" an immediate retry path. The updater already
        // reports the failure, and a successful refresh selects the first imported profile.
        GroupUpdater.executeUpdate(target, true)
    }

    suspend fun importProfile(uri: Uri, externalViewIntent: Boolean = false) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                resolveViewIntent(externalViewIntent)
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            if (isFinishing || isDestroyed) return@onMainDispatcher
            val dialog = MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayNameForUi()))
                .setPositiveButton(R.string.action_import_confirm) { _, _ ->
                    resolveViewIntent(externalViewIntent)
                    runOnIoDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    resolveViewIntent(externalViewIntent)
                }
                .setOnCancelListener { resolveViewIntent(externalViewIntent) }
                .create()
            showImportDialog(dialog)
        }

    }

    private fun showImportDialog(dialog: Dialog) {
        pendingImportDialog?.dismiss()
        pendingImportDialog = dialog
        dialog.setOnDismissListener {
            if (pendingImportDialog === dialog) pendingImportDialog = null
        }
        dialog.show()
    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            if (isFinishing || isDestroyed) return@onMainDispatcher
            displayFragmentWithId(R.id.nav_home)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        // official exe

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, pluginEntity.displayName
                )
            )
            .setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_learn_more) { _, _ ->
                launchCustomTab("https://matsuridayo.github.io/nb4a-plugin/")
            }
            .show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }
            .show()
    }

    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        requestInsetsAfterFragmentChange()
    }

    @SuppressLint("CommitTransaction")
    private fun displayHome() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, ConfigurationFragment())
            .commitAllowingStateLoss()
        requestInsetsAfterFragmentChange()
    }

    @SuppressLint("CommitTransaction")
    fun displaySecondaryFragment(fragment: ToolbarFragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
        setBottomNavigationVisible(false)
        requestInsetsAfterFragmentChange()
    }

    private fun clearSecondaryBackStack() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate(
                null,
                FragmentManager.POP_BACK_STACK_INCLUSIVE,
            )
        }
    }

    private fun setBottomNavigationVisible(visible: Boolean) {
        bottomNavigationVisible = visible
        binding.bottomNavigation.isVisible = visible
        updateMainContentInsets()
    }

    private fun installMainWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navigationInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            navigationBarInsetLeft = navigationInsets.left
            navigationBarInsetRight = navigationInsets.right
            navigationBarInsetBottom = navigationInsets.bottom

            binding.bottomNavigation.updateLayoutParams<ViewGroup.LayoutParams> {
                height = bottomNavigationContentHeight + navigationInsets.bottom
            }
            updateMainContentInsets()
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.fragmentHolder) { _, insets ->
            // MainActivity owns the navigation-bar inset for fragment content. Consume it
            // only on this branch so BottomNavigationView can still apply its own padding.
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.NONE)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.NONE)
                .build()
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private val bottomNavigationContentHeight: Int
        get() = resources.getDimensionPixelSize(R.dimen.main_bottom_navigation_content_height)

    private fun updateMainContentInsets() {
        binding.fragmentHolder.updatePadding(
            left = navigationBarInsetLeft,
            right = navigationBarInsetRight,
            bottom = if (bottomNavigationVisible) {
                bottomNavigationContentHeight + navigationBarInsetBottom
            } else {
                navigationBarInsetBottom
            },
        )
    }

    private fun requestInsetsAfterFragmentChange() {
        binding.fragmentHolder.post(requestInsetsRunnable)
    }

    private fun applyBottomNavigationLabels() {
        binding.bottomNavigation.labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        clearSecondaryBackStack()
        when (id) {
            R.id.nav_home -> displayHome()
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_settings -> displayFragment(SettingsFragment())
            else -> return false
        }
        setBottomNavigationVisible(true)
        binding.bottomNavigation.menu.findItem(id)?.isChecked = true
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        failedProfileId: Long = 0L,
    ) {
        DataStore.serviceState = state
        if (msg != null) {
            DataStore.lastConnectionError = msg
            DataStore.lastConnectionErrorProfile = failedProfileId
            DataStore.lastConnectionErrorTime = System.currentTimeMillis()
        } else if (state != BaseService.State.Stopped && state != BaseService.State.Idle) {
            DataStore.lastConnectionError = ""
            DataStore.lastConnectionErrorProfile = 0L
            DataStore.lastConnectionErrorTime = 0L
        }

        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)
            ?.renderConnectionState(state)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    private val stateCallbackGeneration = AtomicLong()

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (bottomNavigationVisible && binding.bottomNavigation.isVisible) {
                anchorView = binding.bottomNavigation
            } else if (navigationBarInsetBottom > 0) {
                view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin += navigationBarInsetBottom
                }
            }
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        val generation = stateCallbackGeneration.incrementAndGet()
        if (msg == null) {
            changeState(state)
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                // The service flushes the attempted profile together with the failure before
                // delivering this callback. Refresh so an old attempt cannot be blamed on a
                // newly selected node.
                DataStore.configurationStore.refreshBlocking()
                val failedProfileId = DataStore.lastConnectionErrorProfile
                withContext(Dispatchers.Main.immediate) {
                    // A refresh is slower than Binder delivery. If a newer Connecting/Connected
                    // event arrived meanwhile, never let this old failure move the UI backwards.
                    if (stateCallbackGeneration.get() == generation) {
                        changeState(state, msg, failedProfileId)
                    }
                }
            }
        }
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) {
        stateCallbackGeneration.incrementAndGet()
        changeState(
            try {
                BaseService.State.values()[service.state]
            } catch (_: RemoteException) {
                BaseService.State.Idle
            }
        )
    }

    override fun onServiceDisconnected() {
        stateCallbackGeneration.incrementAndGet()
        changeState(BaseService.State.Idle)
    }
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            if (isFinishing || isDestroyed) return@runOnMainDispatcher
            when (key) {
                Key.PROXY_APPS, Key.INDIVIDUAL -> {
                    if (DataStore.serviceState.canStop) {
                        snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                            SagerNet.reloadService()
                        }.show()
                    }
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        stateCallbackGeneration.incrementAndGet()
        pendingImportDialog?.dismiss()
        pendingImportDialog = null
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (super.onKeyDown(keyCode, event)) return true

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

}
