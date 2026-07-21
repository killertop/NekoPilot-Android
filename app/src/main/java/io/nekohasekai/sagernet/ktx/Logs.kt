package io.nekohasekai.sagernet.ktx

import libcore.Libcore

object Logs {

    private fun mkTag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace[4].className.substringAfterLast(".")
    }

    // level int use logrus.go

    fun d(message: String) {
        Libcore.nekoLogPrintln("[Debug] [${mkTag()}] $message")
    }

    fun d(message: String, exception: Throwable) {
        d("$message\n${exception.stackTraceToString()}")
    }

    fun i(message: String) {
        Libcore.nekoLogPrintln("[Info] [${mkTag()}] $message")
    }

    fun i(message: String, exception: Throwable) {
        i("$message\n${exception.stackTraceToString()}")
    }

    fun w(message: String) {
        Libcore.nekoLogPrintln("[Warning] [${mkTag()}] $message")
    }

    fun w(message: String, exception: Throwable) {
        w("$message\n${exception.stackTraceToString()}")
    }

    fun w(exception: Throwable) {
        w(exception.stackTraceToString())
    }

    fun e(message: String) {
        Libcore.nekoLogPrintln("[Error] [${mkTag()}] $message")
    }

    fun e(message: String, exception: Throwable) {
        e("$message\n${exception.stackTraceToString()}")
    }

    fun e(exception: Throwable) {
        e(exception.stackTraceToString())
    }

}
