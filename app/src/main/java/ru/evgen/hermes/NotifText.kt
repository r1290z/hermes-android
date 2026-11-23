package ru.evgen.hermes

/**
 * Text shaping for notifications, kept out of the Android class so it can be tested.
 *
 * A notification gets one or two visible lines. Everything here exists because the
 * agent's own text does not fit that: it contains `MEDIA:` paths that mean nothing in
 * the shade, newlines that eat the second line, and answers long enough to fill the
 * whole panel.
 */
object NotifText {

    /** Collapsed-card text: no `MEDIA:` paths, no newlines, bounded with an ellipsis. */
    fun compact(text: String, limit: Int = 160): String {
        val cleaned = text.lines()
            // The path is the deliverable's address on the server; in the shade it is
            // four lines of noise and the user cannot open it from there anyway.
            .filter { !it.trimStart().startsWith("MEDIA:") }
            .joinToString(" ")
        val squashed = cleaned.trim().split(Regex("\\s+")).joinToString(" ")
        return if (squashed.length > limit) squashed.take(limit) + "…" else squashed
    }

    /**
     * Notification id for a session's answer card.
     *
     * Stable per session — otherwise every answer would add a card instead of
     * replacing its own — and offset from the fixed ids so it cannot collide with the
     * working card or the summary.
     */
    fun answerId(sessionId: String?): Int =
        // floorMod, not `%`: String.hashCode() is negative about half the time, and
        // Kotlin's `%` keeps the sign — the id then falls out of the 5000…5999 band
        // and can land on another notification's number.
        5_000 + Math.floorMod((sessionId ?: "app").hashCode(), 1_000)
}
