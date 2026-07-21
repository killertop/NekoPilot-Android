package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.BuildConfig
import libcore.Libcore

object Logs {

    private const val TAG = "NekoPilot"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Libcore.nekoLogPrintln("[Debug] [$TAG] $message")
    }

    fun d(message: String, exception: Throwable) {
        d("$message\n${exception.stackTraceToString()}")
    }

    fun i(message: String) {
        if (BuildConfig.DEBUG) Libcore.nekoLogPrintln("[Info] [$TAG] $message")
    }

    fun i(message: String, exception: Throwable) {
        i("$message\n${exception.stackTraceToString()}")
    }

    fun w(message: String) {
        Libcore.nekoLogPrintln("[Warning] [$TAG] $message")
    }

    fun w(message: String, exception: Throwable) {
        w("$message\n${exception.stackTraceToString()}")
    }

    fun w(exception: Throwable) {
        w(exception.stackTraceToString())
    }

    fun e(message: String) {
        Libcore.nekoLogPrintln("[Error] [$TAG] $message")
    }

    fun e(message: String, exception: Throwable) {
        e("$message\n${exception.stackTraceToString()}")
    }

    fun e(exception: Throwable) {
        e(exception.stackTraceToString())
    }

}
