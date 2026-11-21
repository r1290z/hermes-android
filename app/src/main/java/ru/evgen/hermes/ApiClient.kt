package ru.evgen.hermes

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's own client for the Hermes API.
 *
 * There is no WebView under this: the chat is rendered natively, so everything
 * goes through OkHttp. Authentication is the same dashboard login the browser
 * uses — `POST /auth/password-login` returns cookies, and the cookies are the
 * credential. No API key ships in the APK and nothing new is exposed on the
 * server.
 *
 * Two transports, because the server has two:
 *  - REST for reads and session edits (`/api/sessions`, `/messages`, PATCH,
 *    DELETE with the revision fence).
 *  - A WebSocket at `/api/ws` for turns. Sending a message is JSON-RPC over
 *    that socket (`session.resume` → `prompt.submit`) and the answer streams
 *    back as events: `message.delta`, `thinking.delta`, `tool.start`,
 *    `tool.complete`, `message.complete`. The REST API has no chat endpoint at
 *    all — `POST /api/sessions/{id}/chat/stream` answers 405.
 *
 * Deliberate choices:
 *  - `followRedirects(false)`: an expired cookie makes nginx answer 302 to
 *    /login, and following it would turn every API call into HTML.
 *  - cookies live in SharedPreferences, not in OkHttp's memory jar: Android
 *    kills the process freely and a lost cookie means a login screen in the
 *    middle of reading an answer.
 */
class ApiClient(private val context: Context) {

    private val prefs = context.getSharedPreferences("hermes_api", Context.MODE_PRIVATE)

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val editor = prefs.edit()
            for (cookie in cookies) {
                editor.putString("cookie:" + cookie.name, cookie.toString())
            }
            editor.apply()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val out = mutableListOf<Cookie>()
            for ((key, value) in prefs.all) {
                if (!key.startsWith("cookie:") || value !is String) continue
                Cookie.parse(url, value)?.let(out::add)
            }
            return out
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // the gateway socket is long-lived
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    val base: String get() = prefs.getString(KEY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE

    fun setBase(url: String) {
        prefs.edit().putString(KEY_BASE, url.trimEnd('/')).apply()
    }

    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_LOGGED_IN, false)

