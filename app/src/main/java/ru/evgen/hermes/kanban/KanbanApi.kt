package ru.evgen.hermes.kanban

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the kanban board service (the WebUI in front of kanban.db).
 *
 * Separate from the chat [ru.evgen.hermes.ApiClient] on purpose: that one has
 * readTimeout(0) for its socket, this one is plain request/response with 15 s
 * timeouts. The token is typed in by the user once and lives only in
 * SharedPreferences — never in resources, git or logs.
 */
class KanbanApi(context: Context) {

    private val app: Context = context.applicationContext
    private val prefs = forPrefs(context)

    /**
     * The board normally lives on the same server as the chat, so an unset value
     * follows [ru.evgen.hermes.ApiClient]'s base. A separate host (board behind
     * its own reverse proxy) is set explicitly from the token dialog.
     */
    var base: String
        get() = prefs.getString(PREF_BASE, null)?.takeIf { it.isNotBlank() }
            ?: ru.evgen.hermes.ApiClient.get(app).base
        set(value) { prefs.edit().putString(PREF_BASE, value.trim().trimEnd('/')).apply() }

    var token: String
        get() = prefs.getString(PREF_TOKEN, "") ?: ""
        set(value) { prefs.edit().putString(PREF_TOKEN, value).apply() }

    fun hasToken(): Boolean = token.isNotEmpty()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    class HttpError(val code: Int, message: String) : IOException("$code $message")

    // ── Requests ──────────────────────────────────────────────────────────────

    private fun get(path: String): JSONObject {
        val req = Request.Builder()
            .url(base + path)
            .header("X-Kanban-Token", token)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw HttpError(resp.code, text.take(200))
            return JSONObject(text)
        }
    }

    private fun getArray(path: String): JSONArray {
        val req = Request.Builder()
            .url(base + path)
            .header("X-Kanban-Token", token)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw HttpError(resp.code, text.take(200))
            return JSONArray(text)
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(base + path)
            .header("X-Kanban-Token", token)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw HttpError(resp.code, text.take(200))
            return if (text.isEmpty()) JSONObject() else JSONObject(text)
        }
    }

    fun stats(): JSONObject = get("/api/stats")

    fun board(): JSONObject = get("/api/board")

    fun task(id: String): JSONObject = get("/api/tasks/$id")

    fun runs(id: String): JSONArray = getArray("/api/tasks/$id/runs")

    fun comment(id: String, text: String): JSONObject =
        post("/api/tasks/$id/comments", JSONObject().put("body", text))

    fun createTask(title: String, body: String?, assignee: String?): JSONObject {
        val payload = JSONObject().put("title", title)
        if (!body.isNullOrBlank()) payload.put("body", body)
        if (!assignee.isNullOrBlank()) payload.put("assignee", assignee)
        return post("/api/tasks", payload)
    }

    /** One-shot events fetch (polling fallback for the SSE stream). */
    fun eventsSince(cursor: Long): JSONObject = get("/api/events?since=$cursor")

    fun complete(id: String): JSONObject = post("/api/tasks/$id/complete", JSONObject())

    fun block(id: String, reason: String?): JSONObject =
        post("/api/tasks/$id/block", JSONObject().put("reason", reason ?: ""))

    fun unblock(id: String): JSONObject = post("/api/tasks/$id/unblock", JSONObject())

    /**
     * Opens the SSE stream and feeds every `data:` line of `event: kanban`
     * frames to [onFrame]. Returns on stream end/error; the caller decides
     * whether to restart. Cancelled calls throw IOException on close.
     */
    fun streamEvents(since: Long, onFrame: (JSONObject) -> Unit) {
        val req = Request.Builder()
            .url("$base/api/events/stream?since=$since&interval=0.75")
            .header("X-Kanban-Token", token)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpError(resp.code, "")
            val source = resp.body?.source() ?: return
            val data = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                    line.isEmpty() && data.isNotEmpty() -> {
                        onFrame(JSONObject(data.toString()))
                        data.setLength(0)
                    }
                }
            }
        }
    }

    companion object {
        private const val PREF_BASE = "base"
        private const val PREF_TOKEN = "token"

        private fun forPrefs(context: Context) =
            context.applicationContext.getSharedPreferences("kanban", Context.MODE_PRIVATE)
    }
}
