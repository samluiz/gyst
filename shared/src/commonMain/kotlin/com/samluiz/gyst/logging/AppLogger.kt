package com.samluiz.gyst.logging

import kotlin.time.Clock

enum class AppLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class AppLogEntry(
    val timestampIso: String,
    val level: AppLogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
)

fun interface AppLogSink {
    fun log(entry: AppLogEntry)
}

object AppLogger {
    private var sinks: List<AppLogSink> = listOf(ConsoleSink)

    fun addSink(sink: AppLogSink) {
        if (sinks.contains(sink)) return
        sinks = sinks + sink
    }

    fun clearSinks(includeConsole: Boolean = true) {
        sinks = if (includeConsole) listOf(ConsoleSink) else emptyList()
    }

    fun d(tag: String, message: String, throwable: Throwable? = null) = log(AppLogLevel.DEBUG, tag, message, throwable)
    fun i(tag: String, message: String, throwable: Throwable? = null) = log(AppLogLevel.INFO, tag, message, throwable)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log(AppLogLevel.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(AppLogLevel.ERROR, tag, message, throwable)

    private fun log(level: AppLogLevel, tag: String, message: String, throwable: Throwable?) {
        val entry = AppLogEntry(
            timestampIso = Clock.System.now().toString(),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable,
        )
        val snapshot = sinks
        snapshot.forEach { sink ->
            runCatching { sink.log(entry) }
        }
    }

    private object ConsoleSink : AppLogSink {
        override fun log(entry: AppLogEntry) {
            val suffix = entry.throwable?.let { " | ${it::class.simpleName}: ${it.message}" }.orEmpty()
            println("${entry.timestampIso} [${entry.level}] ${entry.tag}: ${entry.message}$suffix")
            entry.throwable?.printStackTrace()
        }
    }
}
