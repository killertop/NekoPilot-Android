package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.TextUtils
import android.util.SparseBooleanArray
import android.util.LruCache
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
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
import io.nekohasekai.sagernet.ktx.crossFadeFrom
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
            }
    }

    private class ProxiedApp(
        private val pm: PackageManager, private val appInfo: ApplicationInfo,
        val packageName: String,
    ) {
        val name: CharSequence = appInfo.loadLabel(pm)    // cached for sorting
        fun loadIcon(): Drawable = appInfo.loadIcon(pm)
        val uid get() = appInfo.uid
        val sys get() = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
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
            binding.desc.text = "${app.packageName} (${app.uid})"
            binding.itemcheck.isChecked = isProxiedApp(app)
        }

        fun handlePayload(payloads: List<String>) {
            if (payloads.contains(SWITCH)) binding.itemcheck.isChecked = isProxiedApp(item)
        }

        override fun onClick(v: View?) {
            val wasSelected = isProxiedApp(item)
            if (wasSelected) proxiedUids.delete(item.uid) else proxiedUids[item.uid] = true
            packagesByUid[item.uid].orEmpty().forEach { packageName ->
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

        suspend fun reload() {
            PackageCache.awaitLoadSync()
            // Do not read the lateinit package maps from onCreate. On a cold launch the
            // application-level cache may still be initializing when this activity opens.
            initProxiedUids()
            apps = cachedApps.mapNotNull { (packageName, packageInfo) ->
                coroutineContext[Job]!!.ensureActive()
                packageInfo.applicationInfo?.let { ProxiedApp(packageManager, it, packageName) }
            }.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
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
                    it.name.contains(constraint, true) || it.packageName.contains(
                        constraint, true
                    ) || it.uid.toString().contains(constraint)
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
    private var apps = emptyList<ProxiedApp>()
    private val appsAdapter = AppsAdapter()
    private var initialLoadStarted = false

    private val requestInstalledAppsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            initialLoadStarted = true
            loadApps(refreshPackageCache = granted)
        }

    private fun initProxiedUids(str: String = DataStore.individual) {
        proxiedUids.clear()
        val apps = cachedApps
        for (line in str.lineSequence()) {
            val app = (apps[line] ?: continue)
            val uid = app.applicationInfo?.uid ?: continue
            proxiedUids[uid] = true
        }
    }

    private fun isProxiedApp(app: ProxiedApp) = proxiedUids[app.uid]

    private fun rebuildPackageIndex() {
        packagesByUid = apps.groupBy(ProxiedApp::uid) { it.packageName }
        selectedPackages.clear()
        packagesByUid.forEach { (uid, packageNames) ->
            if (proxiedUids[uid]) selectedPackages.addAll(packageNames)
        }
    }

    private fun persistSelectedPackages() {
        DataStore.individual = selectedPackages.joinToString("\n")
    }

    @UiThread
    private fun loadApps(refreshPackageCache: Boolean = false) {
        loader?.cancel()
        loader = lifecycleScope.launch {
            binding.appPlaceholder.root.visibility = View.GONE
            loading.crossFadeFrom(binding.list)
            val adapter = binding.list.adapter as AppsAdapter
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    if (refreshPackageCache) PackageCache.reload()
                    adapter.reload()
                }.exceptionOrNull()
            }
            if (failure != null) {
                Logs.e(failure)
                apps = emptyList()
            }
            rebuildPackageIndex()
            adapter.filter.filter(binding.search.text?.toString() ?: "")
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

        binding.bypassGroup.check(
            when {
                !DataStore.proxyApps -> R.id.appProxyModeDisable
                DataStore.bypass -> R.id.appProxyModeBypass
                else -> R.id.appProxyModeOn
            }
        )
        binding.bypassGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            when (checkedId) {
                R.id.appProxyModeDisable -> {
                    DataStore.proxyApps = false
                    finish()
                }

                R.id.appProxyModeOn -> {
                    DataStore.proxyApps = true
                    DataStore.bypass = false
                }

                R.id.appProxyModeBypass -> {
                    DataStore.proxyApps = true
                    DataStore.bypass = true
                }
            }
        }
        binding.autoSelectProxyApps.setOnClickListener { selectProxyApp() }

        binding.list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.list.itemAnimator = DefaultItemAnimator()
        binding.list.adapter = appsAdapter

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)

        binding.search.addTextChangedListener {
            appsAdapter.filter.filter(it?.toString() ?: "")
        }

        binding.showSystemApps.isChecked = sysApps
        binding.showSystemApps.setOnCheckedChangeListener { _, isChecked ->
            sysApps = isChecked
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

    private var sysApps = true

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.per_app_proxy_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_invert_selections -> {
                runOnDefaultDispatcher {
                    val proxiedUidsOld = proxiedUids.clone()
                    for (app in apps) {
                        if (proxiedUidsOld.contains(app.uid)) {
                            proxiedUids.delete(app.uid)
                        } else {
                            proxiedUids[app.uid] = true
                        }
                    }
                    rebuildPackageIndex()
                    persistSelectedPackages()
                    apps = apps.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
                    onMainDispatcher {
                        appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
                    }
                }

                return true
            }

            R.id.action_clear_selections -> {
                runOnDefaultDispatcher {
                    proxiedUids.clear()
                    selectedPackages.clear()
                    persistSelectedPackages()
                    apps = apps.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
                    onMainDispatcher {
                        appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
                    }
                }
            }

            R.id.action_export_clipboard -> {
                val success =
                    SagerNet.trySetPrimaryClip("${DataStore.bypass}\n${DataStore.individual}")
                Snackbar.make(
                    binding.list,
                    if (success) R.string.action_export_msg else R.string.action_export_err,
                    Snackbar.LENGTH_LONG
                ).show()
                return true
            }

            R.id.action_import_clipboard -> {
                val proxiedAppString =
                    SagerNet.clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!proxiedAppString.isNullOrEmpty()) {
                    val i = proxiedAppString.indexOf('\n')
                    try {
                        val (enabled, apps) = if (i < 0) {
                            proxiedAppString to ""
                        } else proxiedAppString.substring(
                            0, i
                        ) to proxiedAppString.substring(i + 1)
                        binding.bypassGroup.check(if (enabled.toBoolean()) R.id.appProxyModeBypass else R.id.appProxyModeOn)
                        DataStore.individual = apps
                        Snackbar.make(
                            binding.list, R.string.action_import_msg, Snackbar.LENGTH_LONG
                        ).show()
                        initProxiedUids(apps)
                        rebuildPackageIndex()
                        persistSelectedPackages()
                        appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount, SWITCH)
                        return true
                    } catch (_: IllegalArgumentException) {
                    }
                }
                Snackbar.make(binding.list, R.string.action_import_err, Snackbar.LENGTH_LONG).show()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun selectProxyApp() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.confirm)
            .setMessage(R.string.auto_select_proxy_apps_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                try {
                    val needProxyAppsList = getAutoProxyApps("")
                    val bypass = DataStore.bypass
                    proxiedUids.clear()
                    for (app in cachedApps) {
                        val needProxy =
                            needProxyAppsList.contains(app.key) || (app.value.applicationInfo?.uid
                                ?: 0) == 1000
                        if (needProxy) {
                            if (!bypass) {
                                app.value.applicationInfo?.apply {
                                    proxiedUids[uid] = true
                                }
                            }
                        } else {
                            if (bypass) {
                                app.value.applicationInfo?.apply {
                                    proxiedUids[uid] = true
                                }
                            }
                        }
                    }
                    rebuildPackageIndex()
                    persistSelectedPackages()
                    apps = apps.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
                    appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
                } catch (e: Exception) {
                    Logs.e(e)
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun getAutoProxyApps(content: String): List<String> {
        var list = listOf<String>()
        try {
            val proxyApps = if (TextUtils.isEmpty(content)) {
                NGUtil.readTextFromAssets(app, "proxy_packagename.txt")
            } else {
                content
            }
            if (!TextUtils.isEmpty(proxyApps)) {
                list = proxyApps.split("\n")
            }
        } catch (_: Exception) {
        }
        return list
    }

    override fun supportNavigateUpTo(upIntent: Intent) =
        super.supportNavigateUpTo(upIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))

    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = if (keyCode == KeyEvent.KEYCODE_MENU) {
        if (binding.toolbar.isOverflowMenuShowing) binding.toolbar.hideOverflowMenu() else binding.toolbar.showOverflowMenu()
    } else super.onKeyUp(keyCode, event)

    override fun onDestroy() {
        loader?.cancel()
        iconCache.evictAll()
        super.onDestroy()
    }
}
