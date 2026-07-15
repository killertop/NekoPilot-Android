package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.core.view.isVisible
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.databinding.LayoutAboutBinding
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import libcore.Libcore

class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_about)

        val binding = LayoutAboutBinding.bind(view)
        binding.version.text = SagerNet.appVersionNameForDisplay
        binding.singboxVersion.text = Libcore.versionBox()
        binding.sourceCode.setOnClickListener {
            requireContext().launchCustomTab("https://github.com/MatsuriDayo/NekoBoxForAndroid")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager =
                requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
            binding.batterySettings.isVisible =
                !powerManager.isIgnoringBatteryOptimizations(requireContext().packageName)
            binding.batterySettings.setOnClickListener {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        } else {
            binding.batterySettings.isVisible = false
        }

        runOnDefaultDispatcher {
            val license = requireContext().assets.open("LICENSE").bufferedReader().use { it.readText() }
            onMainDispatcher { binding.license.text = license }
        }
    }
}
