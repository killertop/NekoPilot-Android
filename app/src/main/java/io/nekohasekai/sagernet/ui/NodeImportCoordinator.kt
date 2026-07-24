package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.LayoutHomeAddSheetBinding
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.snackbar

/** Shared import flow for Home, where nodes and airport subscriptions are added. */
internal object NodeImportCoordinator {
    fun showAddOptions(fragment: ToolbarFragment) {
        val binding = LayoutHomeAddSheetBinding.inflate(fragment.layoutInflater)
        val dialog = BottomSheetDialog(fragment.requireContext())
        binding.addScanQr.setOnClickListener {
            dialog.dismiss()
            fragment.startActivity(Intent(fragment.context, ScannerActivity::class.java))
        }
        binding.addFromClipboard.setOnClickListener {
            importClipboard(fragment)
            // Android only exposes clipboard contents to the focused app. Read first, then
            // dismiss the sheet; reversing this order can lose focus during the animation.
            dialog.dismiss()
        }
        dialog.setContentView(binding.root)
        dialog.show()
    }

    private fun importClipboard(fragment: ToolbarFragment) {
        val text = SagerNet.getClipboardText().trim()
        if (text.isBlank()) {
            fragment.snackbar(R.string.clipboard_empty).show()
            return
        }
        fragment.runOnLifecycleDispatcher {
            try {
                subscriptionImportUri(text)?.let { subscriptionUri ->
                    onMainDispatcher {
                        (fragment.activity as? MainActivity)?.requestSubscriptionImport(
                            subscriptionUri,
                            R.id.nav_home,
                        )
                    }
                    return@runOnLifecycleDispatcher
                }
                val proxies = RawUpdater.parseRaw(text)
                if (proxies.isNullOrEmpty()) {
                    onMainDispatcher {
                        if (fragment.isAdded) {
                            fragment.snackbar(R.string.no_proxies_found_in_clipboard).show()
                        }
                    }
                    return@runOnLifecycleDispatcher
                }
                val targetId = DataStore.selectedGroupForImport()
                ProfileManager.createProfiles(targetId, proxies)
                onMainDispatcher {
                    if (!fragment.isAdded) return@onMainDispatcher
                    DataStore.editingGroup = targetId
                    fragment.snackbar(
                        fragment.resources.getQuantityString(
                            R.plurals.added,
                            proxies.size,
                            proxies.size,
                        ),
                    ).show()
                }
            } catch (error: SubscriptionFoundException) {
                onMainDispatcher {
                    (fragment.activity as? MainActivity)?.requestSubscriptionImport(
                        error.link.toUri(),
                        R.id.nav_home,
                    )
                }
            } catch (error: Exception) {
                Logs.w(error)
                onMainDispatcher {
                    if (fragment.isAdded) fragment.snackbar(error.readableMessage).show()
                }
            }
        }
    }

    internal fun subscriptionImportUri(rawText: String): Uri? {
        val raw = rawText.trim()
        if (
            raw.isBlank() || raw.length > MAX_SUBSCRIPTION_URL_UTF16_UNITS ||
            raw.indexOfAny(charArrayOf('\n', '\r')) >= 0
        ) return null
        val parsed = raw.toUri()
        val scheme = parsed.scheme?.lowercase()
        return when {
            (scheme == "sn" && parsed.host == "subscription") ||
                (scheme == "clash" && parsed.host == "install-config") -> parsed
            scheme == "https" && !parsed.host.isNullOrBlank() &&
                parsed.userInfo.isNullOrEmpty() -> {
                Uri.Builder()
                    .scheme("sn")
                    .authority("subscription")
                    .appendQueryParameter("url", raw)
                    .build()
            }

            else -> null
        }
    }
}
