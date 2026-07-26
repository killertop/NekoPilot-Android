package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.SparseBooleanArray
import android.util.LruCache
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat
import androidx.core.util.contains
import androidx.core.util.set
import androidx.core.view.ViewCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceDataStore
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutAppsBinding
import io.nekohasekai.sagernet.databinding.LayoutAppsItemBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applicationScope
import io.nekohasekai.sagernet.ktx.crossFadeFrom
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.utils.PerAppProxyPolicy
import io.nekohasekai.sagernet.utils.PerAppProxyPolicyDraft
import io.nekohasekai.sagernet.utils.isPerAppSelectableUid
import io.nekohasekai.sagernet.utils.mergeVisiblePerAppSelection
import io.nekohasekai.sagernet.utils.sanitizePerAppPackages
import io.nekohasekai.sagernet.utils.shouldPreparePerAppRecommendations
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class AppManagerActivity : ThemedActivity() {
    companion object {
        private const val SWITCH = "switch"
        private const val INSTALLED_APPS_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"
        private const val STATE_DRAFT_ENABLED = "per_app_policy_draft_enabled"
        private const val STATE_DRAFT_PACKAGES = "per_app_policy_draft_packages"
        private const val STATE_RECOMMENDATION_PENDING = "per_app_recommendation_pending"

        private val cachedApps
            get() = PackageCache.installedPackages.toMutableMap().apply {
                remove(BuildConfig.APPLICATION_ID)
                entries.removeAll { (_, packageInfo) ->
                    packageInfo.applicationInfo?.uid?.let(::isPerAppSelectableUid) != true
                }
            }
    }

    private class ProxiedApp(
        private val pm: PackageManager,
        entries: List<Pair<String, ApplicationInfo>>,
    ) {
        private data class Member(
            val packageName: String,
            val appInfo: ApplicationInfo,
            val name: CharSequence,
        )

        private val members = entries.map { (packageName, appInfo) ->
            Member(packageName, appInfo, appInfo.loadLabel(pm))
        }.sortedWith(compareBy({ it.name.toString() }, { it.packageName }))
        private val representative = members.first()

        val packageName get() = representative.packageName
        val packageNames = members.map(Member::packageName)
        val name get() = representative.name
        val uid get() = representative.appInfo.uid
        val sys get() = members.any {
            (it.appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        }
        val appCount get() = members.size

        fun loadIcon(): Drawable = representative.appInfo.loadIcon(pm)

        fun matches(constraint: CharSequence): Boolean =
            uid.toString().contains(constraint) || members.any {
                it.name.contains(constraint, true) || it.packageName.contains(constraint, true)
            }
    }

    private inner class AppViewHolder(val binding: LayoutAppsItemBinding) : RecyclerView.ViewHolder(
        binding.root
    ),
        View.OnClickListener {
        private lateinit var item: ProxiedApp
        private var iconJob: Job? = null
        private var boundPackageName: String? = null

        init {
            binding.root.setOnClickListener(this)
        }

        private fun renderSelectionState() {
            val selected = isProxiedApp(item)
            binding.itemcheck.isChecked = selected
            binding.itemcheck.isEnabled = policyDraft.enabled && !applyingPolicy && !appsLoading
            binding.itemcheck.alpha = if (policyDraft.enabled && !applyingPolicy && !appsLoading) 1f else 0.45f
            binding.root.isChecked = selected
            ViewCompat.setStateDescription(
                binding.root,
                getString(
                    if (selected) R.string.app_proxy_app_selected
                    else R.string.app_proxy_app_not_selected
                ),
            )
        }

        fun bind(app: ProxiedApp) {
            item = app
            boundPackageName = app.packageName
            iconJob?.cancel()
            val cachedIcon = iconCache.get(app.packageName)
            binding.itemicon.setImageDrawable(cachedIcon ?: packageManager.defaultActivityIcon)
            if (cachedIcon == null) {
                iconJob = lifecycleScope.launch(Dispatchers.IO) {
                    val icon = runCatching(app::loadIcon).getOrNull() ?: return@launch
                    iconCache.put(app.packageName, icon)
                    withContext(Dispatchers.Main.immediate) {
                        if (boundPackageName == app.packageName) {
                            binding.itemicon.setImageDrawable(icon)
                        }
                    }
                }
            }
            binding.title.text = app.name
            binding.desc.text = if (app.appCount == 1) {
                "${app.packageName} (${app.uid})"
            } else {
                getString(R.string.app_proxy_shared_network_group, app.appCount)
            }
            renderSelectionState()
        }

        fun handlePayload(payloads: List<String>) {
            if (payloads.contains(SWITCH)) {
                renderSelectionState()
            }
        }

        override fun onClick(v: View?) {
            if (applyingPolicy || appsLoading) return
            if (!policyDraft.enabled) {
                Snackbar.make(
                    binding.root,
                    R.string.app_proxy_enable_first,
                    Snackbar.LENGTH_SHORT,
                ).show()
                return
            }
            val wasSelected = isProxiedApp(item)
            if (wasSelected) proxiedUids.delete(item.uid) else proxiedUids[item.uid] = true
            item.packageNames.forEach { packageName ->
                if (wasSelected) selectedPackages.remove(packageName)
                else selectedPackages.add(packageName)
            }
            updateDraftSelection()
            appsAdapter.notifyUidChanged(item.uid)
        }

        fun recycle() {
            boundPackageName = null
            iconJob?.cancel()
            iconJob = null
            binding.itemicon.setImageDrawable(null)
        }
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppViewHolder>(),
        Filterable,
        FastScrollRecyclerView.SectionedAdapter {
        var filteredApps = apps

        suspend fun reload(): Boolean {
            PackageCache.awaitLoadSync()
            val selectionSanitized = sanitizeDraftSelection()
            // Do not read the lateinit package maps from onCreate. On a cold launch the
            // application-level cache may still be initializing when this activity opens.
            initProxiedUids()
            val appEntries = cachedApps.mapNotNull { (packageName, packageInfo) ->
                coroutineContext[Job]!!.ensureActive()
                packageInfo.applicationInfo?.let { packageName to it }
            }
            apps = appEntries.groupBy { (_, appInfo) -> appInfo.uid }
                .values
                .map { ProxiedApp(packageManager, it) }
                .sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
            return selectionSanitized
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) =
            holder.bind(filteredApps[position])

        override fun onBindViewHolder(holder: AppViewHolder, position: Int, payloads: List<Any>) {
            if (payloads.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST") holder.handlePayload(payloads as List<String>)
                return
            }

            onBindViewHolder(holder, position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder =
            AppViewHolder(LayoutAppsItemBinding.inflate(layoutInflater, parent, false))

        override fun onViewRecycled(holder: AppViewHolder) {
            holder.recycle()
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = filteredApps.size

        fun notifyUidChanged(uid: Int) {
            filteredApps.forEachIndexed { index, app ->
                if (app.uid == uid) notifyItemChanged(index, SWITCH)
            }
        }

        private val filterImpl = object : Filter() {
            override fun performFiltering(constraint: CharSequence) = FilterResults().apply {
                var filteredApps = if (constraint.isEmpty()) apps else apps.filter {
                    it.matches(constraint)
                }
                if (!sysApps) filteredApps = filteredApps.filter { !it.sys }
                count = filteredApps.size
                values = filteredApps
            }

            override fun publishResults(constraint: CharSequence, results: FilterResults) {
                @Suppress("UNCHECKED_CAST")
                val newApps = results.values as List<ProxiedApp>
                val oldApps = filteredApps
                val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = oldApps.size
                    override fun getNewListSize() = newApps.size
                    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                        oldApps[oldItemPosition].packageName == newApps[newItemPosition].packageName
                    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                        val old = oldApps[oldItemPosition]
                        val new = newApps[newItemPosition]
                        return old.uid == new.uid && old.name.toString() == new.name.toString()
                    }
                })
                filteredApps = newApps
                diff.dispatchUpdatesTo(this@AppsAdapter)
            }
        }

        override fun getFilter(): Filter = filterImpl

        override fun getSectionName(position: Int): String {
            return filteredApps[position].name.firstOrNull()?.toString() ?: ""
        }

    }

    private val loading by lazy { findViewById<View>(R.id.loading) }

    private lateinit var binding: LayoutAppsBinding
    private lateinit var policyDraft: PerAppProxyPolicyDraft
    private val policyApplyViewModel by viewModels<PerAppPolicyApplyViewModel>()
    private val proxiedUids = SparseBooleanArray()
    private val selectedPackages = linkedSetOf<String>()
    private var packagesByUid = emptyMap<Int, List<String>>()
    // Launcher icons can retain large adaptive-icon/bitmap backing stores. Two viewports are
    // enough for smooth back-scrolling without pinning dozens of off-screen system icons.
    private val iconCache = LruCache<String, Drawable>(16)
    private var loader: Job? = null
    private var apps = emptyList<ProxiedApp>()
    private val appsAdapter = AppsAdapter()
    private var appLoadGeneration = 0L
    private var initialLoadStarted = false
    private var autoSelectWhenLoaded = false
    private var firstEntrySetupPending = false
    private var renderingPolicy = false
    private var applyingPolicy = false
    private var appsLoading = false
    private var policyLoading = false
    private var policyUiInitialized = false
    private var policyBaseRevision = 0L
    private var durablePolicySnapshot: DataStore.PerAppProxyPolicySnapshot? = null
    private var policyListenerRegistered = false
    private var policyRefreshJob: Job? = null
    private val policyMutationMutex = Mutex()
    private val policyStatusListener = object : OnPreferenceDataStoreChangeListener {
        override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
            if (store !== DataStore.configurationStore || key !in policyStatusKeys) return
            refreshDurablePolicyStatus()
        }
    }
    private val policyStatusKeys = setOf(
        Key.APP_PROXY_DESIRED_REVISION,
        Key.APP_PROXY_APPLIED_REVISION,
        Key.APP_PROXY_APPLIED_TUN_GENERATION,
        Key.APP_PROXY_APPLY_STATUS,
        Key.APP_PROXY_APPLY_FAILURE,
    )

    /** Serializes multi-key Room invalidations and rejects a late snapshot that would regress UI. */
    private fun refreshDurablePolicyStatus() {
        policyRefreshJob?.cancel()
        policyRefreshJob = lifecycleScope.launch {
            policyMutationMutex.withLock {
                val snapshot = runCatching {
                    withContext(Dispatchers.IO) { DataStore.readPerAppProxyPolicy() }
                }.onFailure { error ->
                    Logs.w("Unable to refresh per-app policy status", error)
                }.getOrNull() ?: return@withLock
                if (isFinishing || isDestroyed || !acceptDurablePolicySnapshot(snapshot)) return@withLock
                durablePolicySnapshot = snapshot
                if (
                    ::policyDraft.isInitialized &&
                    !policyDraft.isDirty &&
                    !applyingPolicy &&
                    policyBaseRevision != snapshot.desiredRevision
                ) {
                    val latestPolicy = PerAppProxyPolicy.fromStorage(
                        enabled = snapshot.enabled,
                        serializedPackages = snapshot.serializedPackages,
                    )
                    policyBaseRevision = snapshot.desiredRevision
                    policyDraft.rebase(latestPolicy)
                    initProxiedUids(latestPolicy.serializedPackages)
                    if (apps.isNotEmpty()) rebuildPackageIndex()
                    renderPolicyState(refreshSelections = true)
                }
                renderDurablePolicyStatus(snapshot)
            }
        }
    }

    private fun acceptDurablePolicySnapshot(candidate: DataStore.PerAppProxyPolicySnapshot): Boolean {
        val current = durablePolicySnapshot ?: return true
        if (candidate.desiredRevision != current.desiredRevision) {
            return candidate.desiredRevision > current.desiredRevision
        }
        if (candidate.attemptTunGeneration != current.attemptTunGeneration) {
            return candidate.attemptTunGeneration > current.attemptTunGeneration
        }
        return policyStatusRank(candidate.status) >= policyStatusRank(current.status)
    }

    private fun policyStatusRank(status: DataStore.PerAppPolicyStatus): Int = when (status) {
        DataStore.PerAppPolicyStatus.PENDING -> 0
        DataStore.PerAppPolicyStatus.APPLYING -> 1
        DataStore.PerAppPolicyStatus.APPLIED,
        DataStore.PerAppPolicyStatus.REJECTED,
        DataStore.PerAppPolicyStatus.FAILED_RECOVERED,
        DataStore.PerAppPolicyStatus.FAILED -> 2
    }

    private val requestInstalledAppsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            initialLoadStarted = true
            loadApps(refreshPackageCache = granted)
        }

    private fun initProxiedUids(str: String = policyDraft.policy.serializedPackages) {
        proxiedUids.clear()
        val apps = cachedApps
        for (line in str.lineSequence().map { it.trim().removePrefix("\uFEFF") }) {
            if (line.isBlank()) continue
            val app = (apps[line] ?: continue)
            val uid = app.applicationInfo?.uid ?: continue
            proxiedUids[uid] = true
        }
    }

    private fun isProxiedApp(app: ProxiedApp) = proxiedUids[app.uid]

    private fun sanitizeDraftSelection(): Boolean {
        val original = policyDraft.packages
        val installedUids = PackageCache.installedPackages.mapNotNull { (packageName, packageInfo) ->
            packageInfo.applicationInfo?.uid?.let { packageName to it }
        }.toMap()
        val sanitized = sanitizePerAppPackages(original, installedUids)
        if (sanitized == original) return false
        policyDraft.replacePackages(sanitized)
        return true
    }

    private fun rebuildPackageIndex() {
        packagesByUid = apps.associate { it.uid to it.packageNames }
        val visiblePackages = packagesByUid.values.flatten().toSet()
        val selectedVisiblePackages = packagesByUid.flatMap { (uid, packageNames) ->
            if (proxiedUids[uid]) packageNames else emptyList()
        }
        val mergedSelection = mergeVisiblePerAppSelection(
            savedPackages = policyDraft.packages,
            visiblePackages = visiblePackages,
            selectedVisiblePackages = selectedVisiblePackages,
        )
        selectedPackages.clear()
        selectedPackages.addAll(mergedSelection)
        policyDraft.replacePackages(mergedSelection)
        updateModeSummary()
    }

    private fun selectedPackageCount(): Int = policyDraft.packages.size

    private fun updateModeSummary() {
        if (!::binding.isInitialized) return
        binding.root.post {
            val count = selectedPackageCount()
            val summary = if (policyDraft.enabled) {
                getString(R.string.app_proxy_selected_summary, count)
            } else {
                getString(R.string.app_proxy_disabled_summary, count)
            }
            binding.appProxySelectionSummary.text = if (policyDraft.isDirty) {
                getString(R.string.app_proxy_pending_summary, policyDraft.changeCount, summary)
            } else {
                summary
            }
        }
    }

    private fun renderDurablePolicyStatus(snapshot: DataStore.PerAppProxyPolicySnapshot) {
        val text = when {
            snapshot.isApplied -> getString(
                R.string.app_proxy_status_applied,
                snapshot.desiredRevision,
                snapshot.appliedTunGeneration,
            )
            snapshot.status == DataStore.PerAppPolicyStatus.REJECTED ->
                getString(R.string.app_proxy_status_rejected, snapshot.desiredRevision)
            snapshot.status == DataStore.PerAppPolicyStatus.FAILED_RECOVERED ->
                getString(
                    R.string.app_proxy_status_failed_recovered,
                    snapshot.desiredRevision,
                    snapshot.appliedRevision ?: 0L,
                )
            snapshot.status == DataStore.PerAppPolicyStatus.FAILED ->
                getString(R.string.app_proxy_status_failed, snapshot.desiredRevision)
            else -> getString(R.string.app_proxy_status_pending, snapshot.desiredRevision)
        }
        binding.appProxyApplyStatus.text = text
        invalidateOptionsMenu()
    }

    private fun updateDraftSelection() {
        policyDraft.replacePackages(selectedPackages)
        updateModeSummary()
        invalidateOptionsMenu()
    }

    private fun renderPolicyState(refreshSelections: Boolean = false) {
        renderingPolicy = true
        binding.appProxyToggle.isChecked = policyDraft.enabled
        binding.appProxyToggle.isEnabled = !applyingPolicy && !appsLoading
        binding.search.isEnabled = !appsLoading
        binding.showSystemApps.isEnabled = !appsLoading
        renderingPolicy = false
        updateModeSummary()
        if (refreshSelections) {
            appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount, SWITCH)
        }
        invalidateOptionsMenu()
    }

    private fun renderPolicyApplyState(state: PerAppPolicyApplyState) {
        when (state) {
            PerAppPolicyApplyState.Idle -> Unit
            is PerAppPolicyApplyState.Persisting -> {
                applyingPolicy = true
                renderPolicyState(refreshSelections = true)
            }

            is PerAppPolicyApplyState.PersistedPending -> {
                applyingPolicy = false
                val latest = durablePolicySnapshot
                if (
                    latest != null &&
                    latest.desiredRevision > state.desiredRevision
                ) {
                    rebaseDraftAfterConflict(latest)
                    renderDurablePolicyStatus(latest)
                    renderPolicyState(refreshSelections = true)
                    Snackbar.make(
                        binding.root,
                        R.string.app_proxy_policy_conflict,
                        Snackbar.LENGTH_LONG,
                    ).show()
                    policyApplyViewModel.acknowledge(state)
                    return
                }
                policyBaseRevision = state.desiredRevision
                policyDraft.markCommitted(state.request.policy)
                if (state.request.markSetupDone) firstEntrySetupPending = false
                autoSelectWhenLoaded = false
                renderPolicyState(refreshSelections = true)
                Snackbar.make(
                    binding.root,
                    R.string.app_proxy_changes_pending,
                    Snackbar.LENGTH_LONG,
                ).show()
                policyApplyViewModel.acknowledge(state)
                if (state.request.finishAfterApply) finish()
            }

            is PerAppPolicyApplyState.Conflict -> {
                applyingPolicy = false
                val latest = if (acceptDurablePolicySnapshot(state.latest)) state.latest else {
                    durablePolicySnapshot ?: state.latest
                }
                rebaseDraftAfterConflict(latest)
                renderDurablePolicyStatus(latest)
                renderPolicyState(refreshSelections = true)
                Snackbar.make(
                    binding.root,
                    R.string.app_proxy_policy_conflict,
                    Snackbar.LENGTH_LONG,
                ).show()
                policyApplyViewModel.acknowledge(state)
            }

            is PerAppPolicyApplyState.Failed -> {
                applyingPolicy = false
                Logs.e(state.error)
                renderPolicyState(refreshSelections = true)
                Snackbar.make(
                    binding.root,
                    R.string.app_proxy_apply_failed,
                    Snackbar.LENGTH_LONG,
                ).show()
                policyApplyViewModel.acknowledge(state)
            }
        }
    }

    private fun rebaseDraftAfterConflict(latest: DataStore.PerAppProxyPolicySnapshot) {
        durablePolicySnapshot = latest
        val localDraft = policyDraft.policy
        val latestPolicy = PerAppProxyPolicy.fromStorage(
            enabled = latest.enabled,
            serializedPackages = latest.serializedPackages,
        )
        policyBaseRevision = latest.desiredRevision
        policyDraft.rebase(latestPolicy)
        if (localDraft != latestPolicy) policyDraft.restoreDraft(localDraft)
        initProxiedUids(policyDraft.policy.serializedPackages)
        if (apps.isNotEmpty()) rebuildPackageIndex()
    }

    @UiThread
    private fun loadApps(refreshPackageCache: Boolean = false) {
        val loadGeneration = ++appLoadGeneration
        loader?.cancel()
        loader = lifecycleScope.launch {
            policyMutationMutex.withLock {
                appsLoading = true
                try {
                    binding.appPlaceholder.root.visibility = View.GONE
                    loading.crossFadeFrom(binding.list)
                    renderPolicyState(refreshSelections = true)
                    val adapter = binding.list.adapter as AppsAdapter
                    val reloadResult = withContext(Dispatchers.IO) {
                        runCatching {
                            if (refreshPackageCache) PackageCache.reload()
                            adapter.reload()
                        }
                    }
                    val failure = reloadResult.exceptionOrNull()
                    if (failure != null) {
                        Logs.e(failure)
                        apps = emptyList()
                    }
                    rebuildPackageIndex()
                    if (autoSelectWhenLoaded && policyDraft.packages.isEmpty()) {
                        prepareDefaultAutoSelection()
                    } else {
                        adapter.filter.filter(binding.search.text?.toString() ?: "")
                        renderPolicyState()
                    }
                    if (apps.isEmpty()) {
                        val permissionDenied = !hasInstalledAppsAccess()
                        binding.appPlaceholder.emptyMessage.setText(
                            when {
                                permissionDenied -> R.string.app_list_permission_denied
                                failure != null -> R.string.app_list_load_failed
                                else -> R.string.app_list_empty
                            }
                        )
                        binding.appPlaceholder.openSettings.apply {
                            setText(
                                if (permissionDenied) R.string.open_app_settings else R.string.retry
                            )
                            setOnClickListener {
                                if (permissionDenied) {
                                    startActivity(
                                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = android.net.Uri.fromParts("package", packageName, null)
                                        }
                                    )
                                } else {
                                    loadApps(refreshPackageCache = true)
                                }
                            }
                        }
                        binding.list.visibility = View.GONE
                        binding.appPlaceholder.root.crossFadeFrom(loading)
                    } else {
                        binding.appPlaceholder.root.visibility = View.GONE
                        binding.list.crossFadeFrom(loading)
                    }
                } finally {
                    if (loadGeneration == appLoadGeneration) {
                        appsLoading = false
                        renderPolicyState(refreshSelections = true)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setTitle(R.string.proxied_apps)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)
        binding.appProxyToggle.isEnabled = false
        binding.list.visibility = View.GONE
        binding.appPlaceholder.root.visibility = View.GONE
        loading.visibility = View.VISIBLE
        loadPersistedPolicy(savedInstanceState)
    }

    /**
     * The UI must not build a draft from RoomPreferenceDataStore's safe-but-empty startup cache.
     * Read the three policy rows from one committed snapshot before enabling any selection UI.
     */
    private fun loadPersistedPolicy(savedInstanceState: Bundle?) {
        if (policyLoading || policyUiInitialized) return
        policyLoading = true
        lifecycleScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    DataStore.readPerAppProxyPolicy()
                }
                policyLoading = false
                if (isFinishing || isDestroyed) return@launch
                initializePolicyUi(snapshot, savedInstanceState)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                policyLoading = false
                Logs.e(error)
                if (!isFinishing && !isDestroyed) {
                    showPolicyLoadFailure()
                }
            }
        }
    }

    private fun showPolicyLoadFailure() {
        binding.list.visibility = View.GONE
        binding.appPlaceholder.emptyMessage.setText(R.string.app_proxy_policy_load_failed)
        binding.appPlaceholder.openSettings.apply {
            setText(R.string.retry)
            setOnClickListener { loadPersistedPolicy(savedInstanceState = null) }
        }
        binding.appPlaceholder.root.crossFadeFrom(loading)
    }

    private fun initializePolicyUi(
        snapshot: DataStore.PerAppProxyPolicySnapshot,
        savedInstanceState: Bundle?,
    ) {
        if (policyUiInitialized) return
        policyUiInitialized = true
        val persistedPolicy = PerAppProxyPolicy.fromStorage(
            enabled = snapshot.enabled,
            serializedPackages = snapshot.serializedPackages,
        )
        policyBaseRevision = snapshot.desiredRevision
        durablePolicySnapshot = snapshot
        policyDraft = PerAppProxyPolicyDraft(persistedPolicy)
        savedInstanceState
            ?.takeIf { it.containsKey(STATE_DRAFT_ENABLED) }
            ?.let { state ->
            policyDraft.restoreDraft(
                PerAppProxyPolicy.create(
                    enabled = state.getBoolean(STATE_DRAFT_ENABLED),
                    packages = state.getStringArrayList(STATE_DRAFT_PACKAGES).orEmpty(),
                ),
            )
        }
        renderDurablePolicyStatus(snapshot)
        DataStore.configurationStore.registerChangeListener(policyStatusListener)
        policyListenerRegistered = true
        // Registering after the initial read has a small unavoidable write window. Re-read through
        // the serialized gate so the first Apply never keeps a stale base revision.
        refreshDurablePolicyStatus()

        // First-run recommendations are a draft, not an implicit policy change or VPN reconnect.
        // Existing installations with a non-empty allow-list have already made this choice.
        firstEntrySetupPending = !snapshot.setupDone
        if (firstEntrySetupPending && persistedPolicy.packages.isNotEmpty()) {
            firstEntrySetupPending = false
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { DataStore.markPerAppProxySetupDone() }
                    .onFailure { Logs.w("Unable to mark existing per-app policy as configured", it) }
            }
        }
        autoSelectWhenLoaded = shouldPreparePerAppRecommendations(
            firstEntrySetupPending = firstEntrySetupPending,
            draftIsEmpty = policyDraft.packages.isEmpty(),
            restoredPending = savedInstanceState
                ?.takeIf { it.containsKey(STATE_RECOMMENDATION_PENDING) }
                ?.getBoolean(STATE_RECOMMENDATION_PENDING),
        )

        binding.appProxyToggle.isChecked = policyDraft.enabled
        binding.appProxyToggle.setOnCheckedChangeListener { _, enabled ->
            if (renderingPolicy) return@setOnCheckedChangeListener
            if (applyingPolicy || appsLoading) return@setOnCheckedChangeListener
            policyDraft.setEnabled(enabled)
            if (!enabled) autoSelectWhenLoaded = false
            if (enabled && firstEntrySetupPending && policyDraft.packages.isEmpty()) {
                autoSelectWhenLoaded = true
                if (apps.isNotEmpty()) {
                    prepareDefaultAutoSelection()
                    return@setOnCheckedChangeListener
                }
            }
            renderPolicyState(refreshSelections = true)
        }
        renderPolicyState()

        onBackPressedDispatcher.addCallback(this) {
            requestExitWithDraft()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                policyApplyViewModel.state.collect(::renderPolicyApplyState)
            }
        }

        binding.list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.list.itemAnimator = DefaultItemAnimator()
        binding.list.adapter = appsAdapter

        binding.search.addTextChangedListener {
            appsAdapter.filter.filter(it?.toString() ?: "")
        }

        binding.showSystemApps.isChecked = DataStore.appProxyShowSystemApps
        binding.showSystemApps.setOnCheckedChangeListener { _, isChecked ->
            DataStore.appProxyShowSystemApps = isChecked
            appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
        }

        requestInstalledAppsAccessIfNeeded()
    }

    private fun requestInstalledAppsAccessIfNeeded() {
        if (hasInstalledAppsAccess()) {
            initialLoadStarted = true
            loadApps(refreshPackageCache = true)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.proxied_apps)
            .setMessage(R.string.installed_apps_permission_explanation)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                requestInstalledAppsPermission.launch(INSTALLED_APPS_PERMISSION)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                initialLoadStarted = true
                loadApps()
            }
            .show()
    }

    private fun hasInstalledAppsAccess(): Boolean {
        val supportsRuntimePermission = runCatching {
            packageManager.getPermissionInfo(INSTALLED_APPS_PERMISSION, 0)
                .packageName == "com.lbe.security.miui"
        }.getOrDefault(false)
        return !supportsRuntimePermission || ContextCompat.checkSelfPermission(
            this,
            INSTALLED_APPS_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        if (initialLoadStarted && loader?.isActive != true && apps.isEmpty() &&
            hasInstalledAppsAccess()
        ) {
            loadApps(refreshPackageCache = true)
        }
    }

    private val sysApps: Boolean
        get() = DataStore.appProxyShowSystemApps

    /** Previews the one-time recommendations. Persistence waits for the user's Apply action. */
    private fun prepareDefaultAutoSelection() {
        if (apps.isEmpty()) {
            autoSelectWhenLoaded = true
            return
        }
        runCatching {
            val needProxyApps = getAutoProxyApps("")
            proxiedUids.clear()
            apps.filter { app -> app.packageNames.any { it in needProxyApps } }.forEach { app ->
                proxiedUids[app.uid] = true
            }
            rebuildPackageIndex()
            check(selectedPackages.isNotEmpty()) { getString(R.string.app_proxy_auto_selection_empty) }
            policyDraft.replacePackages(selectedPackages)
            policyDraft.setEnabled(true)
            autoSelectWhenLoaded = false
            renderPolicyState()
            apps = apps.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
            appsAdapter.filter.filter(binding.search.text?.toString() ?: "") {
                // Filtering publishes asynchronously. Refresh selection payloads only after the
                // selected-first list is installed, then reveal its first row immediately.
                binding.list.post {
                    val itemCount = appsAdapter.itemCount
                    if (itemCount <= 0 || isFinishing || isDestroyed) return@post
                    appsAdapter.notifyItemRangeChanged(0, itemCount, SWITCH)
                    (binding.list.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(0, 0)
                }
            }
            Snackbar.make(
                binding.root,
                R.string.app_proxy_recommendations_ready,
                Snackbar.LENGTH_LONG,
            ).show()
        }.onFailure { error ->
            Logs.e(error)
            proxiedUids.clear()
            selectedPackages.clear()
            policyDraft.replacePackages(emptyList())
            policyDraft.setEnabled(false)
            autoSelectWhenLoaded = false
            renderPolicyState(refreshSelections = true)
            Snackbar.make(
                binding.root,
                R.string.app_proxy_auto_selection_empty,
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    private fun requestExitWithDraft() {
        if (applyingPolicy) return
        if (!policyDraft.isDirty) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_proxy_unsaved_changes)
            .setMessage(getString(R.string.app_proxy_unsaved_changes_detail, policyDraft.changeCount))
            .setPositiveButton(R.string.apply) { _, _ -> applyDraft(finishAfterApply = true) }
            .setNegativeButton(R.string.app_proxy_discard_changes) { _, _ ->
                discardDraft()
                finish()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Persists the whole policy in one database transaction. A failed write leaves the draft
     * dirty, so the visible Apply action is a retry and no VPN reconnect is requested.
     */
    private fun applyDraft(finishAfterApply: Boolean = false) {
        if (applyingPolicy) return
        val target = policyDraft.policy
        val retryingFailedPolicy = durablePolicySnapshot?.let { snapshot ->
            snapshot.desiredRevision == policyBaseRevision &&
                snapshot.status in setOf(
                    DataStore.PerAppPolicyStatus.REJECTED,
                    DataStore.PerAppPolicyStatus.FAILED_RECOVERED,
                    DataStore.PerAppPolicyStatus.FAILED,
                )
        } == true
        if (!policyDraft.isDirty && !retryingFailedPolicy) {
            if (finishAfterApply) finish()
            return
        }
        if (target.enabled && target.packages.isEmpty()) {
            Snackbar.make(binding.root, R.string.app_proxy_empty_selection, Snackbar.LENGTH_LONG).show()
            return
        }
        applyingPolicy = true
        renderPolicyState(refreshSelections = true)
        policyApplyViewModel.submit(
            PerAppPolicyCommitRequest(
                baseRevision = policyBaseRevision,
                policy = target,
                markSetupDone = firstEntrySetupPending,
                finishAfterApply = finishAfterApply,
            ),
        )
    }

    private fun getAutoProxyApps(content: String): Set<String> {
        val raw = runCatching {
            if (content.isBlank()) {
                app.assets.open("proxy_packagename.txt").bufferedReader().use { it.readText() }
            } else {
                content
            }
        }.getOrDefault("")
        return raw.lineSequence()
            .map { it.substringBefore('#').trim().removePrefix("\uFEFF") }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun discardDraft() {
        val restored = policyDraft.discard()
        initProxiedUids(restored.serializedPackages)
        rebuildPackageIndex()
        acknowledgeFirstEntryDismissal()
        renderPolicyState(refreshSelections = true)
    }

    /** A deliberate discard counts as a first-run choice, without changing the active VPN policy. */
    private fun acknowledgeFirstEntryDismissal() {
        if (!firstEntrySetupPending) return
        firstEntrySetupPending = false
        // This confirmation must outlive a quick Activity finish after the user presses Discard.
        applicationScope.launch(Dispatchers.IO) {
            runCatching { DataStore.markPerAppProxySetupDone() }
                .onFailure { Logs.w("Unable to remember per-app recommendation dismissal", it) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.app_manager_policy_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val retryingFailedPolicy = durablePolicySnapshot?.status in setOf(
            DataStore.PerAppPolicyStatus.REJECTED,
            DataStore.PerAppPolicyStatus.FAILED_RECOVERED,
            DataStore.PerAppPolicyStatus.FAILED,
        )
        val showApply = ::policyDraft.isInitialized &&
            (policyDraft.isDirty || retryingFailedPolicy) &&
            !applyingPolicy
        menu.findItem(R.id.action_apply_app_policy)?.isVisible = showApply
        menu.findItem(R.id.action_discard_app_policy)?.isVisible =
            ::policyDraft.isInitialized && policyDraft.isDirty && !applyingPolicy
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (!::policyDraft.isInitialized) return super.onOptionsItemSelected(item)
        return when (item.itemId) {
            R.id.action_apply_app_policy -> {
                applyDraft()
                true
            }

            R.id.action_discard_app_policy -> {
                discardDraft()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::policyDraft.isInitialized) {
            outState.putBoolean(STATE_DRAFT_ENABLED, policyDraft.enabled)
            outState.putStringArrayList(STATE_DRAFT_PACKAGES, ArrayList(policyDraft.packages))
            outState.putBoolean(STATE_RECOMMENDATION_PENDING, autoSelectWhenLoaded)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun supportNavigateUpTo(upIntent: Intent) =
        super.supportNavigateUpTo(upIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))

    override fun onDestroy() {
        policyRefreshJob?.cancel()
        policyRefreshJob = null
        if (policyListenerRegistered) {
            DataStore.configurationStore.unregisterChangeListener(policyStatusListener)
            policyListenerRegistered = false
        }
        loader?.cancel()
        iconCache.evictAll()
        super.onDestroy()
    }
}
