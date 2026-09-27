package com.ochakov.divemaster.service

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A few dozen lines of service lifecycle history (armed, stood down, dive
 * events, destroyed) persisted in the app's files dir, so that after a dive
 * day the probe screen can show *why* nothing was running at water entry —
 * our own idle stand-down or the OS killing the process (the latter shows up
 * in the process exit history the service also captures).
 */
object LifecycleLog {
    private const val FILE = "lifecycle.log"
    private const val MAX_LINES = 40
    private val FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    @Synchronized
    fun append(context: Context, line: String) {
        runCatching {
            val file = File(context.filesDir, FILE)
            val stamp = FMT.format(Instant.now().atZone(ZoneId.systemDefault()))
            val lines = (if (file.exists()) file.readLines() else emptyList()) + "$stamp $line"
            file.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
        }
    }

    /** Newest first. */
    fun read(context: Context): List<String> = runCatching {
        File(context.filesDir, FILE).takeIf { it.exists() }?.readLines()?.asReversed() ?: emptyList()
    }.getOrDefault(emptyList())
}
