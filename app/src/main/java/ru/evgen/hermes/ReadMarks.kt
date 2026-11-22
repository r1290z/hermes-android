package ru.evgen.hermes

import android.content.Context

/**
 * Which sessions the user has already looked at.
 *
 * The server has an `unread` column and the app PATCHes it, but on this gateway it
 * is never set to true — every row comes back `unread: false`, so a dot fed from
 * the API would never appear. The count-based marker below is what actually works:
 * a session is unread when the server reports more messages than were on screen
 * the last time the user was inside it.
 *
 * Message count rather than a timestamp on purpose: it is monotonic, it comes from
 * the server, and it cannot be thrown off by the phone clock or timezone.
 *
 * Rows seen for the first time are recorded as read, so the list does not light up
 * with every session ever created the moment this version is installed.
 */
object ReadMarks {

    private const val PREFS = "hermes_read_marks"
    private const val KEY_COUNT = "count:"
    private const val KEY_PENDING = "pending_read"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Messages this session had when it was last open. -1 = never opened. */
    fun readCount(context: Context, sessionId: String): Int =
        prefs(context).getInt(KEY_COUNT + sessionId, -1)

    fun isUnread(context: Context, session: Session): Boolean {
        val known = readCount(context, session.id)
        // Never seen: adopt the current count silently (see class comment).
        if (known < 0) {
            markRead(context, session.id, session.messageCount)
            return false
        }
        return session.messageCount > known
    }

    fun markRead(context: Context, sessionId: String, messageCount: Int) {
        prefs(context).edit().putInt(KEY_COUNT + sessionId, messageCount).apply()
    }

    /**
     * The chat screen says "this session was on screen"; the list, which owns the
     * fresh message count, turns that into a marker on its next load. Storing an id
     * instead of a count keeps the write cheap and avoids an extra request from the
     * chat just to learn a number the list already has.
     */
    fun noteOpened(context: Context, sessionId: String) {
        prefs(context).edit().putString(KEY_PENDING, sessionId).apply()
    }

    /** Consumes the pending note, if any. Returns the session id it named. */
    fun takePending(context: Context): String? {
        val p = prefs(context)
        val id = p.getString(KEY_PENDING, null) ?: return null
        p.edit().remove(KEY_PENDING).apply()
        return id
    }
}
