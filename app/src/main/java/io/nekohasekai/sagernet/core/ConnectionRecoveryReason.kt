package io.nekohasekai.sagernet.core

import androidx.annotation.StringRes
import io.nekohasekai.sagernet.R

/**
 * A small, non-sensitive explanation for failures that Android cannot recover without the user.
 *
 * Keep this separate from the technical connection error: this value is persisted across process
 * death so the foreground UI can offer one explicit retry without storing an exception, URL, or
 * credential in the preference database.
 */
enum class ConnectionRecoveryReason(@StringRes val messageResId: Int) {
    VPN_PERMISSION_REQUIRED(R.string.vpn_recovery_permission_required),
    SERVICE_START_FAILED(R.string.vpn_recovery_start_failed),
    BINDER_RECOVERY_FAILED(R.string.vpn_recovery_binder_failed),
    BOOT_RESTORE_FAILED(R.string.vpn_recovery_boot_failed),
    ;

    val persistedValue: String get() = name

    companion object {
        fun fromPersisted(value: String?): ConnectionRecoveryReason? =
            entries.firstOrNull { it.persistedValue == value }
    }
}
