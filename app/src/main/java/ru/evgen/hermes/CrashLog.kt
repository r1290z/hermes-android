package ru.evgen.hermes

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A crash that survives the process.
 *
 * Every crash report so far arrived as "приложение вылетает" with nothing to look at:
 * the stack trace goes to logcat, and there is no adb cable. This writes it to the
 * app's own files and shows it in «Лог и падения», where the user can copy it — which
 * turns a guess into a fix.
 *
 * It does NOT swallow the crash: the previous handler still runs, so Android behaves
 * exactly as before (dialog, restart, whatever the platform decides).
 */
object CrashLog {

    private const val FILE = "crash.log"
    private const val LIMIT = 200_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date())
        val entry = buildString {
            append("── падение ").append(stamp).append(" · ").append(thread.name).append('\n')
            append(error.javaClass.name).append(": ").append(error.message ?: "").append('\n')
            append(stack).append('\n')
            // The protocol log up to the crash is what explains WHY, not just where.
            append("── протокол ──\n")
            append(Diag.tail(60)).append('\n')
        }
        val file = File(context.filesDir, FILE)
        // Keep it small: three crashes is plenty, and a dialog has to render it.
        val previous = runCatching { file.readText() }.getOrDefault("")
        val merged = (entry + "\n" + previous).take(LIMIT)
        runCatching { file.writeText(merged) }
        Diag.log("crash", "${error.javaClass.simpleName}: ${error.message}")
    }

    /** The stored crashes, newest first; empty when the app never crashed. */
    fun read(context: Context): String =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText().orEmpty() }
            .getOrDefault("")

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
