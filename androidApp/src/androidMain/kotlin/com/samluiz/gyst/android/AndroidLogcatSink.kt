package com.samluiz.gyst.android

import android.util.Log
import com.samluiz.gyst.logging.AppLogEntry
import com.samluiz.gyst.logging.AppLogLevel
import com.samluiz.gyst.logging.AppLogSink
import com.samluiz.gyst.logging.AppLogger

private object AndroidLogcatSink : AppLogSink {
    override fun log(entry: AppLogEntry) {
        val tag = "Gyst/${entry.tag}".take(23)
        when (entry.level) {
            AppLogLevel.DEBUG -> Log.d(tag, entry.message, entry.throwable)
            AppLogLevel.INFO -> Log.i(tag, entry.message, entry.throwable)
            AppLogLevel.WARN -> Log.w(tag, entry.message, entry.throwable)
            AppLogLevel.ERROR -> Log.e(tag, entry.message, entry.throwable)
        }
    }
}

fun installAndroidLogging() {
    AppLogger.addSink(AndroidLogcatSink)
}
