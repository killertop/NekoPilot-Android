package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app

object Theme {

    fun apply(context: Context) {
        context.setTheme(getTheme())
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme())
    }

    fun getTheme(): Int {
        return R.style.Theme_SagerNet
    }

    fun getDialogTheme(): Int {
        return R.style.Theme_SagerNet_Dialog
    }

    fun usingNightMode(): Boolean =
        (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun applyNightTheme() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

}
