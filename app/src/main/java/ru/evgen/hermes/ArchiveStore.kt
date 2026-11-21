package ru.evgen.hermes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local archive registry — a SUPPLEMENT, not the source of truth.
 *
 * The server listing is `GET /api/sessions?archived=only` (plus pinned rows,
 * which the caller filters out client-side). The registry only catches rows
 * archived from this phone that the server's `limit` window may cut off, so
 * a fresh install still sees the full archive from the server alone.
 * Registry ids the server no longer has (404/410 on `GET /api/sessions/{id}`)
 * are dropped; a network failure never removes an id.
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
