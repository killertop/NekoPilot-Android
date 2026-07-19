package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import moe.matsuri.nb4a.utils.Util

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener {

    lateinit var binding: LayoutMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
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

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

    }

    fun toggleService() {
        if (DataStore.serviceState.canStop) {
            SagerNet.stopService()
            return
        }
        // Do not ask for notification or VPN permissions until there is actually a
        // profile to connect. Permission dialogs are a high-friction system surface and
        // presenting them from the empty home screen makes the action look broken.
        if (DataStore.selectedProxy <= 0L) {
            snackbar(R.string.profile_empty).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(POST_NOTIFICATIONS)
        } else {
            connect.launch(null)
        }
    }

    fun testConnection() {
        if (!DataStore.serviceState.connected) return
        runOnDefaultDispatcher {
            try {
                val elapsed = urlTest()
                onMainDispatcher {
                    snackbar(
                        getString(
                            if (DataStore.connectionTestURL.startsWith("https://")) {
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

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if ((uri.scheme == "sn" && uri.host == "subscription") || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(): Int {
        val service = connection.service
        if (!DataStore.serviceState.connected || service == null) {
            error("not started")
        }
        return service.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            val parsedUrl = Uri.parse(url)
            if ((parsedUrl.scheme != "https" && parsedUrl.scheme != "http") ||
                parsedUrl.host.isNullOrBlank()
            ) {
                onMainDispatcher {
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
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserializeStrict(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val safeName = group.name
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotBlank() && it.length <= 80 && !it.contains("://") }
        val name = safeName
            ?: group.subscription?.link?.let { Uri.parse(it).host }
            ?: getString(R.string.subscription_unknown_name)

        group.name = name

        onMainDispatcher {

            displayFragmentWithId(R.id.nav_home)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.action_import_confirm) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        // Make the newly imported subscription the visible group immediately.  This is
        // especially important on a fresh install where the home page only contains the
        // empty-state card and otherwise keeps showing the old empty group.
        DataStore.selectedGroup = subscription.id
        GroupUpdater.startUpdate(subscription, true)
        onMainDispatcher {
            snackbar(R.string.subscription_import_started).show()
        }
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
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
    }

    @SuppressLint("CommitTransaction")
    private fun displayHome() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, ConfigurationFragment())
            .commitAllowingStateLoss()
    }

    @SuppressLint("CommitTransaction")
    fun displaySecondaryFragment(fragment: ToolbarFragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
        setBottomNavigationVisible(false)
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
        binding.bottomNavigation.isVisible = visible
        binding.fragmentHolder.updatePadding(
            bottom = if (visible) (80 * resources.displayMetrics.density).toInt() else 0,
        )
    }

    private fun applyBottomNavigationLabels() {
        binding.bottomNavigation.labelVisibilityMode = if (DataStore.showBottomBar) {
            NavigationBarView.LABEL_VISIBILITY_LABELED
        } else {
            NavigationBarView.LABEL_VISIBILITY_UNLABELED
        }
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
        animate: Boolean = false,
    ) {
        DataStore.serviceState = state

        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)
            ?.renderConnectionState(state, msg)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG)
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // A foreground VPN still works if notifications are denied; Android keeps the
            // service visible in its active-apps surface. Continue the action the user chose.
            connect.launch(null)
        }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SHOW_BOTTOM_BAR -> applyBottomNavigationLabels()
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
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
