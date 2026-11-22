package ru.evgen.hermes

import android.content.Context

/**
 * Sessions this phone has started a turn in and has not seen finish.
 *
 * The list already learns about running sessions from the server (`is_active` plus
 * a fresh `last_activity_description`), but that only arrives on the next poll. This
 * registry makes the row start pulsing the instant the user sends, before any
 * refresh, and it survives the chat screen being closed — which is exactly when the
 * user is looking at the list and wondering whether anything is happening.
 *
 * Entries expire on their own: a process killed mid-turn would otherwise leave a
 * row pulsing forever.
 */
object Running {

    private const val PREFS = "hermes_running"
    private const val KEY = "since:"
    /** Longer than any realistic answer; a stale entry past this is a lie. */
    private const val TTL_MS = 5 * 60 * 1000L

    fun started(context: Context, sessionId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY + sessionId, System.currentTimeMillis()).apply()
    }

    fun finished(context: Context, sessionId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY + sessionId).apply()
    }

    /** When this phone started the turn, or null if it did not. */
    fun since(context: Context, sessionId: String): Long? {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY + sessionId, -1L)
        return if (at > 0) at else null
    }

    /** Ids still believed to be running, with expired entries dropped as we go. */
    fun active(context: Context): Set<String> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val live = mutableSetOf<String>()
        val edit = p.edit()
        for ((key, value) in p.all) {
            if (!key.startsWith(KEY) || value !is Long) continue
            val id = key.removePrefix(KEY)
            if (now - value > TTL_MS) edit.remove(key) else live += id
        }
        edit.apply()
        return live
    }
}
