package com.samluiz.gyst.desktop

import com.samluiz.gyst.logging.AppLogEntry
import com.samluiz.gyst.logging.AppLogSink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class DesktopFileLogSink(
    private val logPath: Path,
) : AppLogSink {
    init {
        Files.createDirectories(logPath.parent)
    }

    override fun log(entry: AppLogEntry) {
        val line = buildString {
            append(entry.timestampIso)
            append(" [")
            append(entry.level.name)
            append("] ")
            append(entry.tag)
            append(": ")
            append(entry.message)
            entry.throwable?.let {
                append(" | ")
                append(it::class.simpleName ?: "Throwable")
                append(": ")
                append(it.message ?: "no-message")
            }
            appendLine()
            entry.throwable?.stackTraceToString()?.let { appendLine(it) }
        }
        Files.writeString(
            logPath,
            line,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }
}
