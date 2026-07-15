package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutWebviewBinding
import moe.matsuri.nb4a.utils.WebViewUtil

// Fragment必须有一个无参public的构造函数，否则在数据恢复的时候，会报crash

class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {

    companion object {
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
    }

    lateinit var mWebView: WebView
    private var webViewDestroyed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // layout
        toolbar.setTitle(R.string.menu_dashboard)
        toolbar.inflateMenu(R.menu.yacd_menu)
        toolbar.setOnMenuItemClickListener(this)

        val binding = LayoutWebviewBinding.bind(view)

        // webview
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        mWebView = binding.webview
        webViewDestroyed = false
        mWebView.settings.apply {
            domStorageEnabled = true
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mWebView.settings.safeBrowsingEnabled = true
        }
        mWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = request?.url?.let { !isAllowedDashboardUri(it) } ?: true

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                WebViewUtil.onReceivedError(view, request, error)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }
        loadDashboard(DataStore.yacdURL)
    }

    @SuppressLint("CheckResult")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_set_url -> {
                val view = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setText(DataStore.yacdURL)
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.set_panel_url)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val url = view.text.toString().trim()
                        if (isAllowedDashboardUri(Uri.parse(url))) {
                            DataStore.yacdURL = url
                            loadDashboard(url)
                        } else {
                            Toast.makeText(
                                requireContext(), R.string.invalid_dashboard_url, Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            R.id.close -> {
                destroyWebView()
            }
        }
        return true
    }

    private fun loadDashboard(url: String) {
        val uri = Uri.parse(url)
        if (isAllowedDashboardUri(uri)) {
            mWebView.loadUrl(url)
        } else {
            DataStore.resetYacdURL()
            mWebView.loadUrl(DataStore.yacdURL)
        }
    }

    private fun isAllowedDashboardUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return scheme == "https" || (scheme == "http" && host in LOOPBACK_HOSTS)
    }

    override fun onDestroyView() {
        destroyWebView()
        super.onDestroyView()
    }

    private fun destroyWebView() {
        if (!::mWebView.isInitialized || webViewDestroyed) return
        webViewDestroyed = true
        mWebView.stopLoading()
        mWebView.onPause()
        mWebView.clearHistory()
        mWebView.removeAllViews()
        mWebView.destroy()
    }
}
