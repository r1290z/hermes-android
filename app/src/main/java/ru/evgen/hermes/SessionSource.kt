package ru.evgen.hermes

/**
 * Remembers which channel each session came from, for the few places that need it
 * outside the list: the answer notification draws a Telegram session as a chat
 * message rather than a system card.
 *
 * Filled from the session list — the only place the gateway's `source` arrives —
 * and deliberately in-memory: a stale channel on a notification is worse than none,
 * and after a restart the list is reloaded before any notification can fire.
 */
object SessionSource {

    private val sources = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun remember(id: String, source: String?) {
        if (source.isNullOrBlank()) sources.remove(id)
        else sources[id] = source
    }

    fun of(id: String?): String? = id?.let { sources[it] }

    fun kind(id: String?): Channel.Kind = Channel.kind(of(id))
}
