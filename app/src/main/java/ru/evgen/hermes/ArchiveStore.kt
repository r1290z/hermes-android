package ru.evgen.hermes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local archive registry.
 *
 * The server has NO surface that lists archived sessions: every listing
 * (REST `/api/sessions`, WS `session.list`) pins `archived = 0` in SQL —
 * `include_hidden` in REST only works together with a `title` lookup
 * (api_server.py `_handle_list_sessions`), and the WS live scope drops
 * closed/archived rows. But `GET /api/sessions/{id}` still answers for an
 * archived row (with `archived: true`), and `PATCH {"archived": false}`
 * brings it back. So the app remembers archived ids itself and re-fetches
 * each row on demand; a deleted session (404) drops out of the registry.
 */
object ArchiveStore {

    private const val PREFS = "archive"
    private const val KEY = "ids"

    fun add(context: Context, id: String, title: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = prefs.getString(KEY, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        for (i in 0 until arr.length()) if (arr.optString(i) == id) return
        arr.put(id)
        prefs.edit().putString(KEY, arr.toString())
            .putString("title.$id", title)
            .apply()
    }

    fun remove(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = prefs.getString(KEY, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return
        val kept = JSONArray()
        for (i in 0 until arr.length()) if (arr.optString(i) != id) kept.put(arr.optString(i))
        prefs.edit().putString(KEY, kept.toString()).remove("title.$id").apply()
    }

    fun ids(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = prefs.getString(KEY, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }

    /** Fallback title for a row the server no longer returns (deleted upstream). */
    fun title(context: Context, id: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("title.$id", "") ?: ""
}
