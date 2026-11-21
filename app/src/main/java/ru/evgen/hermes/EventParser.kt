package ru.evgen.hermes

import org.json.JSONObject

/**
 * Pure frame→ChatEvent mapping, no sockets, no state, no Android.
 *
 * Extracted from ApiClient.handleEvent so the protocol parse can be unit-tested
 * on the JVM (the build host has no device). handleEvent keeps only the session
 * filter and the dispatch into the event sink.
 *
 * `org.json`'s optString turns a JSON null into the literal "null", and the
 * gateway sends `text: null` in message.complete. Reading it naively put the
 * word "null" on screen where the answer should be. Every payload read goes
 * through [str] so a null can never become text.
 */
object EventParser {

    /**
     * Session filter: an event belongs to the client when it carries no
     * session_id, or the id is one of the ids this session is known under
     * (the stored row id and the live id the gateway answers resume with).
     * An empty bound set accepts everything (no turn is streaming yet).
     */
    fun belongsToSession(params: JSONObject, boundIds: Collection<String>): Boolean {
        if (boundIds.isEmpty()) return true
        val sid = params.optString("session_id")
        return sid.isEmpty() || sid in boundIds
    }

    /** A JSON string field, with a JSON null read as "" instead of the literal "null". */
    fun str(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key)

    /**
     * Maps one event payload to its ChatEvent. Returns null when the frame is
     * not a chat event (or carries no text) — the caller drops it silently.
     */
    fun parseEvent(type: String, payload: JSONObject): ChatEvent? = when (type) {
        "message.delta" -> str(payload, "text").takeIf { it.isNotEmpty() }?.let(ChatEvent::Delta)
        "thinking.delta" -> str(payload, "text").takeIf { it.isNotEmpty() }?.let(ChatEvent::Reasoning)
        "tool.start" -> ChatEvent.ToolStarted(
            str(payload, "name"),
            str(payload, "context"),
            payload.optJSONObject("args")?.toString().orEmpty()
        )
        "tool.complete" -> ChatEvent.ToolFinished(
            str(payload, "name"),
            str(payload, "context"),
            payload.optBoolean("is_error")
        )
        "agent.print" -> str(payload, "text").takeIf { it.isNotBlank() }
            ?.let { ChatEvent.Commentary(it) }
        "status.update" -> str(payload, "text").takeIf { it.isNotBlank() }
            ?.let { ChatEvent.Commentary(it) }
        "message.complete" -> ChatEvent.Completed(str(payload, "text"))
        else -> null
    }
}