    fun forget() {
        closeSocket()
        val editor = prefs.edit()
        for (key in prefs.all.keys) {
            if (key.startsWith("cookie:") || key == KEY_LOGGED_IN) editor.remove(key)
        }
        editor.apply()
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    /** Returns null on success, or a human-readable reason. */
    fun login(username: String, password: String): String? {
        val body = JSONObject()
            .put("provider", "basic")
            .put("username", username)
            .put("password", password)
            .toString()
        val request = Request.Builder()
            .url("$base/auth/password-login")
            .post(body.toRequestBody(JSON))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code in 200..299) {
                    prefs.edit().putBoolean(KEY_LOGGED_IN, true).apply()
                    null
                } else if (response.code == 401 || response.code == 403) {
                    "Неверный логин или пароль"
                } else {
                    "Сервер ответил ${response.code}"
                }
            }
        } catch (e: IOException) {
            "Нет связи с сервером"
        }
    }

    // ── Sessions (REST) ──────────────────────────────────────────────────────

    fun sessions(limit: Int = 100): List<Session> {
        val response = get("/api/sessions?limit=$limit") ?: return emptyList()
        val root = runCatching { JSONObject(response) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("sessions") ?: root.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<Session>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { Session.from(it)?.let(out::add) }
        }
        return out
    }

    fun renameSession(id: String, title: String): Boolean =
        mutate("PATCH", id, JSONObject().put("title", title).toString())

    fun setPinned(id: String, pinned: Boolean): Boolean =
        mutate("PATCH", id, JSONObject().put("pinned", pinned).toString())

    fun deleteSession(id: String): Boolean = mutate("DELETE", id, null)

    /** Writes are fenced: the server refuses a mutation without the counters it
     *  last committed, and those live in `/mutation-snapshot`, not in the list. */
    private fun mutate(method: String, id: String, body: String?): Boolean {
        val snapshotRaw = get("/api/sessions/$id/mutation-snapshot") ?: return false
        val snapshot = runCatching { JSONObject(snapshotRaw) }.getOrNull() ?: return false
        val requestId = "apk-" + System.currentTimeMillis() + "-" + (0..999).random()
        val revision = snapshot.optInt("runtime_revision", 0)
        val generation = if (snapshot.isNull("runtime_generation")) null
        else snapshot.optInt("runtime_generation")

        val builder = Request.Builder().url("$base/api/sessions/$id")
        return try {
            val request = if (method == "DELETE") {
                val url = "$base/api/sessions/$id?request_id=$requestId&expected_revision=$revision" +
                    (generation?.let { "&expected_generation=$it" } ?: "")
                builder.url(url).delete().build()
            } else {
                val payload = JSONObject(body ?: "{}")
                    .put("request_id", requestId)
                    .put("expected_revision", revision)
                generation?.let { payload.put("expected_generation", it) }
                builder.method(method, payload.toString().toRequestBody(JSON)).build()
            }
            client.newCall(request).execute().use { it.code in 200..299 }
        } catch (e: IOException) {
            false
        }
    }

    // ── Messages (REST) ──────────────────────────────────────────────────────

    fun messages(sessionId: String, limit: Int = 200): List<ChatMessage> {
        val raw = get("/api/sessions/$sessionId/messages?limit=$limit") ?: return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("messages") ?: return emptyList()
        val out = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("compacted", false)) continue
            ChatMessage.from(o)?.let(out::add)
        }
        return out
    }

    // ── Gateway socket ───────────────────────────────────────────────────────

    private val main = Handler(Looper.getMainLooper())
    private val rpcIds = AtomicInteger(1)
    private val waiters = ConcurrentHashMap<Int, RpcWaiter>()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var openLatch: CountDownLatch? = null
    @Volatile private var sink: ((ChatEvent) -> Unit)? = null

    /** Only the attached session's events reach the UI: one socket carries the
     *  traffic of every session this app has touched. */
    @Volatile private var attached: String? = null

    private class RpcWaiter {
        val latch = CountDownLatch(1)
        @Volatile var frame: JSONObject? = null
        fun complete(f: JSONObject) {
            frame = f
            latch.countDown()
        }
        fun fail() = latch.countDown()
    }

    private fun socketTicket(): String? {
        val request = Request.Builder()
            .url("$base/api/webapp/socket-ticket?socket=gateway")
            .post("{}".toRequestBody(JSON))
            .build()
        val raw = execute(request) ?: return null
        return runCatching { JSONObject(raw).optString("ticket").ifEmpty { null } }.getOrNull()
    }

    private fun ensureSocket(timeoutMs: Long = 20000): Boolean {
        if (connected && socket != null) return true
        synchronized(this) {
            if (connected && socket != null) return true
            val ticket = socketTicket() ?: return false
            val scheme = if (base.startsWith("https://")) "wss://" else "ws://"
            val host = base.removePrefix("https://").removePrefix("http://").trimEnd('/')
            val url = scheme + host + "/api/ws?ticket=" +
                URLEncoder.encode(ticket, "UTF-8") + "&native_dial=0"
            val latch = CountDownLatch(1)
            openLatch = latch
            socket?.cancel()
            socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected = true
            openLatch?.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (frame.optString("method") == "event") {
                frame.optJSONObject("params")?.let(::handleEvent)
                return
            }
            val id = frame.optInt("id", -1)
            waiters.remove(id)?.complete(frame)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected = false
            openLatch?.countDown()
            for (waiter in waiters.values) waiter.fail()
            waiters.clear()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
            openLatch?.countDown()
        }
    }

    private fun rpc(method: String, params: JSONObject, timeoutMs: Long = 25000): JSONObject? {
        if (!ensureSocket()) return null
        val ws = socket ?: return null
        val id = rpcIds.getAndIncrement()
        val waiter = RpcWaiter()
        waiters[id] = waiter
        val frame = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        if (!ws.send(frame.toString())) {
            waiters.remove(id)
            return null
        }
        if (!waiter.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            waiters.remove(id)
            return null
        }
        val reply = waiter.frame ?: return null
        if (reply.has("error")) {
            val err = reply.optJSONObject("error")
            error = err?.optString("message") ?: "Ошибка шлюза"
            return null
        }
        return reply.optJSONObject("result")
    }

    /** Last RPC error text, for the caller's toast. */
    @Volatile var error: String? = null

    private fun handleEvent(params: JSONObject) {
        val type = params.optString("type")
        val sid = params.optString("session_id")
        val bound = attached
        if (bound != null && sid.isNotEmpty() && sid != bound) return
        val payload = params.optJSONObject("payload") ?: JSONObject()
        val event = when (type) {
            "message.delta" -> payload.optString("text").takeIf { it.isNotEmpty() }?.let(ChatEvent::Delta)
            "thinking.delta" -> payload.optString("text").takeIf { it.isNotEmpty() }?.let(ChatEvent::Reasoning)
            "tool.start" -> ChatEvent.ToolStarted(
                payload.optString("name"),
                payload.optString("context"),
                payload.optJSONObject("args")?.toString().orEmpty()
            )
            "tool.complete" -> ChatEvent.ToolFinished(
                payload.optString("name"),
                payload.optString("context"),
                payload.optBoolean("is_error")
            )
            "agent.print" -> payload.optString("text").takeIf { it.isNotBlank() }
                ?.let { ChatEvent.Commentary(it) }
            "status.update" -> payload.optString("text").takeIf { it.isNotBlank() }
                ?.let { ChatEvent.Commentary(it) }
            "message.complete" -> ChatEvent.Completed(payload.optString("text"))
            else -> null
        }
        event?.let { e ->
            val target = sink ?: return
            main.post { target(e) }
            if (e is ChatEvent.Completed) main.post { target(ChatEvent.Done(null)) }
        }
    }

    fun closeSocket() {
        connected = false
        attached = null
        sink = null
        socket?.close(1000, "bye")
        socket = null
    }

    // ── Turns ────────────────────────────────────────────────────────────────

    /** Creates a session and returns its id. `source` must be one the gateway
     *  accepts (`cli|tui|gui|acp|tool|oneshot`) — anything else is a 4001. */
    fun createSession(): String? {
        error = null
        val result = rpc(
            "session.create",
            JSONObject()
                .put("request_id", "apk-" + System.currentTimeMillis())
                .put("source", "gui"),
            40000
        ) ?: return null
        val id = result.optString("session_id").ifEmpty { null } ?: return null
        attached = id
        return id
    }

    /**
     * Runs one turn. `onEvent` fires on the main thread.
     *
     * Blocks until the gateway admits the prompt; the answer arrives later on
     * the socket, so the caller's coroutine can return immediately after.
     */
    fun streamTurn(sessionId: String, message: String, onEvent: (ChatEvent) -> Unit) {
        error = null
        sink = onEvent
        attached = sessionId
        val resumed = rpc(
            "session.resume",
            JSONObject().put("session_id", sessionId).put("omit_messages", true),
            40000
        )
        if (resumed == null) {
            val why = error ?: "Не удалось открыть сеанс"
            main.post { onEvent(ChatEvent.Error(why)) }
            return
        }
        val submitted = rpc(
            "prompt.submit",
            JSONObject()
                .put("session_id", sessionId)
                .put("text", message)
                .put("submission_id", "apk-" + System.currentTimeMillis()),
            40000
        )
        if (submitted == null) {
            val why = error ?: "Сообщение не отправлено"
            main.post { onEvent(ChatEvent.Error(why)) }
        }
    }

    fun stopTurn(sessionId: String) {
        runCatching {
            rpc("session.interrupt", JSONObject().put("session_id", sessionId), 15000)
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /** null means "not logged in or unreachable" — the caller shows the login
     *  screen rather than an error dialog. */
    private fun get(path: String): String? =
        execute(Request.Builder().url(base + path).get().build())

    private fun execute(request: Request): String? = try {
        client.newCall(request).execute().use { response ->
            when {
                response.code == 302 || response.code == 401 -> null
                response.isSuccessful -> response.body?.string()
                else -> null
            }
        }
    } catch (e: IOException) {
        null
    }

    companion object {
        const val DEFAULT_BASE = "https://hermes.example.com"
        private const val KEY_BASE = "base_url"
        private const val KEY_LOGGED_IN = "logged_in"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Everything the chat screen reacts to, in one sealed hierarchy. */
sealed class ChatEvent {
    data class Started(val runId: String, val userText: String) : ChatEvent()
    data class Delta(val text: String) : ChatEvent()
    data class Commentary(val text: String) : ChatEvent()
    data class Reasoning(val text: String) : ChatEvent()
    data class ToolStarted(val name: String, val preview: String, val args: String) : ChatEvent()
    data class ToolFinished(val name: String, val preview: String, val failed: Boolean) : ChatEvent()
    data class Completed(val content: String) : ChatEvent()
    data class Error(val message: String) : ChatEvent()
    data class Done(val reason: String?) : ChatEvent()
}

/** One row of the transcript, already flattened for the list. */
data class ChatMessage(
    val role: String,
    val content: String,
    val toolName: String?,
    val reasoning: String?,
    val timestamp: Double
) {
    val isUser: Boolean get() = role == "user"
    val isTool: Boolean get() = role == "tool"

    companion object {
        fun from(json: JSONObject): ChatMessage? {
            val role = json.optString("role").ifEmpty { return null }
            val content = json.optString("content")
            val toolName = if (json.isNull("tool_name")) null else json.optString("tool_name").ifEmpty { null }
            val reasoning = if (json.isNull("reasoning")) null
            else json.optString("reasoning").ifEmpty { null }
            // An assistant row that only carries tool_calls has no text of its
            // own; the tools it called are separate rows right after it.
            if (role == "assistant" && content.isBlank() && reasoning == null) {
                val calls = json.optJSONArray("tool_calls")
                if (calls == null || calls.length() == 0) return null
            }
            return ChatMessage(
                role = role,
                content = content,
                toolName = toolName,
                reasoning = reasoning,
                timestamp = json.optDouble("timestamp", 0.0)
            )
        }
    }
}
