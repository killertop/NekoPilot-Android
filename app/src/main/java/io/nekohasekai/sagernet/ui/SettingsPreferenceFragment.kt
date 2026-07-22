package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.MAX_CONNECTION_TEST_CONCURRENCY
import io.nekohasekai.sagernet.MIN_CONNECTION_TEST_CONCURRENCY
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.location.ServerLocationRepository
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.ui.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.launch

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    private lateinit var allowAccess: SwitchPreference
    private lateinit var localAccessInfo: Preference
    private lateinit var backgroundRunProtection: Preference
    private lateinit var useChineseInterface: SwitchPreference
    private lateinit var connectionTestSettings: Preference

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.layoutManager = FixedLinearLayoutManager(listView)
        listView.setPadding(
            listView.paddingLeft,
            dp2px(6),
            listView.paddingRight,
            listView.paddingBottom,
        )
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        addPreferencesFromResource(R.xml.global_preferences)

        allowAccess = findPreference(Key.ALLOW_ACCESS)!!
        localAccessInfo = findPreference("localAccessInfo")!!
        backgroundRunProtection = findPreference("backgroundRunProtection")!!
        useChineseInterface = findPreference("useChineseInterface")!!
        connectionTestSettings = findPreference("connectionTestSettings")!!

        val autoSwitchPreference = findPreference<SwitchPreference>(Key.AUTO_SWITCH)!!
        val showNodeIpPreference = findPreference<SwitchPreference>(Key.SHOW_NODE_IP)!!
        val showServerLocationPreference =
            findPreference<SwitchPreference>(Key.SHOW_SERVER_LOCATION)!!
        autoSwitchPreference.setOnPreferenceChangeListener { _, value ->
            (activity as? MainActivity)?.setAutomaticNodeSwitchingEnabled(value as Boolean)
            true
        }
        showServerLocationPreference.setOnPreferenceChangeListener { _, value ->
            ServerLocationRepository.setEnabled(value as Boolean)
            true
        }
        useChineseInterface.isChecked = isChineseInterfaceActive()
        useChineseInterface.setOnPreferenceChangeListener { _, value ->
            val languageTag = languageTagForChineseInterface(value as Boolean)
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(languageTag),
            )
            true
        }

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
        backgroundRunProtection.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            true
        }
        connectionTestSettings.setOnPreferenceClickListener {
            showConnectionTestSettings()
            true
        }
        findPreference<Preference>("settingsAbout")?.setOnPreferenceClickListener {
            (requireActivity() as MainActivity).displaySecondaryFragment(AboutFragment())
            true
        }
        if (::backgroundRunProtection.isInitialized) {
            updateBackgroundRunProtection()
        }
        if (::useChineseInterface.isInitialized) {
            useChineseInterface.isChecked = isChineseInterfaceActive()
        }
        updateLocalAccessInfo(DataStore.allowAccess)
        updateConnectionTestSummary()
        lifecycleScope.launch {
            runCatching { DataStore.configurationStore.awaitReady() }
                .onFailure(Logs::e)
                .getOrNull() ?: return@launch
            if (!isAdded) return@launch
            autoSwitchPreference.isChecked = DataStore.autoSwitch
            showNodeIpPreference.isChecked = DataStore.showNodeIp
            showServerLocationPreference.isChecked = DataStore.showServerLocation
            if (DataStore.showServerLocation) ServerLocationRepository.scheduleRefresh()
            allowAccess.isChecked = DataStore.allowAccess
            updateLocalAccessInfo(DataStore.allowAccess)
            updateConnectionTestSummary()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::allowAccess.isInitialized) {
            allowAccess.isChecked = DataStore.allowAccess
        }
        if (::localAccessInfo.isInitialized) {
            updateLocalAccessInfo(DataStore.allowAccess)
        }
        if (::backgroundRunProtection.isInitialized) {
            updateBackgroundRunProtection()
        }
        if (::connectionTestSettings.isInitialized) {
            updateConnectionTestSummary()
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

    private fun updateBackgroundRunProtection() {
        val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        backgroundRunProtection.summary = getString(
            if (powerManager.isIgnoringBatteryOptimizations(requireContext().packageName)) {
                R.string.background_run_protection_enabled
            } else {
                R.string.background_run_protection_disabled
            }
        )
    }

    private fun updateConnectionTestSummary() {
        connectionTestSettings.summary = getString(
            R.string.connection_test_settings_summary,
            DataStore.connectionTestURL,
            DataStore.connectionTestConcurrent,
        )
    }

    private fun showConnectionTestSettings() {
        val content = LayoutInflater.from(requireContext())
            .inflate(R.layout.layout_urltest_preference_dialog, null, false)
        val urlLayout = content.findViewById<TextInputLayout>(R.id.input_layout)
        val urlInput = content.findViewById<EditText>(android.R.id.edit)
        val concurrencyLayout = content.findViewById<TextInputLayout>(R.id.concurrent_layout)
        val concurrencyInput = content.findViewById<EditText>(R.id.edit_concurrent)

        urlLayout.hint = getString(R.string.connection_test_url_hint)
        urlInput.setText(DataStore.connectionTestURL)
        urlInput.setSelection(urlInput.text?.length ?: 0)
        concurrencyLayout.hint = getString(R.string.test_concurrency)
        concurrencyLayout.isVisible = true
        concurrencyInput.setText(DataStore.connectionTestConcurrent.toString())
        concurrencyInput.setSelection(concurrencyInput.text?.length ?: 0)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.connection_test_settings)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                urlLayout.error = null
                concurrencyLayout.error = null
                val normalizedUrl = urlInput.text?.toString()?.trim()
                    ?.toHttpUrlOrNull()
                    ?.takeIf {
                        (it.scheme == "http" || it.scheme == "https") &&
                            it.encodedUsername.isEmpty() && it.encodedPassword.isEmpty()
                    }
                    ?.toString()
                val requestedConcurrency = concurrencyInput.text?.toString()?.toIntOrNull()
                val validConcurrency = requestedConcurrency?.takeIf {
                    it in MIN_CONNECTION_TEST_CONCURRENCY..MAX_CONNECTION_TEST_CONCURRENCY
                }
                if (normalizedUrl == null) {
                    urlLayout.error = getString(R.string.connection_test_url_invalid)
                    return@setOnClickListener
                }
                if (validConcurrency == null) {
                    concurrencyLayout.error = getString(R.string.connection_test_concurrency_invalid)
                    return@setOnClickListener
                }

                val changed = normalizedUrl != DataStore.connectionTestURL ||
                    validConcurrency != DataStore.connectionTestConcurrent
                DataStore.connectionTestURL = normalizedUrl
                DataStore.connectionTestConcurrent = validConcurrency
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching { DataStore.configurationStore.flush() }
                        .onSuccess {
                            updateConnectionTestSummary()
                            dialog.dismiss()
                            if (changed) needReload()
                        }
                        .onFailure { error ->
                            Logs.e(error)
                            urlLayout.error = error.localizedMessage
                            dialog.getButton(
                                androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE,
                            ).isEnabled = true
                        }
                }
            }
        }
        dialog.show()
    }

    private fun isChineseInterfaceActive(): Boolean {
        val configuration = resources.configuration
        val language = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.locales.get(0)?.language
        } else {
            @Suppress("DEPRECATION")
            configuration.locale?.language
        }
        return isChineseLanguage(language)
    }

    private fun showLocalAccessInfo() {
        viewLifecycleOwner.lifecycleScope.launch {
            val endpoint = runCatching { DataStore.prepareLocalProxyEndpoint() }
                .onFailure(Logs::e)
                .getOrNull() ?: return@launch
            if (!isAdded) return@launch
            val connectionInfo = getString(
                R.string.local_access_info_value,
                endpoint.port,
                endpoint.username,
                endpoint.password,
            )
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.local_access_info)
                .setMessage(connectionInfo)
                .setNeutralButton(R.string.action_copy) { _, _ ->
                    val copied = SagerNet.trySetPrimaryClip(connectionInfo)
                    snackbar(
                        getString(if (copied) R.string.copy_success else R.string.copy_failed),
                    ).show()
                }
                .setPositiveButton(android.R.string.ok, null)
                .show()
            }
    }
}
