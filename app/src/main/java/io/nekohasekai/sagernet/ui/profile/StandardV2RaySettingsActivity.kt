package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.isVLESSProfile
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type
import moe.matsuri.nb4a.ui.SimpleMenuPreference

abstract class StandardV2RaySettingsActivity : ProfileSettingsActivity<StandardV2RayBean>() {

    var tmpBean: StandardV2RayBean? = null

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val uuid = pbm.add(PreferenceBinding(Type.Text, "uuid"))
    private val username = pbm.add(PreferenceBinding(Type.Text, "username"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val alterId = pbm.add(PreferenceBinding(Type.TextToInt, "alterId"))
    private val encryption = pbm.add(PreferenceBinding(Type.Text, "encryption"))
    private val type = pbm.add(PreferenceBinding(Type.Text, "type"))
    private val host = pbm.add(PreferenceBinding(Type.Text, "host"))
    private val path = pbm.add(PreferenceBinding(Type.Text, "path"))
    private val packetEncoding = pbm.add(PreferenceBinding(Type.TextToInt, "packetEncoding"))
    private val wsMaxEarlyData = pbm.add(PreferenceBinding(Type.TextToInt, "wsMaxEarlyData"))
    private val earlyDataHeaderName = pbm.add(PreferenceBinding(Type.Text, "earlyDataHeaderName"))
    private val security = pbm.add(PreferenceBinding(Type.Text, "security"))
    private val sni = pbm.add(PreferenceBinding(Type.Text, "sni"))
    private val alpn = pbm.add(PreferenceBinding(Type.Text, "alpn"))
    private val certificates = pbm.add(PreferenceBinding(Type.Text, "certificates"))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, "allowInsecure"))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, "utlsFingerprint"))
    private val realityPubKey = pbm.add(PreferenceBinding(Type.Text, "realityPubKey"))
    private val realityShortId = pbm.add(PreferenceBinding(Type.Text, "realityShortId"))

    private val enableECH = pbm.add(PreferenceBinding(Type.Bool, "enableECH"))
    private val echConfig = pbm.add(PreferenceBinding(Type.Text, "echConfig"))

    private val enableMux = pbm.add(PreferenceBinding(Type.Bool, "enableMux"))
    private val muxPadding = pbm.add(PreferenceBinding(Type.Bool, "muxPadding"))
    private val muxType = pbm.add(PreferenceBinding(Type.TextToInt, "muxType"))
    private val muxConcurrency = pbm.add(PreferenceBinding(Type.TextToInt, "muxConcurrency"))

    override fun StandardV2RayBean.init() {
        if (this is TrojanBean) {
            this@StandardV2RaySettingsActivity.uuid.fieldName = "password"
            this@StandardV2RaySettingsActivity.password.disable = true
        }

        tmpBean = this // copy bean
        pbm.writeToCacheAll(this)
    }

    override fun StandardV2RayBean.serialize() {
        pbm.fromCacheAll(this)
    }

    private lateinit var securityCategory: PreferenceCategory
    private lateinit var tlsCamouflageCategory: PreferenceCategory
    private lateinit var wsCategory: PreferenceCategory
    private lateinit var echCategory: PreferenceCategory
    private lateinit var muxCategory: PreferenceCategory
    private lateinit var showAdvancedPreference: SwitchPreference

    private var isHttpProfile = false
    private var isVmessProfile = false
    private var isVlessProfile = false
    private var showAdvanced = false
    private var currentNetwork = "tcp"
    private var currentSecurity = "none"
    private var muxEnabled = false
    private var echEnabled = false

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.standard_v2ray_preferences)
        pbm.setPreferenceFragment(this)
        securityCategory = findPreference(Key.SERVER_SECURITY_CATEGORY)!!
        tlsCamouflageCategory = findPreference(Key.SERVER_TLS_CAMOUFLAGE_CATEGORY)!!
        echCategory = findPreference(Key.SERVER_ECH_CATEORY)!!
        wsCategory = findPreference(Key.SERVER_WS_CATEGORY)!!
        muxCategory = findPreference(Key.SERVER_MUX_CATEGORY)!!
        showAdvancedPreference = findPreference(Key.SERVER_SHOW_ADVANCED)!!

        // vmess/vless/http/trojan
        val profile = tmpBean
        isHttpProfile = profile is HttpBean
        isVmessProfile = profile is VMessBean && !profile.isVLESSProfile()
        isVlessProfile = profile?.isVLESSProfile() == true

        serverPort.preference.apply {
            this as EditTextPreference
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }

        alterId.preference.apply {
            this as EditTextPreference
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }

        uuid.preference.summaryProvider = PasswordSummaryProvider

        type.preference.isVisible = !isHttpProfile
        uuid.preference.isVisible = !isHttpProfile
        username.preference.isVisible = isHttpProfile
        password.preference.isVisible = isHttpProfile

        if (tmpBean is TrojanBean) {
            uuid.preference.title = resources.getString(R.string.password)
        }

        encryption.preference.apply {
            this as SimpleMenuPreference
            if (isVlessProfile) {
                title = resources.getString(R.string.xtls_flow)
                setIcon(R.drawable.ic_baseline_stream_24)
                setEntries(R.array.xtls_flow_value)
                setEntryValues(R.array.xtls_flow_value)
                setEntrySummaries(R.array.xtls_flow_summary)
            } else {
                setEntries(R.array.vmess_encryption_value)
                setEntryValues(R.array.vmess_encryption_value)
            }
        }

        currentNetwork = type.readStringFromCache().ifBlank { "tcp" }
        currentSecurity = security.readStringFromCache().ifBlank { "none" }
        muxEnabled = enableMux.readBoolFromCache()
        echEnabled = enableECH.readBoolFromCache()

        showAdvancedPreference.setOnPreferenceChangeListener { _, newValue ->
            showAdvanced = newValue as Boolean
            refreshVisibility()
            true
        }

        enableMux.preference.setOnPreferenceChangeListener { _, newValue ->
            muxEnabled = newValue as Boolean
            refreshVisibility()
            true
        }

        enableECH.preference.setOnPreferenceChangeListener { _, newValue ->
            echEnabled = newValue as Boolean
            refreshVisibility()
            true
        }

        type.preference.apply {
            this as SimpleMenuPreference
            setOnPreferenceChangeListener { _, newValue ->
                updateView(newValue as String)
                true
            }
        }

        security.preference.apply {
            this as SimpleMenuPreference
            setOnPreferenceChangeListener { _, newValue ->
                updateTls(newValue as String)
                true
            }
        }

        updateView(currentNetwork)
        updateTls(currentSecurity)
    }

    private fun updateView(network: String) {
        currentNetwork = network
        host.preference.isVisible = false
        path.preference.isVisible = false

        when (network) {
            "tcp" -> {
                host.preference.setTitle(R.string.http_host)
                path.preference.setTitle(R.string.http_path)
            }

            "http" -> {
                host.preference.setTitle(R.string.http_host)
                path.preference.setTitle(R.string.http_path)
                host.preference.isVisible = true
                path.preference.isVisible = true
            }

            "ws" -> {
                host.preference.setTitle(R.string.ws_host)
                path.preference.setTitle(R.string.ws_path)
                host.preference.isVisible = true
                path.preference.isVisible = true
                wsCategory.isVisible = true
            }

            "grpc" -> {
                path.preference.setTitle(R.string.grpc_service_name)
                path.preference.isVisible = true
            }

            "httpupgrade" -> {
                host.preference.setTitle(R.string.http_upgrade_host)
                path.preference.setTitle(R.string.http_upgrade_path)
                host.preference.isVisible = true
                path.preference.isVisible = true
            }
        }
        refreshVisibility()
    }

    private fun updateTls(tls: String) {
        currentSecurity = tls
        refreshVisibility()
    }

    private fun refreshVisibility() {
        val isTls = "tls" in currentSecurity
        val flowConfigured = encryption.readStringFromCache().isNotBlank()
        val packetEncodingConfigured = packetEncoding.readStringToIntFromCache() != 0
        val wsAdvancedConfigured = wsMaxEarlyData.readStringToIntFromCache() != 0 ||
            earlyDataHeaderName.readStringFromCache().isNotBlank()
        val alpnConfigured = alpn.readStringFromCache().isNotBlank()
        val certificatesConfigured = certificates.readStringFromCache().isNotBlank()
        val allowInsecureEnabled = allowInsecure.readBoolFromCache()
        val fingerprintConfigured = utlsFingerprint.readStringFromCache().isNotBlank()
        val realityConfigured = realityPubKey.readStringFromCache().isNotBlank() ||
            realityShortId.readStringFromCache().isNotBlank()

        alterId.preference.isVisible = isVmessProfile &&
            (showAdvanced || alterId.readStringToIntFromCache() != 0)
        encryption.preference.isVisible = isVmessProfile ||
            (isVlessProfile && isTls && (showAdvanced || flowConfigured))
        packetEncoding.preference.isVisible = (isVmessProfile || isVlessProfile) &&
            (showAdvanced || packetEncodingConfigured)

        wsCategory.isVisible = currentNetwork == "ws" &&
            (showAdvanced || wsAdvancedConfigured)

        securityCategory.isVisible = isTls
        sni.preference.isVisible = isTls
        alpn.preference.isVisible = isTls && (showAdvanced || alpnConfigured)
        certificates.preference.isVisible = isTls && (showAdvanced || certificatesConfigured)
        allowInsecure.preference.isVisible = isTls && (showAdvanced || allowInsecureEnabled)

        tlsCamouflageCategory.isVisible = isTls &&
            (showAdvanced || fingerprintConfigured || realityConfigured)
        utlsFingerprint.preference.isVisible = isTls &&
            (showAdvanced || fingerprintConfigured)
        realityPubKey.preference.isVisible = isTls && (showAdvanced || realityConfigured)
        realityShortId.preference.isVisible = isTls && (showAdvanced || realityConfigured)

        muxCategory.isVisible = showAdvanced || muxEnabled
        muxType.preference.isVisible = muxEnabled
        muxConcurrency.preference.isVisible = muxEnabled
        muxPadding.preference.isVisible = muxEnabled

        echCategory.isVisible = isTls && (showAdvanced || echEnabled)
        echConfig.preference.isVisible = echEnabled
    }

}
