package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.core.view.isVisible
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.LayoutDashboardBinding
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher

class DashboardFragment : androidx.fragment.app.Fragment(R.layout.layout_dashboard) {

    private var _binding: LayoutDashboardBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = LayoutDashboardBinding.bind(view)

        binding.connectButton.setOnClickListener { mainActivity().toggleService() }
        binding.nodeCard.setOnClickListener {
            mainActivity().displayFragmentWithId(R.id.nav_configuration)
        }
        binding.settingsButton.setOnClickListener {
            mainActivity().displayFragmentWithId(R.id.nav_settings)
        }
        binding.trafficCard.setOnClickListener {
            if (DataStore.serviceState.connected) mainActivity().testConnection()
        }

        renderState(DataStore.serviceState)
        updateSpeed(0L, 0L)
        refreshProfile()
    }

    override fun onResume() {
        super.onResume()
        refreshProfile()
    }

    fun renderState(state: BaseService.State) {
        if (_binding == null) return
        val status = when (state) {
            BaseService.State.Connecting -> R.string.connecting
            BaseService.State.Connected -> R.string.vpn_connected
            BaseService.State.Stopping -> R.string.stopping
            else -> R.string.not_connected
        }
        binding.connectionStatus.setText(status)
        binding.connectLabel.setText(if (state.canStop) R.string.stop else R.string.connect)
        binding.connectButton.contentDescription = getString(
            if (state.canStop) R.string.stop else R.string.connect
        )
        binding.connectButton.isEnabled = state.canStop || state == BaseService.State.Stopped ||
            state == BaseService.State.Idle
        binding.connectButton.alpha = if (binding.connectButton.isEnabled) 1f else 0.65f
        binding.statusDot.alpha = if (state.connected) 1f else 0.35f
    }

    fun updateSpeed(txRate: Long, rxRate: Long) {
        if (_binding == null) return
        binding.uploadSpeed.text = getString(
            R.string.speed, Formatter.formatFileSize(requireContext(), txRate)
        )
        binding.downloadSpeed.text = getString(
            R.string.speed, Formatter.formatFileSize(requireContext(), rxRate)
        )
    }

    fun refreshProfile() {
        if (_binding == null) return
        val selectedId = DataStore.selectedProxy
        runOnLifecycleDispatcher {
            val profile = ProfileManager.getProfile(selectedId)
            onMainDispatcher {
                if (_binding == null) return@onMainDispatcher
                if (profile == null) {
                    binding.nodeName.setText(R.string.dashboard_no_node)
                    binding.nodeProtocol.setText(R.string.dashboard_choose_node)
                    binding.nodeDelay.isVisible = false
                } else {
                    binding.nodeName.text = profile.displayName()
                    binding.nodeProtocol.text = profile.displayType()
                    binding.nodeDelay.isVisible = profile.ping > 0
                    if (profile.ping > 0) binding.nodeDelay.text = "${profile.ping} ms"
                }
            }
        }
    }

    private fun mainActivity() = requireActivity() as MainActivity

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
