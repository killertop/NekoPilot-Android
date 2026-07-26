package io.nekohasekai.sagernet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.bg.VpnPolicyReloadCoordinator
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.PerAppProxyPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class PerAppPolicyCommitRequest(
    val policy: PerAppProxyPolicy,
    val markSetupDone: Boolean,
    val needsReconnect: Boolean,
    val finishAfterApply: Boolean,
)

internal sealed interface PerAppPolicyApplyState {
    data object Idle : PerAppPolicyApplyState
    data class Applying(val request: PerAppPolicyCommitRequest) : PerAppPolicyApplyState
    data class Succeeded(val request: PerAppPolicyCommitRequest) : PerAppPolicyApplyState
    data class Failed(
        val request: PerAppPolicyCommitRequest,
        val error: Throwable,
    ) : PerAppPolicyApplyState
}

/**
 * Owns one Apply transaction across configuration changes. Persistence and the reconnect request
 * finish in the retained ViewModel even when Android destroys and recreates the Activity.
 */
internal class PerAppPolicyApplyViewModel : ViewModel() {
    private val mutableState =
        MutableStateFlow<PerAppPolicyApplyState>(PerAppPolicyApplyState.Idle)
    val state = mutableState.asStateFlow()

    fun submit(request: PerAppPolicyCommitRequest) {
        if (mutableState.value is PerAppPolicyApplyState.Applying) return
        mutableState.value = PerAppPolicyApplyState.Applying(request)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                DataStore.savePerAppProxyPolicy(
                    enabled = request.policy.enabled,
                    serializedPackages = request.policy.serializedPackages,
                    markSetupDone = request.markSetupDone,
                )
                if (request.needsReconnect) VpnPolicyReloadCoordinator.request()
            }.onSuccess {
                mutableState.value = PerAppPolicyApplyState.Succeeded(request)
            }.onFailure { error ->
                mutableState.value = PerAppPolicyApplyState.Failed(request, error)
            }
        }
    }

    fun acknowledge(terminalState: PerAppPolicyApplyState) {
        if (mutableState.value === terminalState) {
            mutableState.value = PerAppPolicyApplyState.Idle
        }
    }
}
