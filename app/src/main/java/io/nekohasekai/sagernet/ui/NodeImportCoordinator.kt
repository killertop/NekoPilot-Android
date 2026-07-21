package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.view.inputmethod.EditorInfo
import androidx.core.net.toUri
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.snackbar

/** Shared import flow for the node-source management screen. */
internal object NodeImportCoordinator {

    fun handle(fragment: ToolbarFragment, itemId: Int): Boolean = when (itemId) {
        R.id.action_scan_qr_code -> {
            fragment.startActivity(Intent(fragment.context, ScannerActivity::class.java))
            true
        }

        R.id.action_import_clipboard -> {
            importClipboard(fragment)
            true
        }

        R.id.action_import_subscription -> {
            showSubscriptionImportDialog(fragment)
            true
        }

        else -> false
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
                            R.id.nav_nodes,
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
                        R.id.nav_nodes,
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

    private fun showSubscriptionImportDialog(fragment: ToolbarFragment) {
        val content = fragment.layoutInflater.inflate(R.layout.layout_subscription_import, null)
        val inputLayout = content.findViewById<TextInputLayout>(R.id.subscription_link_layout)
        val input = content.findViewById<TextInputEditText>(R.id.subscription_link_input)
        val dialog = MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.import_subscription_link)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_import_confirm, null)
            .show()
        val importButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
        fun submit() {
            val subscriptionUri = subscriptionImportUri(input.text?.toString().orEmpty()) ?: run {
                inputLayout.error = fragment.getString(R.string.subscription_link_invalid)
                return
            }
            inputLayout.error = null
            dialog.dismiss()
            (fragment.activity as? MainActivity)?.requestSubscriptionImport(
                subscriptionUri,
                R.id.nav_nodes,
            )
        }
        importButton.isEnabled = false
        importButton.setOnClickListener { submit() }
        input.doAfterTextChanged {
            inputLayout.error = null
            importButton.isEnabled = !it.isNullOrBlank()
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && importButton.isEnabled) {
                submit()
                true
            } else {
                false
            }
        }
    }

    internal fun subscriptionImportUri(rawText: String): Uri? {
        val raw = rawText.trim()
        if (raw.isBlank() || raw.indexOfAny(charArrayOf('\n', '\r')) >= 0) return null
        val parsed = raw.toUri()
        val scheme = parsed.scheme?.lowercase()
        return when {
            (scheme == "sn" && parsed.host == "subscription") || scheme == "clash" -> parsed
            (scheme == "https" || scheme == "http") && !parsed.host.isNullOrBlank() -> {
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
