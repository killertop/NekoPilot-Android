package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.SparseBooleanArray
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat
import androidx.core.util.contains
import androidx.core.util.set
import androidx.core.view.ViewCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAppsBinding
import io.nekohasekai.sagernet.databinding.LayoutAppsItemBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applicationScope
import io.nekohasekai.sagernet.ktx.crossFadeFrom
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.utils.isPerAppSelectableUid
import io.nekohasekai.sagernet.utils.sanitizePerAppPackages
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.NGUtil
import kotlin.coroutines.coroutineContext

class AppManagerActivity : ThemedActivity() {
    companion object {
        private const val SWITCH = "switch"
        private const val INSTALLED_APPS_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"

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

        init {
            binding.root.setOnClickListener(this)
        }

        fun bind(app: ProxiedApp) {
            item = app
            val icon = iconCache.get(app.packageName) ?: app.loadIcon().also {
                iconCache.put(app.packageName, it)
            }
            binding.itemicon.setImageDrawable(icon)
            binding.title.text = app.name
            binding.desc.text = if (app.appCount == 1) {
                "${app.packageName} (${app.uid})"
            } else {
                getString(R.string.app_proxy_shared_network_group, app.appCount)
            }
            binding.itemcheck.isChecked = isProxiedApp(app)
            binding.itemcheck.isEnabled = DataStore.proxyApps
            binding.itemcheck.alpha = if (DataStore.proxyApps) 1f else 0.45f
        }

        fun handlePayload(payloads: List<String>) {
            if (payloads.contains(SWITCH)) {
                binding.itemcheck.isChecked = isProxiedApp(item)
                binding.itemcheck.isEnabled = DataStore.proxyApps
                binding.itemcheck.alpha = if (DataStore.proxyApps) 1f else 0.45f
            }
        }

        override fun onClick(v: View?) {
            if (!DataStore.proxyApps) {
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
            persistSelectedPackages()
            appsAdapter.notifyUidChanged(item.uid)
        }
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppViewHolder>(),
        Filterable,
        FastScrollRecyclerView.SectionedAdapter {
        var filteredApps = apps

        suspend fun reload(): Boolean {
            PackageCache.awaitLoadSync()
            val selectionSanitized = sanitizeStoredSelection()
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
    private val proxiedUids = SparseBooleanArray()
    private val selectedPackages = linkedSetOf<String>()
    private var packagesByUid = emptyMap<Int, List<String>>()
    private val iconCache = LruCache<String, Drawable>(64)
    private var loader: Job? = null
    private var vpnPolicyReload: Job? = null
    private var apps = emptyList<ProxiedApp>()
    private val appsAdapter = AppsAdapter()
    private var initialLoadStarted = false
    private var autoSelectWhenLoaded = false
    private var firstEntrySetupPending = false

    private val requestInstalledAppsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            initialLoadStarted = true
            loadApps(refreshPackageCache = granted)
        }

    private fun initProxiedUids(str: String = DataStore.individual) {
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

    private fun sanitizeStoredSelection(): Boolean {
        val original = DataStore.individual.lineSequence().toList()
        val installedUids = PackageCache.installedPackages.mapNotNull { (packageName, packageInfo) ->
            packageInfo.applicationInfo?.uid?.let { packageName to it }
        }.toMap()
        val sanitized = sanitizePerAppPackages(original, installedUids)
        val originalNormalized = original.map { it.trim().removePrefix("\uFEFF") }
            .filterTo(linkedSetOf(), String::isNotEmpty)
        if (sanitized == originalNormalized) return false
        DataStore.individual = sanitized.joinToString("\n")
        return true
    }

    private fun rebuildPackageIndex() {
        packagesByUid = apps.associate { it.uid to it.packageNames }
        selectedPackages.clear()
        packagesByUid.forEach { (uid, packageNames) ->
            if (proxiedUids[uid]) selectedPackages.addAll(packageNames)
        }
        updateModeSummary()
    }

    private fun selectedPackageCount(): Int {
        if (apps.isNotEmpty()) return selectedPackages.size
        return DataStore.individual.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .count()
    }

    private fun updateModeSummary() {
        if (!::binding.isInitialized) return
        binding.root.post {
            val count = selectedPackageCount()
            binding.appProxySelectionSummary.text = if (DataStore.proxyApps) {
                getString(R.string.app_proxy_selected_summary, count)
            } else {
                getString(R.string.app_proxy_disabled_summary, count)
            }
        }
    }

    private fun persistSelectedPackages() {
        DataStore.individual = selectedPackages.joinToString("\n")
        updateModeSummary()
        scheduleVpnPolicyReload()
    }

    private fun scheduleVpnPolicyReload() {
        vpnPolicyReload?.cancel()
        vpnPolicyReload = applicationScope.launch {
            // A short debounce lets several quick selections become one tunnel rebuild.
            delay(350)
            withContext(Dispatchers.IO) {
                SagerNet.reloadService()
            }
        }
    }

    @UiThread
    private fun loadApps(refreshPackageCache: Boolean = false) {
        loader?.cancel()
        loader = lifecycleScope.launch {
            binding.appPlaceholder.root.visibility = View.GONE
            loading.crossFadeFrom(binding.list)
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
            adapter.filter.filter(binding.search.text?.toString() ?: "")
            if (autoSelectWhenLoaded && DataStore.proxyApps && DataStore.individual.isBlank()) {
                applyDefaultAutoSelection()
            }
            if (reloadResult.getOrDefault(false)) scheduleVpnPolicyReload()
            if (apps.isEmpty()) {
                binding.list.visibility = View.GONE
                binding.appPlaceholder.root.crossFadeFrom(loading)
            } else {
                binding.appPlaceholder.root.visibility = View.GONE
                binding.list.crossFadeFrom(loading)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // On a fresh install, make the safe default useful immediately: select the
        // recommended apps once and enable selected-app VPN mode. If a user already
        // has a saved list from an older build, preserve it and preserve their switch.
        firstEntrySetupPending = !DataStore.appProxySetupDone
        if (firstEntrySetupPending && DataStore.individual.isBlank()) {
            DataStore.proxyApps = true
            autoSelectWhenLoaded = true
        } else if (firstEntrySetupPending) {
            DataStore.appProxySetupDone = true
            firstEntrySetupPending = false
        }

        binding.appPlaceholder.openSettings.setOnClickListener {
            val intent =
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
            startActivity(intent)
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setTitle(R.string.proxied_apps)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.appProxyToggle.isChecked = DataStore.proxyApps
        binding.appProxyToggle.setOnCheckedChangeListener { _, enabled ->
            DataStore.proxyApps = enabled
            autoSelectWhenLoaded = enabled && !DataStore.appProxySetupDone && DataStore.individual.isBlank()
            if (autoSelectWhenLoaded) applyDefaultAutoSelection()
            updateModeSummary()
            appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount, SWITCH)
            if (!autoSelectWhenLoaded) scheduleVpnPolicyReload()
        }
        updateModeSummary()

        binding.list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.list.itemAnimator = DefaultItemAnimator()
        binding.list.adapter = appsAdapter

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)

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

    private fun applyDefaultAutoSelection() {
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
            persistSelectedPackages()
            apps = apps.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
            appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
            autoSelectWhenLoaded = false
            DataStore.appProxySetupDone = true
            firstEntrySetupPending = false
        }.onFailure { Logs.e(it) }
    }

    private fun getAutoProxyApps(content: String): Set<String> {
        val raw = runCatching {
            if (content.isBlank()) NGUtil.readTextFromAssets(app, "proxy_packagename.txt") else content
        }.getOrDefault("")
        return raw.lineSequence()
            .map { it.substringBefore('#').trim().removePrefix("\uFEFF") }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    override fun supportNavigateUpTo(upIntent: Intent) =
        super.supportNavigateUpTo(upIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))

    override fun onDestroy() {
        loader?.cancel()
        iconCache.evictAll()
        super.onDestroy()
    }
}
