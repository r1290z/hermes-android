package ru.evgen.hermes

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * A tiny log of the protocol steps, shown in «…» → «Лог и падения».
 *
 * The phone is the only place the app actually runs — the build host has no
 * device and no emulator — so when something "just doesn't arrive" there is no
 * other way to see which layer died: ticket, socket, resume, submit, or events.
 *
 * Lines go to a file as well, because the interesting run is exactly the one that
 * ends with a crash: in memory the log died with the process, so a crash left
 * nothing to read and the running version had to be guessed from the bytecode of
 * a downloaded APK.
 *
 * Nothing secret belongs here: session ids, state names, Shizuku steps, counts.
 * Never tokens, cookies or message text.
 */
object Diag {

    private const val CAPACITY = 240
    /** On disk: several launches' worth, still small enough for a dialog. */
    private const val FILE_CAPACITY = 600
    private const val TAG = "HermesDiag"
    private const val FILE_NAME = "diag.log"
    private const val FILE_LIMIT = 200_000

    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val stamp = SimpleDateFormat("dd.MM HH:mm", Locale.US)

    private var file: File? = null
    private var initialized = false

    /** Brings back the tail of the previous runs and marks the new one. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        file = File(context.filesDir, FILE_NAME)
        val previous = try {
            file?.takeIf { it.exists() }?.readLines()?.takeLast(FILE_CAPACITY - CAPACITY)
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val separator = "── запуск " + stamp.format(Date()) + " ──"
        synchronized(lines) {
            previous.forEach { lines.addLast("  … $it") }
            lines.addLast(separator)
        }
        write(separator)
    }

    fun log(step: String, detail: String = "") {
        val line = clock.format(Date()) + "  " + step + (if (detail.isEmpty()) "" else "  " + detail)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > CAPACITY) lines.removeFirst()
        }
        Log.d(TAG, line)
        write(line)
    }

    private fun write(line: String) {
        val target = file ?: return
        try {
            target.appendText(line + "\n")
            // Trim from the front when the file outgrows its budget: an append log
            // that grows forever is a slow way to fill the data partition.
            if (target.length() > FILE_LIMIT) {
                val tail = target.readLines().takeLast(FILE_CAPACITY)
                target.writeText(tail.joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
            // A log that throws is worse than a log that drops its last line.
        }
    }

    fun dump(): List<String> = synchronized(lines) { lines.toList() }

    fun tail(n: Int): String = dump().takeLast(n).joinToString("\n")

    fun clear() {
        synchronized(lines) { lines.clear() }
        try {
            file?.writeText("")
        } catch (e: Exception) {
        }
    }
}
