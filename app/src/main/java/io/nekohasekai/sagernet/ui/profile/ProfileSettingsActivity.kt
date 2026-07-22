package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.bg.SelectedProfileReloadCoordinator
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.parcelize.Parcelize
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.neko.NekoBean
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.properties.Delegates

@Suppress("UNCHECKED_CAST")
abstract class ProfileSettingsActivity<T : AbstractBean>(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ThemedActivity(resId), OnPreferenceDataStoreChangeListener {

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    (requireActivity() as ProfileSettingsActivity<*>).saveAndExit()
                }
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class ProfileIdArg(val profileId: Long, val groupId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<ProfileIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_confirm_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    ProfileManager.deleteProfile(arg.groupId, arg.profileId)
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "id"
        const val EXTRA_IS_SUBSCRIPTION = "sub"
        private const val STATE_EDITING_SESSION = "editing_session"
        private const val CACHE_EDITING_SESSION = "profileEditingSession"
    }

    abstract fun createEntity(): T
    abstract fun T.init()
    abstract fun T.serialize()

    val proxyEntity by lazy { SagerDatabase.proxyDao.getById(DataStore.editingId) }
    protected var isSubscription by Delegates.notNull<Boolean>()
    private var editingSession = ""
    private var resetDirtyOnNextView = false
    private val saving = AtomicBoolean(false)
    private var editorReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            if (DataStore.dirty) {
                UnsavedChangesDialogFragment().apply { key() }
                    .show(supportFragmentManager, null)
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.profile_config)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        val editingId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        isSubscription = intent.getBooleanExtra(EXTRA_IS_SUBSCRIPTION, false)
        val restoredSession = savedInstanceState?.getString(STATE_EDITING_SESSION)
        val cachedSession = DataStore.profileCacheStore.getString(CACHE_EDITING_SESSION, null)
        val canReuseCache = restoredSession != null && restoredSession == cachedSession
        editingSession = restoredSession.takeIf { canReuseCache } ?: UUID.randomUUID().toString()

        if (!canReuseCache) {
            DataStore.profileCacheStore.reset()
            DataStore.editingId = editingId
            DataStore.profileCacheStore.putString(CACHE_EDITING_SESSION, editingSession)
            resetDirtyOnNextView = true
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    DataStore.editingGroup = DataStore.selectedGroupForImport()
                    createEntity().applyDefaultValues().init()
                } else {
                    if (proxyEntity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    DataStore.editingGroup = proxyEntity!!.groupId
                    (proxyEntity!!.requireBean() as T).init()
                }
                onMainDispatcher {
                    invalidateOptionsMenu()
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()
                }
            }


        }

    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_EDITING_SESSION, editingSession)
        super.onSaveInstanceState(outState)
    }

    private fun consumeDirtyReset(): Boolean {
        if (!resetDirtyOnNextView) return false
        resetDirtyOnNextView = false
        return true
    }

    private fun validateEndpoint(bean: T): Int? {
        if (bean is ConfigBean || bean is ChainBean || bean is NekoBean) return null
        if (bean.serverAddress.isNullOrBlank()) return R.string.server_address_required
        if (bean is HysteriaBean) {
            if (bean.serverPorts.isNullOrBlank()) return R.string.server_port_invalid
        } else if ((bean.serverPort ?: 0) !in 1..65535) {
            return R.string.server_port_invalid
        }
        return null
    }

    open suspend fun saveAndExit() {
        if (!saving.compareAndSet(false, true)) return
        try {
            val editingId = DataStore.editingId
            val serialized = if (editingId == 0L) {
                createEntity().apply { serialize() }
            } else {
                val entity = proxyEntity ?: run {
                    onMainDispatcher { finish() }
                    return
                }
                (entity.requireBean() as T).apply { serialize() }
            }
            validateEndpoint(serialized)?.let { messageRes ->
                saving.set(false)
                onMainDispatcher { snackbar(messageRes).show() }
                return
            }

            if (editingId == 0L) {
                ProfileManager.createProfile(DataStore.editingGroup, serialized)
            } else {
                val entity = proxyEntity ?: return
                val active = entity.id == DataStore.currentProfile && DataStore.serviceState.started
                entity.putBean(serialized)
                ProfileManager.updateProfile(entity)
                if (active) SelectedProfileReloadCoordinator.request(entity.id, force = true)
            }
            onMainDispatcher { finish() }
        } catch (error: Exception) {
            Logs.w(error)
            saving.set(false)
            onMainDispatcher { snackbar(error.readableMessage).show() }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        menu.findItem(R.id.action_apply)?.isVisible = editorReady
        menu.findItem(R.id.action_delete)?.isVisible = editorReady
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val child = supportFragmentManager.findFragmentById(R.id.settings)
            as? MyPreferenceFragmentCompat
        return child?.onOptionsItemSelected(item)
            ?: if (item.itemId == R.id.action_apply || item.itemId == R.id.action_delete) true
            else super.onOptionsItemSelected(item)
    }

    private fun markEditorReady() {
        if (editorReady) return
        editorReady = true
        invalidateOptionsMenu()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
    }

    abstract fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    )

    open fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
    }

    open fun PreferenceFragmentCompat.displayPreferenceDialog(preference: Preference): Boolean {
        return false
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        var activity: ProfileSettingsActivity<*>? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as ProfileSettingsActivity<*>).apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "Error on createPreferences, please try again.",
                    Toast.LENGTH_SHORT
                ).show()
                Logs.e(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)

            activity?.apply {
                viewCreated(view, savedInstanceState)
                if (consumeDirtyReset()) DataStore.dirty = false
                DataStore.profileCacheStore.registerChangeListener(this)
                markEditorReady()
            }
        }

        @SuppressLint("CheckResult")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    requireActivity().finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(
                            ProfileIdArg(
                                DataStore.editingId, DataStore.editingGroup
                            )
                        )
                        key()
                    }.show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
                true
            }

            else -> false
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            activity?.apply {
                if (displayPreferenceDialog(preference)) return
            }
            super.onDisplayPreferenceDialog(preference)
        }

    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "\u2022".repeat(text.length)
            }
        }

    }

}
