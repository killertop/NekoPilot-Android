package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.Executable
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    override fun name0() = app.getString(R.string.backup)

    private lateinit var binding: LayoutBackupBinding
    private val exportSettings =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { data ->
            if (data != null) {
                val includeConfigurations = binding.backupConfigurations.isChecked
                val includeRules = binding.backupRules.isChecked
                val includeSettings = binding.backupSettings.isChecked
                runOnDefaultDispatcher {
                    try {
                        val content = doBackup(
                            includeConfigurations,
                            includeRules,
                            includeSettings,
                        )
                        requireActivity().contentResolver.openOutputStream(
                            data
                        )?.bufferedWriter()?.use {
                            it.write(content)
                        } ?: error(getString(R.string.action_export_err))
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding = LayoutBackupBinding.bind(view)

        binding.resetSettings.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                .setMessage(R.string.reset_settings_message)
                .setNegativeButton(R.string.no, null)
                .setPositiveButton(R.string.yes) { _, _ ->
                    DataStore.configurationStore.reset()
                    triggerFullRestart(requireContext())
                }
                .show()
        }

        binding.actionExport.setOnClickListener {
            startFilesForResult(exportSettings, backupFileName())
        }

        binding.actionShare.setOnClickListener {
            runOnDefaultDispatcher {
                val content = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked
                )
                val cacheFile = File(
                    File(app.cacheDir, "backup").also { it.mkdirs() }, backupFileName()
                )
                cacheFile.writeText(content)
                onMainDispatcher {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("application/json")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .putExtra(
                                    Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                        app, BuildConfig.APPLICATION_ID + ".cache", cacheFile
                                    )
                                ), app.getString(R.string.abc_shareactionprovider_share_with)
                        )
                    )
                }

            }
        }

        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }
    }

    private fun backupFileName() =
        "nekopilot_backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())}.json"

    fun Parcelable.toBase64Str(): String {
        val parcel = Parcel.obtain()
        writeToParcel(parcel, 0)
        try {
            return Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    fun doBackup(profile: Boolean, rule: Boolean, setting: Boolean): String {
        val out = JSONObject().apply {
            put("version", 1)
            if (profile) {
                put("profiles", JSONArray().apply {
                    SagerDatabase.proxyDao.getAll().forEach {
                        put(it.toBase64Str())
                    }
                })

                put("groups", JSONArray().apply {
                    SagerDatabase.groupDao.allGroups().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (rule) {
                put("rules", JSONArray().apply {
                    SagerDatabase.rulesDao.allRules().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (setting) {
                put("settings", JSONArray().apply {
                    PublicDatabase.kvPairDao.all().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
        }
        return out.toStringPretty()
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            runOnDefaultDispatcher {
                startImport(file)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val fileName = runCatching {
            requireContext().contentResolver.query(file, null, null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                }
        }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
            .substringAfterLast('/')
            .substringAfter(':')

        if (!fileName.endsWith(".json", ignoreCase = true)) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
            }
            return
        }

        suspend fun invalid() = onMainDispatcher {
            snackbar(getString(R.string.invalid_backup_file)).show()
        }

        val content = try {
            val stream = requireContext().contentResolver.openInputStream(file)
                ?: error("Unable to open backup file")
            JSONObject(stream.use {
                BackupSafety.readUtf8(it)
            })
        } catch (e: Exception) {
            Logs.w(e)
            invalid()
            return
        }
        val version = content.optInt("version", 0)
        if (version < 1 || version > 1) {
            invalid()
            return
        }

        onMainDispatcher {
            val import = LayoutImportBinding.inflate(layoutInflater)
            if (!content.has("profiles")) {
                import.backupConfigurations.isVisible = false
            }
            if (!content.has("rules")) {
                import.backupRules.isVisible = false
            }
            if (!content.has("settings")) {
                import.backupSettings.isVisible = false
            }
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                .setView(import.root)
                .setPositiveButton(R.string.backup_import) { _, _ ->
                    SagerNet.stopService()

                    val binding = LayoutProgressBinding.inflate(layoutInflater)
                    binding.content.text = getString(R.string.backup_importing)
                    val dialog = MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.backup_import)
                        .setView(binding.root)
                        .setCancelable(false)
                        .show()
                    runOnDefaultDispatcher {
                        runCatching {
                            finishImport(
                                content,
                                import.backupConfigurations.isChecked,
                                import.backupRules.isChecked,
                                import.backupSettings.isChecked
                            )
                            triggerFullRestart(requireContext())
                        }.onFailure {
                            Logs.w(it)
                            onMainDispatcher {
                                alert(it.readableMessage).tryToShow()
                            }
                        }

                        onMainDispatcher {
                            dialog.dismiss()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    fun finishImport(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean
    ) {
        require(content.optInt("version", 0) == 1) { "Unsupported backup version" }

        val profiles = if (profile && content.has("profiles")) {
            require(content.has("groups")) { "Backup profiles are missing their groups" }
            decodeSection(content, "profiles", ProxyEntity.CREATOR::createFromParcel)
        } else null
        val groups = if (profiles != null) {
            decodeSection(content, "groups", ProxyGroup.CREATOR::createFromParcel)
        } else null
        val rules = if (rule && content.has("rules")) {
            decodeSection(content, "rules", ParcelizeBridge::createRule)
        } else null
        val settings = if (setting && content.has("settings")) {
            decodeSection(content, "settings", KeyValuePair.CREATOR::createFromParcel)
        } else null

        val existingProfileIds = if (profiles == null && (rules != null || settings != null)) {
            SagerDatabase.proxyDao.getAll().mapTo(HashSet(), ProxyEntity::id)
        } else null
        val existingGroupIds = if (groups == null && settings != null) {
            SagerDatabase.groupDao.allGroups().mapTo(HashSet(), ProxyGroup::id)
        } else null
        BackupSafety.validateDecodedData(
            profiles,
            groups,
            rules,
            settings,
            existingProfileIds,
            existingGroupIds,
        )

        val previousProfiles = profiles?.let { SagerDatabase.proxyDao.getAll() }
        val previousGroups = groups?.let { SagerDatabase.groupDao.allGroups() }
        val previousRules = rules?.let { SagerDatabase.rulesDao.allRules() }

        val applyProfilesAndRules = if (profiles != null || rules != null) {
            {
                SagerDatabase.instance.runInTransaction {
                    if (profiles != null && groups != null) {
                        SagerDatabase.proxyDao.reset()
                        SagerDatabase.groupDao.reset()
                        SagerDatabase.groupDao.insert(groups)
                        SagerDatabase.proxyDao.insert(profiles)
                    }
                    if (rules != null) {
                        SagerDatabase.rulesDao.reset()
                        SagerDatabase.rulesDao.insert(rules)
                    }
                }
            }
        } else null

        val rollbackProfilesAndRules = if (applyProfilesAndRules != null) {
            {
                SagerDatabase.instance.runInTransaction {
                    if (previousProfiles != null && previousGroups != null) {
                        SagerDatabase.proxyDao.reset()
                        SagerDatabase.groupDao.reset()
                        SagerDatabase.groupDao.insert(previousGroups)
                        SagerDatabase.proxyDao.insert(previousProfiles)
                    }
                    if (previousRules != null) {
                        SagerDatabase.rulesDao.reset()
                        SagerDatabase.rulesDao.insert(previousRules)
                    }
                }
            }
        } else null

        val effectiveSettings = when {
            settings != null -> settings
            profiles != null && groups != null -> {
                val profileIds = profiles.mapTo(HashSet(), ProxyEntity::id)
                val groupIds = groups.mapTo(HashSet(), ProxyGroup::id)
                val fallbackGroupId = groups.firstOrNull { it.type == GroupType.BASIC }?.id
                    ?: groups.firstOrNull()?.id
                    ?: 0L
                BackupSafety.reconcileSelections(
                    PublicDatabase.kvPairDao.all(),
                    profileIds,
                    groupIds,
                    fallbackGroupId,
                )
            }
            else -> null
        }
        val applySettings = effectiveSettings?.let {
            {
                PublicDatabase.instance.runInTransaction {
                    PublicDatabase.kvPairDao.reset()
                    PublicDatabase.kvPairDao.insert(it)
                }
            }
        }

        restoreWithRollback(applyProfilesAndRules, applySettings, rollbackProfilesAndRules)
    }

    private fun <T> decodeSection(
        content: JSONObject,
        name: String,
        create: (Parcel) -> T,
    ): List<T> {
        val array = content.getJSONArray(name)
        val encoded = List(array.length()) { index ->
            array.opt(index) as? String ?: error("$name contains a non-string item")
        }
        BackupSafety.validateEncodedSection(name, encoded)
        return encoded.map { value ->
            val data = Util.b64Decode(value)
            require(data.isNotEmpty() && data.size <= MAX_BACKUP_ITEM_CHARS) {
                "$name contains invalid parcel data"
            }
            Parcel.obtain().let { parcel ->
                try {
                    parcel.unmarshall(data, 0, data.size)
                    parcel.setDataPosition(0)
                    create(parcel)
                } finally {
                    parcel.recycle()
                }
            }
        }
    }

}
