package ru.evgen.hermes

/**
 * Where a session came from.
 *
 * The server stores it in `source` and never localises it: `telegram`, `desktop`,
 * `gui`, `local`, `cli`, `tui`, `oneshot`, `api_server`. On a phone the list is a mix
 * of all of them, and without a mark a Telegram chat is indistinguishable from one
 * typed on the desktop — which matters the moment a reply lands somewhere else.
 *
 * The phone cannot claim a source of its own: it creates sessions with `source: "gui"`
 * on purpose, because that is what makes the gateway grant the client-surface
 * toolsets (`gateway/session_policy.py`). A private source name would silently cost
 * the app its tools, so the badge describes the channel, not the device.
 */
object Channel {

    enum class Kind { TELEGRAM, DESKTOP, CLI, BACKGROUND, API, NONE }

    /** Maps the gateway's `source` to a channel. Unknown values are not guessed:
     *  a wrong badge is worse than none. */
    fun kind(source: String?): Kind = when (source?.trim()?.lowercase()) {
        "telegram" -> Kind.TELEGRAM
        // `gui` is the web app, `desktop` the shell, `local` a local agent session —
        // all of them are the same "works at a keyboard" channel.
        "desktop", "gui", "local" -> Kind.DESKTOP
        "cli", "tui" -> Kind.CLI
        "oneshot" -> Kind.BACKGROUND
        "api_server", "api" -> Kind.API
        else -> Kind.NONE
    }

    /** Short badge text; empty for an unknown channel. */
    fun label(source: String?): String = when (kind(source)) {
        Kind.TELEGRAM -> "Telegram"
        Kind.DESKTOP -> "ПК"
        Kind.CLI -> "CLI"
        Kind.BACKGROUND -> "Фон"
        Kind.API -> "API"
        Kind.NONE -> ""
    }

    /** Longer phrasing for the row's accessibility label ("канал: Telegram"). */
    fun describe(source: String?): String = when (kind(source)) {
        Kind.TELEGRAM -> "из Telegram"
        Kind.DESKTOP -> "с компьютера"
        Kind.CLI -> "из терминала"
        Kind.BACKGROUND -> "фоновый запуск"
        Kind.API -> "через API"
        Kind.NONE -> ""
    }
}
