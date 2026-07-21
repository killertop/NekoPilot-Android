package io.nekohasekai.sagernet.ktx

import android.util.Log
import io.nekohasekai.sagernet.BuildConfig

object Logs {

    private const val TAG = "NekoPilot"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun d(message: String, exception: Throwable) {
        d("$message\n${exception.stackTraceToString()}")
    }

    fun i(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    fun i(message: String, exception: Throwable) {
        i("$message\n${exception.stackTraceToString()}")
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }

    fun w(message: String, exception: Throwable) {
        w("$message\n${exception.stackTraceToString()}")
    }

    fun w(exception: Throwable) {
        w(exception.stackTraceToString())
    }

    fun e(message: String) {
        Log.e(TAG, message)
    }

    fun e(message: String, exception: Throwable) {
        e("$message\n${exception.stackTraceToString()}")
    }

    fun e(exception: Throwable) {
        e(exception.stackTraceToString())
    }

}
