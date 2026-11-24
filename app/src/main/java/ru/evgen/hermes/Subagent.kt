package ru.evgen.hermes

import org.json.JSONObject

/**
 * A child agent working on behalf of the open session.
 *
 * The desktop has a status stack for these; the phone had nothing, so a delegation
 * that fanned out five workers looked like one line "выполняет инструмент". A user
 * watching a long job has two honest questions — what are they doing, and can I stop
 * the one that went the wrong way — and both are answerable from `subagent.list`.
 */
data class Subagent(
    val id: String,
    val goal: String,
    val model: String,
    /** running / waiting / done / failed — the gateway's own word, not translated. */
    val status: String,
    val toolCount: Int,
    val lastTool: String,
    /** Seconds since the child started; 0 when the server did not say. */
    val elapsed: Long,
    val acceptingSteer: Boolean,
    /** The child's own session, when it has one — its transcript can be opened. */
    val childSessionId: String?
) {
    /** A finished child stays in the list for a moment; the UI dims it. */
    val finished: Boolean get() = status.lowercase() in setOf("done", "completed", "failed", "error", "cancelled")

    companion object {
        fun from(json: JSONObject): Subagent? {
            val id = json.optString("subagent_id").ifEmpty { return null }
            val started = json.optDouble("started_at", 0.0)
            val elapsed = if (started > 0) {
                (System.currentTimeMillis() / 1000.0 - started).toLong().coerceAtLeast(0)
            } else 0L
            return Subagent(
                id = id,
                goal = json.optString("goal").trim(),
                model = json.optString("model").substringAfter('/'),
                status = json.optString("status").ifEmpty { "running" },
                toolCount = json.optInt("tool_count", 0),
                lastTool = json.optString("last_tool"),
                elapsed = elapsed,
                acceptingSteer = json.optBoolean("accepting_steer", false),
                childSessionId = if (json.isNull("child_session_id")) null
                else json.optString("child_session_id").ifEmpty { null }
            )
        }
    }
}

/** Human wording for a child's state. Unknown states are shown as sent: inventing
 *  "готово" for an unrecognised word would hide a failure. */
object AgentStatus {
    fun label(status: String): String = when (status.lowercase()) {
        "running" -> "работает"
        "waiting", "blocked" -> "ждёт"
        "done", "completed" -> "готово"
        "failed", "error" -> "ошибка"
        "cancelled", "interrupted" -> "остановлен"
        else -> status
    }
}
