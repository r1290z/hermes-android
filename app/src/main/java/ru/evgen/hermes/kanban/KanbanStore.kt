package ru.evgen.hermes.kanban

import org.json.JSONObject

/**
 * In-memory board cache shared by the board and task screens.
 *
 * Refresh strategy: `GET /api/board` wholesale on open/resume (parsing 240 KB
 * of JSON beats replaying events), then the SSE stream marks the cache dirty
 * while the screen is visible — a dirty flag triggers one full refetch rather
 * than incremental event application.
 */
object KanbanStore {

    var board: JSONObject? = null
        private set

    var latestEventId: Long = 0

    val columnOrder: List<String>
        get() = board?.optJSONArray("column_order")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        } ?: emptyList()

    fun tasksOf(status: String): List<JSONObject> {
        val cols = board?.optJSONObject("columns") ?: return emptyList()
        val arr = cols.optJSONArray(status) ?: return emptyList()
        return (0 until arr.length()).map { arr.optJSONObject(it) }
    }

    fun task(id: String): JSONObject? {
        for (status in columnOrder) {
            tasksOf(status).firstOrNull { it.optString("id") == id }?.let { return it }
        }
        return null
    }

    /** Returns true when the board should be refetched. */
    fun applyFrame(frame: JSONObject): Boolean {
        val events = frame.optJSONArray("events") ?: return false
        if (events.length() == 0) return false
        val latest = frame.optLong("latest_event_id", 0L)
        if (latest > latestEventId) latestEventId = latest
        return true
    }

    fun replace(boardJson: JSONObject) {
        board = boardJson
        latestEventId = boardJson.optLong("latest_event_id", 0L)
    }

    fun clear() {
        board = null
        latestEventId = 0
    }
}
