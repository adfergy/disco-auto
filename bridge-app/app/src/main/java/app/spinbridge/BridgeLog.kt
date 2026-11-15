// SPDX-License-Identifier: MIT
package app.spinbridge

import android.content.Context
import android.util.Log
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The session log: what the car sent, what we answered, and what went wrong. Kept in memory for
 * the screen and appended to a file per app start, because the first trips to the car are about
 * bringing back exactly this.
 */
object BridgeLog {
    private const val TAG = "DiscoAuto"
    private const val MAX_LINES = 5000

    private val lines = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var writer: Writer? = null

    @Volatile var file: File? = null
        private set

    /** Starts the log file for this run, once. Safe to call from every entry point. */
    @Synchronized
    fun attach(context: Context) {
        if (writer != null) return
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        try {
            directory.mkdirs()
            val name = "discoauto-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log"
            val target = File(directory, name)
            writer = target.bufferedWriter()
            file = target
            // Keep the newest twenty; a log is a few hundred kilobytes at most.
            directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
        } catch (failure: Exception) {
            Log.w(TAG, "no log file: ${failure.message}")
        }
        log("Disco Auto ${BuildInfo.VERSION} on Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})")
    }

    @Synchronized
    fun log(line: String) {
        val text = stamp.format(Date()) + " " + line
        lines.addLast(text)
        while (lines.size > MAX_LINES) lines.removeFirst()
        try {
            writer?.apply {
                write(text)
                write("\n")
                flush()
            }
        } catch (_: Exception) {
        }
        Log.i(TAG, line)
        listeners.forEach { it() }
    }

    @Synchronized
    fun snapshot(last: Int = MAX_LINES): List<String> = lines.toList().takeLast(last)

    @Synchronized
    fun clear() {
        lines.clear()
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
}

object BuildInfo {
    const val VERSION = "0.4.0"
}
