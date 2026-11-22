package ru.evgen.hermes

/** One row of the server's slash catalog. */
data class SlashItem(
    val text: String,
    val meta: String,
    /** "command" for built-ins, "skill" for a skill invocation. */
    val kind: String
)

/**
 * Slash commands on a phone.
 *
 * The catalog comes from the gateway (`complete.slash`), so nothing is hardcoded —
 * but three names are intercepted locally, because on a phone the server's meaning
 * is not the one a thumb expects:
 *
 *  - `/stop` on the server kills *background processes*; the stop button in this app
 *    interrupts the running turn. While a turn streams, `/stop` must mean the turn —
 *    otherwise the user stops their build and the answer keeps coming.
 *  - `/new` and `/reset` create a session server-side and leave the screen on the old
 *    one; the app has its own new-session path (draft saving, staged attachments).
 *
 * Everything else is forwarded verbatim to `slash.exec`.
 */
object Slash {

    /** A command typed into the composer: name without the slash, and the rest. */
    data class Parsed(val name: String, val args: String) {
        val isCommand: Boolean get() = name.isNotEmpty()
    }

    const val NEW = "new"
    const val RESET = "reset"
    const val CLEAR = "clear"
    const val STOP = "stop"

    /** Names handled inside the app instead of on the server. */
    val LOCAL = setOf(NEW, RESET, CLEAR)

    /** Parses `/title Мой сеанс` → ("title", "Мой сеанс"). Null when not a command. */
    fun parse(input: String): Parsed? {
        val trimmed = input.trimStart()
        if (!trimmed.startsWith("/")) return null
        val body = trimmed.removePrefix("/")
        val space = body.indexOfFirst { it.isWhitespace() }
        val name = (if (space < 0) body else body.substring(0, space)).lowercase()
        if (name.isEmpty()) return null
        val args = if (space < 0) "" else body.substring(space).trim()
        return Parsed(name, args)
    }

    /**
     * Whether the catalog says this command takes arguments. Its description carries
     * "(использование: …)" for those, which is the only signal the server gives.
     */
    fun takesArgs(item: SlashItem): Boolean {
        val meta = item.meta.lowercase()
        return meta.contains("использование") || meta.contains("usage") ||
            meta.contains("(") && meta.contains("[")
    }

    /** The palette query for a composer text, or null when the palette must hide. */
    fun paletteQuery(input: String): String? {
        val trimmed = input.trimStart()
        if (!trimmed.startsWith("/")) return null
        // A space ends the command name: from there on the user is typing arguments,
        // and a palette that keeps filtering would hide the field.
        if (trimmed.any { it.isWhitespace() }) return null
        return trimmed
    }

    /** Commands whose effect shows up in the transcript itself. */
    private val REFRESHING = setOf(
        "compress", "compact", "rollback", "undo", "retry", "branch", "fork", "new", "reset"
    )

    fun refreshesHistory(command: String): Boolean {
        val name = parse(command)?.name ?: return false
        return name in REFRESHING
    }

    /**
     * What `/stop` should do right now. The server's `/stop` is about background
     * processes, so it is only forwarded when no turn is running.
     */
    fun stopMeansTurn(streaming: Boolean): Boolean = streaming
}
