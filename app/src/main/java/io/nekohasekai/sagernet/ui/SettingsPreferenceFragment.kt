package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.ui.*

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

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

        allowAccess = findPreference(Key.ALLOW_ACCESS)!!
        localAccessInfo = findPreference("localAccessInfo")!!

        val tunImplementation = findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)!!
        tunImplementation.onPreferenceChangeListener = reloadListener

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
        findPreference<Preference>("settingsAbout")?.setOnPreferenceClickListener {
            (requireActivity() as MainActivity).displaySecondaryFragment(AboutFragment())
            true
        }
        updateLocalAccessInfo(DataStore.allowAccess)
    }

    override fun onResume() {
        super.onResume()
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
