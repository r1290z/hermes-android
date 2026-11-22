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
    /** `var`: the list updates it from the activity poll so the unread dot can
     *  appear without a full reload. */
    var messageCount: Int,
    val model: String,
    val source: String,
    /** The sidebar dot: the server tracks it per session; the app clears it
     *  with the same fenced PATCH every other session edit uses. `var` only
     *  for the optimistic clear on tap — a failed PATCH is corrected by the
     *  next listing. */
    var unread: Boolean = false,
    /** A turn is running in this session right now. Taken from the server's
     *  `is_active` + a fresh `last_activity_description`, or from the local
     *  registry for turns this phone started. */
    var running: Boolean = false,
    /** What the agent says it is doing ("receiving stream response"), shown as
     *  the second line while [running]; empty otherwise. */
    var activity: String = "",
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
            // A running turn is: the session has not ended, the agent stamped what
            // it is doing, and that stamp is recent. `is_active` alone is useless
            // here — the server counts a session as active for five minutes after
            // its last message, so every chat used in the last five minutes would
            // pulse.
            val activity = if (json.isNull("last_activity_description")) ""
            else json.optString("last_activity_description").trim()
            val activityAt = json.optDouble("last_activity_at", 0.0)
            val fresh = activityAt > 0 && System.currentTimeMillis() / 1000.0 - activityAt < 120
            val running = json.optBoolean("is_active", false) && activity.isNotEmpty() && fresh
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
                unread = json.optBoolean("unread", false),
                running = running,
                activity = if (running) activity else "",
                inputTokens = json.optLong("input_tokens", 0L)
            )
        }
    }
}
