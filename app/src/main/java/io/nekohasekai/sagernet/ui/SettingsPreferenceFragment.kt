package io.nekohasekai.sagernet.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.view.isVisible
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.*

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    private lateinit var globalCustomConfig: EditConfigPreference
    private lateinit var allowAccess: SwitchPreference
    private lateinit var localAccessInfo: Preference

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.layoutManager = FixedLinearLayoutManager(listView)
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        addPreferencesFromResource(R.xml.global_preferences)

        val appTheme = findPreference<ColorPickerPreference>(Key.APP_THEME)!!
        appTheme.setOnPreferenceChangeListener { _, newTheme ->
            if (DataStore.serviceState.started) {
                SagerNet.reloadService()
            }
            val theme = Theme.getTheme(newTheme as Int)
            app.setTheme(theme)
            requireActivity().apply {
                setTheme(theme)
                ActivityCompat.recreate(this)
            }
            true
        }

        val nightTheme = findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)!!
        nightTheme.setOnPreferenceChangeListener { _, newTheme ->
            Theme.currentNightMode = (newTheme as String).toInt()
            Theme.applyNightTheme()
            true
        }

        val mixedPort = findPreference<EditTextPreference>(Key.MIXED_PORT)!!
        val mixedProxyCredentials = findPreference<Preference>("mixedProxyCredentials")!!
        val serviceMode = findPreference<Preference>(Key.SERVICE_MODE)!!
        allowAccess = findPreference(Key.ALLOW_ACCESS)!!
        localAccessInfo = findPreference("localAccessInfo")!!

        val showDirectSpeed = findPreference<SwitchPreference>(Key.SHOW_DIRECT_SPEED)!!
        val ipv6Mode = findPreference<Preference>(Key.IPV6_MODE)!!
        val trafficSniffing = findPreference<Preference>(Key.TRAFFIC_SNIFFING)!!

        val bypassLan = findPreference<SwitchPreference>(Key.BYPASS_LAN)!!
        val bypassLanInCore = findPreference<SwitchPreference>(Key.BYPASS_LAN_IN_CORE)!!

        val remoteDns = findPreference<EditTextPreference>(Key.REMOTE_DNS)!!
        val directDns = findPreference<EditTextPreference>(Key.DIRECT_DNS)!!
        val enableDnsRouting = findPreference<SwitchPreference>(Key.ENABLE_DNS_ROUTING)!!
        val enableFakeDns = findPreference<SwitchPreference>(Key.ENABLE_FAKEDNS)!!

        val logLevel = findPreference<SimpleMenuPreference>(Key.LOG_LEVEL)!!
        val mtu = findPreference<MTUPreference>(Key.MTU)!!
        val customMtu = findPreference<Preference>("customMtu")!!
        val logBufferSize = findPreference<Preference>("logBufferSize")!!
        globalCustomConfig = findPreference(Key.GLOBAL_CUSTOM_CONFIG)!!
        globalCustomConfig.useConfigStore(Key.GLOBAL_CUSTOM_CONFIG)

        logLevel.dialogLayoutResource = R.layout.layout_loglevel_help
        logLevel.setOnPreferenceChangeListener { _, _ ->
            needRestart()
            true
        }
        customMtu.setOnPreferenceClickListener {
            mtu.showCustomDialog()
            true
        }
        logBufferSize.setOnPreferenceClickListener {
            requireContext().showNumberInputDialog(
                titleRes = R.string.log_buffer_size,
                hintRes = R.string.log_buffer_size_hint,
                initialValue = DataStore.logBufSize.takeIf { size -> size > 0 } ?: 50,
                validationErrorRes = R.string.log_buffer_value_invalid,
                min = 1,
            ) { size ->
                DataStore.logBufSize = size
                needRestart()
            }
            true
        }

        mixedPort.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        mixedProxyCredentials.setOnPreferenceClickListener {
            val credentials = getString(
                R.string.mixed_proxy_credentials_value,
                DataStore.mixedProxyUsername,
                DataStore.mixedProxyPassword,
            )
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.mixed_proxy_credentials)
                .setMessage(credentials)
                .setNeutralButton(R.string.action_copy) { _, _ ->
                    val copied = SagerNet.trySetPrimaryClip(credentials)
                    snackbar(getString(if (copied) R.string.copy_success else R.string.copy_failed)).show()
                }
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }

        val meteredNetwork = findPreference<Preference>(Key.METERED_NETWORK)!!
        if (Build.VERSION.SDK_INT < 28) {
            meteredNetwork.remove()
        }

        val profileTrafficStatistics =
            findPreference<SwitchPreference>(Key.PROFILE_TRAFFIC_STATISTICS)!!
        val speedInterval = findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)!!
        profileTrafficStatistics.isEnabled = speedInterval.value.toString() != "0"
        speedInterval.setOnPreferenceChangeListener { _, newValue ->
            profileTrafficStatistics.isEnabled = newValue.toString() != "0"
            needReload()
            true
        }

        serviceMode.setOnPreferenceChangeListener { _, _ ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            true
        }

        val tunImplementation = findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)!!
        val resolveDestination = findPreference<SwitchPreference>(Key.RESOLVE_DESTINATION)!!
        val acquireWakeLock = findPreference<SwitchPreference>(Key.ACQUIRE_WAKE_LOCK)!!
        mixedPort.onPreferenceChangeListener = reloadListener
        showDirectSpeed.onPreferenceChangeListener = reloadListener
        trafficSniffing.onPreferenceChangeListener = reloadListener
        bypassLan.onPreferenceChangeListener = reloadListener
        bypassLanInCore.onPreferenceChangeListener = reloadListener
        mtu.onPreferenceChangeListener = reloadListener

        enableFakeDns.onPreferenceChangeListener = reloadListener
        remoteDns.onPreferenceChangeListener = reloadListener
        directDns.onPreferenceChangeListener = reloadListener
        enableDnsRouting.onPreferenceChangeListener = reloadListener

        ipv6Mode.onPreferenceChangeListener = reloadListener
        resolveDestination.onPreferenceChangeListener = reloadListener
        tunImplementation.onPreferenceChangeListener = reloadListener
        acquireWakeLock.onPreferenceChangeListener = reloadListener
        globalCustomConfig.onPreferenceChangeListener = reloadListener

        allowAccess.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                confirmLanAccess()
                false
            } else {
                updateLocalAccessInfo(false)
                needReload()
                true
            }
        }
        localAccessInfo.setOnPreferenceClickListener {
            showLocalAccessInfo()
            true
        }
        findPreference<Preference>("settingsTools")?.setOnPreferenceClickListener {
            (requireActivity() as MainActivity).displaySecondaryFragment(ToolsFragment())
            true
        }
        findPreference<Preference>("settingsLogs")?.setOnPreferenceClickListener {
            (requireActivity() as MainActivity).displaySecondaryFragment(LogcatFragment())
            true
        }
        findPreference<Preference>("settingsAbout")?.setOnPreferenceClickListener {
            (requireActivity() as MainActivity).displaySecondaryFragment(AboutFragment())
            true
        }
        updateLocalAccessInfo(DataStore.allowAccess)
    }

    override fun onResume() {
        super.onResume()
        if (::globalCustomConfig.isInitialized) {
            globalCustomConfig.notifyChanged()
        }
        if (::allowAccess.isInitialized) {
            allowAccess.isChecked = DataStore.allowAccess
        }
        if (::localAccessInfo.isInitialized) {
            updateLocalAccessInfo(DataStore.allowAccess)
        }
    }

    private fun confirmLanAccess() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.allow_access_confirm_title)
            .setMessage(R.string.allow_access_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.enable) { _, _ ->
                DataStore.allowAccess = true
                allowAccess.isChecked = true
                updateLocalAccessInfo(true)
                needReload()
            }
            .show()
    }

    private fun updateLocalAccessInfo(visible: Boolean) {
        localAccessInfo.isVisible = visible
        if (visible) {
            localAccessInfo.summary = getString(
                R.string.local_access_info_summary,
                DataStore.mixedPort,
            )
        }
    }

    private fun showLocalAccessInfo() {
        val connectionInfo = getString(
            R.string.local_access_info_value,
            DataStore.mixedPort,
            DataStore.mixedProxyUsername,
            DataStore.mixedProxyPassword,
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.local_access_info)
            .setMessage(connectionInfo)
            .setNeutralButton(R.string.action_copy) { _, _ ->
                val copied = SagerNet.trySetPrimaryClip(connectionInfo)
                snackbar(getString(if (copied) R.string.copy_success else R.string.copy_failed)).show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
