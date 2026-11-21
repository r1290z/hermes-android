package ru.evgen.hermes

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * A tiny in-memory log of the protocol steps, shown in the status-bar dialog.
 *
 * The phone is the only place the app actually runs — the build host has no
 * device and no emulator — so when something "just doesn't arrive" there is no
 * other way to see which layer died: ticket, socket, resume, submit, or events.
 * Kept in memory only: it carries session ids and titles, nothing secret.
 */
object Diag {

    private const val CAPACITY = 240
    private const val TAG = "HermesDiag"

    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(step: String, detail: String = "") {
        val line = clock.format(Date()) + "  " + step + (if (detail.isEmpty()) "" else "  " + detail)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > CAPACITY) lines.removeFirst()
        }
        Log.d(TAG, line)
    }

    fun dump(): List<String> = synchronized(lines) { lines.toList() }

    fun tail(n: Int): String = dump().takeLast(n).joinToString("\n")

    fun clear() = synchronized(lines) { lines.clear() }
}
