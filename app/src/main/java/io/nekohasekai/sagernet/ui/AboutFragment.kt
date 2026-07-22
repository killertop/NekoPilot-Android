package io.nekohasekai.sagernet.ui

import android.os.Build
import android.os.Bundle
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.databinding.LayoutAboutBinding
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onIoDispatcher
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.libbox.Libbox

class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    override val showBackNavigation = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_about)

        val binding = LayoutAboutBinding.bind(view)
        binding.version.text = SagerNet.appVersionNameForDisplay
        val coreVersion = Libbox.version()
        binding.singboxVersion.text = formatCoreVersionSummary(coreVersion)
        binding.androidVersion.text = Build.VERSION.RELEASE.ifBlank { Build.VERSION.CODENAME }
        binding.androidSdk.text = Build.VERSION.SDK_INT.toString()
        binding.architecture.text = Build.SUPPORTED_ABIS.firstOrNull() ?: getString(R.string.unknown)
        binding.singboxVersionRow.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.core_details)
                .setMessage(coreVersion)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        binding.sourceCode.setOnClickListener {
            requireContext().launchCustomTab(PROJECT_URL)
        }
        binding.checkUpdates.setOnClickListener {
            checkForUpdates(binding)
        }
    }

    private fun checkForUpdates(binding: LayoutAboutBinding) {
        binding.checkUpdates.isEnabled = false
        binding.checkUpdates.text = getString(R.string.checking_updates)
        runOnLifecycleDispatcher {
            val result = runCatching {
                onIoDispatcher { AppReleaseChecker.fetchLatest() }
            }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                binding.checkUpdates.isEnabled = true
                binding.checkUpdates.text = getString(R.string.check_for_updates)
                result.fold(
                    onSuccess = { release -> showUpdateResult(release) },
                    onFailure = { showUpdateCheckFailed() },
                )
            }
        }
    }

    private fun showUpdateResult(release: AppRelease) {
        if (!isRemoteVersionNewer(release.version, BuildConfig.VERSION_NAME)) {
            snackbar(
                getString(R.string.no_update_message, SagerNet.appVersionNameForDisplay)
            ).setDuration(1_800).show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.update_available_title)
            .setMessage(
                getString(
                    R.string.update_available_message,
                    SagerNet.appVersionNameForDisplay,
                    release.version,
                    release.notes.ifBlank { getString(R.string.update_notes_empty) },
                )
            )
            .setNegativeButton(R.string.update_later, null)
            .setPositiveButton(R.string.go_to_download) { _, _ ->
                requireContext().launchCustomTab(release.downloadPageUrl)
            }
            .show()
    }

    private fun showUpdateCheckFailed() {
        snackbar(R.string.update_check_failed).setDuration(Snackbar.LENGTH_LONG).show()
    }

}

private const val PROJECT_URL = "https://github.com/killertop/NekoPilot-Android"

internal fun formatCoreVersion(raw: String): String {
    val lines = raw.lineSequence().filter(String::isNotBlank).toList()
    if (lines.size < 3) return raw
    val capabilities = lines.drop(2)
        .flatMap { it.split(',') }
        .map { it.trim().removePrefix("with_") }
        .filter(String::isNotBlank)
    return buildList {
        addAll(lines.take(2))
        addAll(capabilities.chunked(3).map { it.joinToString(" · ") })
    }.joinToString("\n")
}

internal fun formatCoreVersionSummary(raw: String): String = raw.lineSequence()
    .firstOrNull { it.startsWith("sing-box:") }
    ?.removePrefix("sing-box:")
    ?.trim()
    ?: raw.lineSequence().firstOrNull(String::isNotBlank).orEmpty()
