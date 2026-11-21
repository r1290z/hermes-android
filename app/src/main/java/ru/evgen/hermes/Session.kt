package ru.evgen.hermes

import org.json.JSONObject

/** One row of `GET /api/sessions`. Only the fields the list actually draws are
 *  kept: the endpoint returns 60+ columns, and most of the payload is system
 *  prompts and token counters a phone list has no use for. */
data class Session(
    val id: String,
    val title: String,
    val preview: String,
    val lastActive: Long,
    val pinned: Boolean,
    val archived: Boolean,
    val messageCount: Int,
    val model: String,
    val source: String,
    /** Prompt tokens the last turn carried — what the status strip shows as
     *  "context used". Absent on a session that never ran a turn. */
    val inputTokens: Long = 0
) {
    /** What the search box matches against: the title is often absent or just
     *  "Новый сеанс", the preview is what the user actually remembers. */
    val haystack: String by lazy { "$title $preview $model".lowercase() }

    companion object {
        fun from(json: JSONObject, includeArchived: Boolean = false): Session? {
            val id = json.optString("id").ifEmpty { return null }
            // Archived and hidden rows are not in the app's sidebar either; a
            // list that shows them is a list the user cannot recognise. Only
            // the archive screen opts in via includeArchived.
            if (!includeArchived) {
                if (json.optBoolean("archived", false)) return null
                if (json.optBoolean("hidden", false)) return null
            }

            val preview = json.optString("preview").replace('\n', ' ').trim()
            // `title` is JSON null on a session that never got a summary — the
            // server sends null, not "", so optString alone yields "null".
            val rawTitle = if (json.isNull("title")) "" else json.optString("title").trim()
            val title = rawTitle.ifEmpty {
                preview.take(60).ifEmpty { "Без названия" }
            }

            val last = json.optDouble("last_active", 0.0)
            val started = json.optDouble("started_at", 0.0)
            return Session(
                id = id,
                title = title,
                preview = if (preview == title) "" else preview,
                // last_active is absent on a session that never got a reply;
                // started_at is the only thing left to sort by.
                lastActive = ((if (last > 0) last else started) * 1000).toLong(),
                pinned = json.optBoolean("pinned", false),
                archived = json.optBoolean("archived", false),
                messageCount = json.optInt("message_count", 0),
                model = json.optString("model").substringAfter('/'),
                source = json.optString("source"),
                inputTokens = json.optLong("input_tokens", 0L)
            )
        }
    }
}
