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
 *
 * One instance per process, via [get]. Each instance owns a WebSocket, so an
 * activity-local client meant the session list and the open chat each held their
 * own socket to the same session: duplicate events, and `session.interrupt` or a
 * reconnect in one tearing down the other (three sockets were observed dying at
 * once in the field). The socket is process-wide state, so the client is too.
 */
class ApiClient private constructor(context: Context) {

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
        // `profile` scopes the listing to one profile's gateway; omitted for
        // the default (and for "all", which is the same thing on this server).
        val profileQuery = profile?.let { "&profile=$it" } ?: ""
        val response = get("/api/sessions?limit=$limit$profileQuery") ?: return emptyList()
        val root = runCatching { JSONObject(response) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("sessions") ?: root.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<Session>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { Session.from(it)?.let(out::add) }
        }
        return out
    }

    /** Archived rows come from the server listing: REST takes
     *  `?archived=only` (hermes_cli/web_routers/sessions.py), which also
     *  returns pinned rows — hence the client-side `archived` filter. The
     *  local registry (ArchiveStore) only supplements it for rows archived
     *  from this phone that the limit window may cut off; ids the server no
     *  longer has (404/410) are pruned, a network failure is NOT a prune. */
    /** Result of the archive fetch: `error` is non-null when the listing
     *  itself failed (offline / expired cookie) — then `sessions` is empty
     *  and the registry was NOT touched. */
    data class ArchivePage(val sessions: List<Session>, val error: String?)

    fun archivedSessions(context: android.content.Context): ArchivePage {
        val (code, body) = getWithStatus("/api/sessions?archived=only&limit=100")
        if (body == null) {
            // The listing failed — bail out before the registry loop so a
            // network failure never prunes (or slows down) anything.
            val err = if (code == 302 || code == 401) ARCHIVE_ERR_AUTH else ARCHIVE_ERR_OFFLINE
            return ArchivePage(emptyList(), err)
        }
        val out = sessionsFrom(body, includeArchived = true)
            .filter { it.archived }.toMutableList()
        val listed = out.map { it.id }.toHashSet()
        for (id in ArchiveStore.ids(context)) {
            if (id in listed) continue
            val (code2, json) = sessionRowStatus(id)
            if (code2 == 404 || code2 == 410) {
                ArchiveStore.remove(context, id)  // deleted upstream
                continue
            }
            val row = json?.let { Session.from(it, includeArchived = true) } ?: continue
            if (!row.archived) {
                // Session was restored elsewhere — its id no longer belongs
                // in the registry (otherwise every open re-fetches it).
                ArchiveStore.remove(context, id)
                continue
            }
            out += row
        }
        return ArchivePage(out.sortedByDescending { it.lastActive }, null)
    }

    /** `GET /api/sessions/{id}` — works for archived rows too (unlike every
     *  listing). null when the row is gone (404/410) or unreachable; the
     *  caller must NOT treat a network failure as "deleted". */
    fun sessionRow(id: String): JSONObject? =
        sessionRowStatus(id).second

    /** Same fetch, but reporting the HTTP code so the archive can tell a row
     *  deleted upstream (404/410 — prune) from a transient failure (keep). */
    fun sessionRowStatus(id: String): Pair<Int, JSONObject?> = try {
        val response = client.newCall(Request.Builder().url("$base/api/sessions/$id").get().build()).execute()
        response.use {
            val body = it.body?.string()
            when {
                it.code == 404 || it.code == 410 -> it.code to null
                it.isSuccessful && body != null ->
                    it.code to runCatching { JSONObject(body) }.getOrNull()?.takeIf { j -> j.has("id") }
                else -> it.code to null
            }
        }
    } catch (e: IOException) {
        -1 to null
    }

    private fun sessionsFrom(response: String?, includeArchived: Boolean = false): List<Session> {
        val root = response?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        val arr = root.optJSONArray("sessions") ?: root.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<Session>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { Session.from(it, includeArchived)?.let(out::add) }
        }
        return out
    }

    /** Sets the title from the first 60 chars of the answer when the session is
     *  still unnamed — otherwise every new session shows the same blank row.
     *  Returns the new title when it renamed, null when it left the name alone. */
    fun autoTitle(sessionId: String, answerText: String): String? {
        val current = get("/api/sessions/$sessionId")?.let {
            runCatching { JSONObject(it).optString("title") }.getOrNull()
        } ?: ""
        val named = current.trim().isNotEmpty() && current.trim() != "Новый сеанс"
        if (named) return null
        val text = answerText.replace('\n', ' ').trim().take(60).ifEmpty { return null }
        return if (renameSession(sessionId, text)) text else null
    }

    fun renameSession(id: String, title: String): Boolean =
        mutate("PATCH", id, JSONObject().put("title", title).toString())

    fun setPinned(id: String, pinned: Boolean): Boolean =
        mutate("PATCH", id, JSONObject().put("pinned", pinned).toString())

    fun setArchived(id: String, archived: Boolean): Boolean =
        mutate("PATCH", id, JSONObject().put("archived", archived).toString())

    /** Clears the sidebar dot. There is no POST /{id}/read on this gateway
     *  (405 on every method); the fenced session PATCH with `unread:false`
     *  is the supported write (verified: 200, operation "sidebar"). */
    fun markRead(id: String): Boolean =
        mutate("PATCH", id, JSONObject().put("unread", false).toString())

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

    /**
     * The last answer the server has stored — the fallback when the socket goes
     * quiet mid-turn.
     *
     * Reads the newest few rows and takes the last assistant one, so a long
     * session can never hand back its opening message as if it were the answer.
     * Compaction summaries are skipped: they are not what the user asked for.
     */
    fun latestAssistantText(sessionId: String): String {
        val recent = messages(sessionId, limit = 12)
        return recent.lastOrNull {
            !it.isUser && !it.isTool && !it.compacted && it.content.isNotBlank()
        }?.content.orEmpty()
    }

    // ── Profiles / models / per-session modes ────────────────────────────────

    /** The profile every new call goes to. Null = the gateway default.
     *  Persisted: the chosen profile must survive a process restart. */
    var profile: String?
        get() = prefs.getString(KEY_PROFILE, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_PROFILE).apply()
            else prefs.edit().putString(KEY_PROFILE, value).apply()
        }

    class ProfileInfo(
        val name: String,
        val displayName: String,
        val model: String,
        val gatewayRunning: Boolean,
        val isDefault: Boolean
    )

    /** `GET /api/profiles` — the picker's source of truth. */
    fun profiles(): List<ProfileInfo> {
        val raw = get("/api/profiles") ?: return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("profiles") ?: return emptyList()
        val out = mutableListOf<ProfileInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isEmpty()) continue
            out += ProfileInfo(
                name = name,
                displayName = o.optString("display_name").ifEmpty { name },
                model = o.optString("model").substringAfter('/'),
                gatewayRunning = o.optBoolean("gateway_running", false),
                isDefault = o.optBoolean("is_default", false)
            )
        }
        return out
    }

    class ModelProvider(
        val slug: String,
        val name: String,
        val models: List<String>,
        val isCurrent: Boolean,
        val authenticated: Boolean
    )

    /** `model.options` over the socket — the model picker's list. */
    fun modelOptions(): List<ModelProvider> {
        val result = rpc("model.options", JSONObject(), 25000) ?: return emptyList()
        val arr = result.optJSONArray("providers") ?: return emptyList()
        val out = mutableListOf<ModelProvider>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val slug = o.optString("slug")
            if (slug.isEmpty()) continue
            val models = mutableListOf<String>()
            val mArr = o.optJSONArray("models")
            if (mArr != null) for (j in 0 until mArr.length()) models += mArr.optString(j)
            out += ModelProvider(
                slug = slug,
                name = o.optString("name").ifEmpty { slug },
                models = models,
                isCurrent = o.optBoolean("is_current", false),
                authenticated = o.optBoolean("authenticated", true)
            )
        }
        return out
    }

    class SessionMutation(val ok: Boolean, val model: String?, val provider: String?, val reason: String?)

    /**
     * Changes THIS session's model via `session.mutate operation:"model"`.
     *
     * `config.set key=model` is forbidden on the shared gateway (4001), and the
     * config.yaml default must stay untouched anyway — per-session is the only
     * supported path. `expected_generation` is mandatory (a probe without it
     * answered `invalid_params`); on `revision_conflict` the reply carries
     * fresh counters, so the retry loop needs no re-read.
     */
    fun setSessionModel(sessionId: String, slug: String): SessionMutation {
        error = null
        var revision: Int = -1
        var generation: Int = -1
        // The revision fence counters: from the last resume when this socket is
        // attached to the session, from the REST snapshot otherwise. A conflict
        // re-reads the snapshot instead of guessing the fresh values.
        fun readSnapshot(): Boolean {
            val snapRaw = get("/api/sessions/$sessionId/mutation-snapshot") ?: return false
            val snap = runCatching { JSONObject(snapRaw) }.getOrNull() ?: return false
            revision = snap.optInt("runtime_revision", 0)
            generation = snap.optInt("runtime_generation", 0)
            sessionCounters[sessionId] = revision to generation
            return true
        }
        val known = sessionCounters[sessionId]
        if (known != null) {
            revision = known.first; generation = known.second
        } else if (!readSnapshot()) return failure()
        for (attempt in 1..3) {
            val result = rpc(
                "session.mutate",
                JSONObject()
                    .put("session_id", sessionId)
                    .put("request_id", "apk-" + System.currentTimeMillis())
                    .put("expected_revision", revision)
                    .put("expected_generation", generation)
                    .put("operation", "model")
                    .put("payload", JSONObject().put("model", slug)),
                25000
            )
            if (result != null) {
                val newRev = result.optInt("revision", revision)
                val newGen = result.optInt("execution_generation", generation)
                sessionCounters[sessionId] = newRev to newGen
                return SessionMutation(true, result.optString("model").ifEmpty { slug }, result.optString("provider"), null)
            }
            val why = error ?: return failure()
            // revision_conflict: the answer says the fence moved — re-read the
            // counters and retry instead of failing the tap.
            if (why.contains("revision") || why.contains("conflict")) {
                if (!readSnapshot()) return failure()
                continue
            }
            return SessionMutation(false, null, null, why)
        }
        return failure()
    }

    private fun failure() = SessionMutation(false, null, null, error ?: "Шлюз не ответил")

    /** `config.set` for the per-session mode keys (yolo, reasoning, …). */
    fun setMode(sessionId: String, key: String, value: String): Boolean {
        val result = rpc(
            "config.set",
            JSONObject()
                .put("session_id", sessionId)
                .put("key", key)
                .put("value", value),
            15000
        )
        return result != null
    }

    /** `config.get` — the current value of a per-session mode key. Answers
     *  like `{"value":"0","scope":"session"}` or `{"value":"medium"}`. */
    fun getMode(sessionId: String, key: String): String? {
        val result = rpc(
            "config.get",
            JSONObject().put("session_id", sessionId).put("key", key),
            15000
        ) ?: return null
        val v = result.opt("value")
        return when (v) {
            null -> null
            else -> v.toString()
        }
    }

    // ── Status ───────────────────────────────────────────────────────────────
    /** Gateway liveness + build: `{ok, version, commit}`. */
    fun health(): JSONObject? = get("/api/health")?.let {
        runCatching { JSONObject(it) }.getOrNull()
    }

    /** Current model and the context window it runs with. */
    fun modelInfo(): JSONObject? = get("/api/model/info")?.let {
        runCatching { JSONObject(it) }.getOrNull()
    }

    // ── Messages (REST) ──────────────────────────────────────────────────────

    fun messages(sessionId: String, limit: Int = 200, offset: Int = 0): List<ChatMessage> {
        // `order=latest` is not decoration. Without it the endpoint answers
        // `order: oldest`, i.e. `limit=200` returns the FIRST 200 messages of the
        // session — on a long session the phone showed the very beginning and new
        // messages never appeared at all. With it, `limit` means "the last N" and
        // `offset` walks backwards through the older ones.
        //
        // inline_images=false: the default inlines every stored attachment as a
        // data:-URI in `content`, so a picture-heavy history drags megabytes of
        // base64. The projection replaces the image part with "[image]" instead.
        val raw = get(
            "/api/sessions/$sessionId/messages?limit=$limit&offset=$offset" +
                "&order=latest&inline_images=false"
        ) ?: return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("messages") ?: return emptyList()
        val out = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // Hidden rows are gateway scaffolding (handoffs, notices): the user
            // never typed them and must not see them.
            if (o.optString("display_kind") == "hidden") continue
            ChatMessage.from(o)?.let(out::add)
        }
        return out
    }

    /** Server row count for the session (`message_count` from the session
     *  object) — the denominator in "search over N of M messages". */
    fun messageCount(sessionId: String): Int = runCatching {
        val raw = get("/api/sessions/$sessionId") ?: return -1
        JSONObject(raw).optInt("message_count", -1)
    }.getOrDefault(-1)

    /** Window of messages around a row. The endpoint anchors on a USER row
     *  ("Prompt not found" for anything else), so callers pass the nearest
     *  user message at or before the target. `limit` is capped at 120 by the
     *  server. Returns messages oldest-first. */
    fun messagesAround(sessionId: String, rowId: Long, limit: Int = 60): List<ChatMessage> {
        val raw = get("/api/sessions/$sessionId/messages/around?row_id=$rowId&limit=$limit&inline_images=false")
            ?: return emptyList()
        val arr = runCatching { JSONObject(raw) }.getOrNull()?.optJSONArray("messages") ?: return emptyList()
        val out2 = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("display_kind") == "hidden") continue
            ChatMessage.from(o)?.let(out2::add)
        }
        return out2
    }

    // ── Gateway socket ───────────────────────────────────────────────────────

    private val main = Handler(Looper.getMainLooper())
    private val rpcIds = AtomicInteger(1)
    private val waiters = ConcurrentHashMap<Int, RpcWaiter>()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var openLatch: CountDownLatch? = null
    @Volatile private var sink: ((ChatEvent) -> Unit)? = null

    /** Server→client requests (clarify/approval). Null until a listener
     *  attaches; the frames only arrive after client.capabilities. */
    @Volatile var onRequest: ((ServerRequest) -> Unit)? = null
    @Volatile var onRequestCancel: ((String) -> Unit)? = null

    /** Frames replayed by `session.resume` for requests still unanswered. */
    @Volatile var openRequests: List<ServerRequest> = emptyList()
        private set

    /** Every live request by id, so answer paths can pick the right wire
     *  format (srq frame reply vs canonical respond RPC). */
    private val liveRequests = java.util.concurrent.ConcurrentHashMap<String, ServerRequest>()

    /** Latest `execution_generation` for the attached session: interrupting a
     *  finished turn's generation cannot bleed into the next one. */
    @Volatile private var executionGeneration: Int? = null

    /** Revision-fence counters per session, fed by session.resume and
     *  session.mutate — what session.mutate needs as expected_*. */
    private val sessionCounters = ConcurrentHashMap<String, Pair<Int, Int>>()

    /** Only the attached session's events reach the UI: one socket carries the
     *  traffic of every session this app has touched. Two ids per session can be
     *  live at once (stored row id and live id), so this is a set. */
    private val attachedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** One Diag line per turn is enough to see a mis-filtered event stream. */
    @Volatile private var droppedLogged = false

    /** Logged once per turn: a token-level log would be useless noise. */
    @Volatile private var sawDelta = false

    /** Set by onFailure when a turn was in flight; onOpen replays the gap. */
    @Volatile private var catchUpOnOpen = false

    val isSocketConnected: Boolean get() = connected && socket != null

    /** Opens the socket ahead of the first message so the status bar can report a
     *  real gateway state instead of "unknown". Best effort, never throws. */
    fun warmSocket() {
        if (connected) return
        Thread {
            runCatching { ensureSocket(15000) }
        }.apply { isDaemon = true }.start()
    }

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
        val raw = execute(request)
        if (raw == null) {
            Diag.log("ticket", "нет ответа (401/сеть)")
            return null
        }
        val ticket = runCatching { JSONObject(raw).optString("ticket") }.getOrNull()
        Diag.log("ticket", if (ticket.isNullOrEmpty()) "пустой билет" else "ok ${ticket.take(8)}…")
        return ticket?.ifEmpty { null }
    }

    private fun ensureSocket(timeoutMs: Long = 20000): Boolean {
        if (connected && socket != null) return true
        synchronized(this) {
            if (connected && socket != null) return true
            val ticket = socketTicket() ?: run {
                error = "Шлюз не выдал билет — проверь вход"
                return false
            }
            val scheme = if (base.startsWith("https://")) "wss://" else "ws://"
            val host = base.removePrefix("https://").removePrefix("http://").trimEnd('/')
            val url = scheme + host + "/api/ws?ticket=" +
                URLEncoder.encode(ticket, "UTF-8") + "&native_dial=0"
            val latch = CountDownLatch(1)
            openLatch = latch
            socket?.cancel()
            socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
            val opened = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!opened) {
                Diag.log("socket", "не открылся за ${timeoutMs / 1000} с")
                error = "Шлюз не отвечает по сокету"
            }
            return opened
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected = true
            openLatch?.countDown()
            Diag.log("socket", "открыт")
            // A mid-turn drop: the turn kept running server-side, its events went
            // to nobody. Replay the gap before anything new can interleave.
            if (catchUpOnOpen) {
                catchUpOnOpen = false
                val sid = attachedIds.firstOrNull()
                val target = sink
                if (sid != null && target != null) {
                    Thread { runCatching { catchUp(sid, target) } }.apply { isDaemon = true }.start()
                }
            }
            // The gateway only sends clarify/approval to clients that announced
            // they can answer; without this the agent's question times out.
            sendCapabilities()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (frame.optString("method") == "event") {
                frame.optJSONObject("params")?.let(::handleEvent)
                return
            }
            // A server→client request: string id `srq-…` + a method of its own.
            if (frame.has("method") && frame.optString("id").startsWith("srq-")) {
                ServerRequest.from(frame)?.let { request ->
                    liveRequests[request.requestId] = request
                    val target = onRequest ?: return
                    main.post { target(request) }
                }
                return
            }
            // Retraction (`request.cancel`) arrives as an event — see the
            // "request.cancel" branch in handleEvent; there is no top-level
            // frame form of it on the wire.
            val id = frame.optInt("id", -1)
            waiters.remove(id)?.complete(frame)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected = false
            openLatch?.countDown()
            Diag.log("socket", "сбой: ${t.javaClass.simpleName} ${t.message ?: ""} http=${response?.code ?: "-"}")
            // A drop mid-turn: the next open must replay what published meanwhile.
            if (attachedIds.isNotEmpty() && sink != null) catchUpOnOpen = true
            for (waiter in waiters.values) waiter.fail()
            waiters.clear()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
            openLatch?.countDown()
            Diag.log("socket", "закрыт $code $reason")
        }
    }

    private fun rpc(method: String, params: JSONObject, timeoutMs: Long = 25000): JSONObject? {
        if (!ensureSocket()) {
            Diag.log("rpc", "$method: нет сокета")
            return null
        }
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
            error = "Сокет закрыт, сообщение не ушло"
            Diag.log("rpc", "$method: send() = false")
            return null
        }
        if (!waiter.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            waiters.remove(id)
            error = "$method: шлюз не ответил за ${timeoutMs / 1000} с"
            Diag.log("rpc", "$method: таймаут")
            return null
        }
        val reply = waiter.frame ?: return null
        if (reply.has("error")) {
            val err = reply.optJSONObject("error")
            error = err?.optString("message") ?: "Ошибка шлюза"
            Diag.log("rpc", "$method: ошибка $error")
            return null
        }
        Diag.log("rpc", "$method: ok")
        return reply.optJSONObject("result")
    }

    /** Last RPC error text, for the caller's toast. */
    @Volatile var error: String? = null

    private fun handleEvent(params: JSONObject) {
        val type = params.optString("type")
        val sid = params.optString("session_id")
        // Live frames advance the replay cursor so a post-drop catch-up asks for
        // only the real gap, not the whole turn again.
        if (params.optLong("seq", -1) > 0) {
            replayCursor[sid]?.let { (seq, epoch) ->
                val newSeq = params.optLong("seq")
                if (newSeq > seq) {
                    replayCursor[sid] = Pair(newSeq, params.optString("replay_epoch").ifEmpty { epoch })
                }
            }
        }
        val bound = attachedIds
        // A session has more than one id in flight: the stored row id the client
        // asks with, and the live id the gateway answers `session.resume` with.
        // Filtering on a single value dropped every event whenever those differed,
        // which on screen is "the answer never arrives until you reload".
        if (!EventParser.belongsToSession(params, bound)) {
            if (!droppedLogged) {
                droppedLogged = true
                Diag.log("event", "чужой сеанс ${sid.take(14)}… (жду ${bound.joinToString { it.take(14) }})")
            }
            return
        }
        // Canonical control projection: the prompt arrives as an event and is
        // answered by the clarify.respond/approval.respond RPC, not a frame reply.
        if (type == "clarify.request" || type == "approval.request") {
            params.optJSONObject("payload")?.let(ServerRequest::fromPrompt)?.let { request ->
                liveRequests[request.requestId] = request
                val target = onRequest ?: return
                main.post { target(request) }
            }
            return
        }
        if (type == "clarify.settled" || type == "approval.settled") {
            val promptId = params.optJSONObject("payload")?.optString("prompt_id") ?: ""
            if (promptId.isNotEmpty()) {
                val target = onRequestCancel ?: return
                main.post { target(promptId) }
            }
            return
        }
        // Retraction of a server→client request: timeout, interrupt, or
        // answered from another client. payload.id is the srq id, which is
        // what the request is filed under in liveRequests (canonical prompts
        // are retracted by clarify.settled / approval.settled above).
        if (type == "request.cancel") {
            val payload = params.optJSONObject("payload") ?: JSONObject()
            val target = onRequestCancel ?: return
            val cancelled = liveRequests.values.filter { !it.canonical && it.srqId == payload.optString("id") }
            if (cancelled.isEmpty()) return
            cancelled.forEach { liveRequests.remove(it.requestId) }
            main.post { cancelled.forEach { target(it.requestId) } }
            return
        }
        val payload = params.optJSONObject("payload") ?: JSONObject()
        // Pure parse lives in EventParser (unit-tested on the JVM): `org.json`'s
        // optString turns a JSON null into the literal "null", and the gateway
        // sends `text: null` in message.complete. Reading it naively put the
        // word "null" on screen where the answer should be.
        val event = EventParser.parseEvent(type, payload)
        if (event is ChatEvent.Delta && !sawDelta) {
            sawDelta = true
            Diag.log("event", "первый message.delta")
        }
        if (type == "message.complete") Diag.log("event", "message.complete")
        event?.let { e ->
            val target = sink ?: return
            main.post { target(e) }
            if (e is ChatEvent.Completed) main.post { target(ChatEvent.Done(null)) }
        }
    }

    fun closeSocket() {
        connected = false
        attachedIds.clear()
        sink = null
        socket?.close(1000, "bye")
        socket = null
    }

    // ── Turns ────────────────────────────────────────────────────────────────

    /** Creates a session and returns its id. `source` must be one the gateway
     *  accepts (`cli|tui|gui|acp|tool|oneshot`) — anything else is a 4001. */
    fun createSession(): String? {
        error = null
        val params = JSONObject()
            .put("request_id", "apk-" + System.currentTimeMillis())
            .put("source", "gui")
        // A non-default profile runs its own gateway; without this the session
        // lands on default and the gateway answers profile_mismatch on resume.
        profile?.let { params.put("profile", it) }
        val result = rpc("session.create", params, 40000) ?: return null
        val id = result.optString("session_id").ifEmpty { null } ?: return null
        attachedIds.clear(); attachedIds.add(id)
        return id
    }

    /** Stages image bytes in the profile image cache (gateway's
     *  `image.attach_bytes`); the returned absolute path is what
     *  `prompt.submit`'s `attachments` later refers to. Must run after
     *  `session.resume` — the gateway only accepts it for a subscribed session. */
    fun attachImage(sessionId: String, contentBase64: String, filename: String): JSONObject? {
        return rpc(
            "image.attach_bytes",
            JSONObject()
                .put("session_id", sessionId)
                .put("content_base64", contentBase64)
                .put("filename", filename),
            30000
        )
    }

    /** Uploads an arbitrary file into the session workspace (gateway's
     *  `file.attach`); the returned `ref_text` ("@file:…") is what the server
     *  itself appends to the prompt text, so the client reuses it verbatim. */
    fun attachFile(sessionId: String, dataUrl: String, name: String): JSONObject? {
        return rpc(
            "file.attach",
            JSONObject()
                .put("session_id", sessionId)
                .put("data_url", dataUrl)
                .put("name", name),
            90000
        )
    }

    /**
     * Runs one turn. `onEvent` fires on the main thread.
     *
     * Blocks until the gateway admits the prompt; the answer arrives later on
     * the socket, so the caller's coroutine can return immediately after.
     */
    fun streamTurn(sessionId: String, message: String, onEvent: (ChatEvent) -> Unit) =
        streamTurn(sessionId, message, null, null, onEvent)

    fun streamTurn(
        sessionId: String,
        message: String,
        imageBase64: String?,
        imageFilename: String?,
        onEvent: (ChatEvent) -> Unit
    ) = streamTurn(sessionId, message, imageBase64, imageFilename, null, null, onEvent)

    /**
     * File branch: the bytes ride `file.attach` (workspace attachments, any
     * type) and reach the model as a `ref_text` line the SERVER returns — the
     * client never invents its own reference format. Any image branch above
     * stays untouched.
     */
    fun streamTurn(
        sessionId: String,
        message: String,
        imageBase64: String?,
        imageFilename: String?,
        fileDataUrl: String?,
        fileName: String?,
        onEvent: (ChatEvent) -> Unit
    ) {
        error = null
        sink = onEvent
        attachedIds.clear(); attachedIds.add(sessionId)
        droppedLogged = false
        sawDelta = false
        val resumed = rpc(
            "session.resume",
            JSONObject().put("session_id", sessionId).put("omit_messages", true),
            40000
        )
        if (resumed == null) {
            val why = error ?: "Не удалось открыть сеанс"
            Diag.log("turn", "resume не удался: $why")
            main.post { onEvent(ChatEvent.Error(why)) }
            return
        }
        // The gateway may answer with a different live id than the one asked for
        // (stored vs live session). Submitting to the wrong one is silently
        // accepted but the turn never starts, so follow the gateway's answer.
        val liveId = resumed.optString("session_id").ifEmpty { sessionId }
        if (liveId != sessionId) {
            Diag.log("turn", "live id $liveId (спрашивали ${sessionId.take(18)}…)")
            attachedIds.add(liveId)
        }
        // The replay cursor: after a socket drop, session.events.since replays
        // exactly the frames this client never saw (bounded ring: 512/4MB).
        if (!resumed.isNull("last_sequence") && !resumed.isNull("replay_epoch")) {
            replayCursor[liveId] = Pair(resumed.optLong("last_sequence"), resumed.optString("replay_epoch"))
        }
        // Re-announce after every resume (a reconnect gets a fresh attachment)
        // and pick up requests that were pending when the app went away.
        sendCapabilities()
        executionGeneration = if (resumed.isNull("execution_generation")) null
        else resumed.optInt("execution_generation")
        if (!resumed.isNull("revision")) {
            sessionCounters[sessionId] = resumed.optInt("revision") to (executionGeneration ?: 0)
        }
        openRequests = resumed.optJSONArray("open_requests")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .mapNotNull(ServerRequest::fromResumeEntry)
        } ?: emptyList()
        // Canonical path: pending prompts ride the resume result's `prompts`
        // array (one entry per unanswered question), not `open_requests`.
        val resumedPrompts = resumed.optJSONArray("prompts")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .mapNotNull(ServerRequest::fromPrompt)
        } ?: emptyList()
        openRequests += resumedPrompts
        for (request in openRequests) {
            liveRequests[request.requestId] = request
            val target = onRequest ?: break
            main.post { target(request) }
        }
        val submitParams = JSONObject()
            .put("session_id", liveId)
            .put("text", message)
            .put("submission_id", "apk-" + System.currentTimeMillis())
        // The image only reaches the model via prompt.submit attachments, and
        // the staged path only exists after resume subscribed this socket.
        if (imageBase64 != null) {
            val staged = attachImage(liveId, imageBase64, imageFilename ?: "upload.png")
            val path = staged?.optString("path").orEmpty()
            val mime = staged?.optString("mime").orEmpty()
            if (path.isEmpty()) {
                val why = error ?: "Картинка не прикрепилась"
                Diag.log("turn", "attach_bytes не удался: $why")
                main.post { onEvent(ChatEvent.Error(why)) }
                return
            }
            submitParams.put("attachments", JSONArray().put(
                JSONObject().put("path", path).put("mime", mime)))
            Diag.log("turn", "attachment: ${path.substringAfterLast('/')}")
        }
        var promptText = message
        if (fileDataUrl != null) {
            val uploaded = attachFile(liveId, fileDataUrl, fileName ?: "file.bin")
            val refText = uploaded?.optString("ref_text").orEmpty()
            if (refText.isEmpty()) {
                val why = error ?: "Файл не прикрепился"
                Diag.log("turn", "file.attach не удался: $why")
                main.post { onEvent(ChatEvent.Error(why)) }
                return
            }
            promptText = if (message.isEmpty()) refText else "$message\n$refText"
            Diag.log("turn", "file: ${uploaded?.optString("path")?.substringAfterLast('/')}")
        }
        submitParams.put("text", promptText)
        val submitted = rpc("prompt.submit", submitParams, 40000)
        if (submitted == null) {
            val why = error ?: "Сообщение не отправлено"
            Diag.log("turn", "submit не удался: $why")
            main.post { onEvent(ChatEvent.Error(why)) }
        } else {
            Diag.log("turn", "submit принят, ждём события")
        }
    }

    fun stopTurn(sessionId: String) {
        runCatching {
            val params = JSONObject().put("session_id", sessionId)
            // Pin the interrupt to this turn's generation: without it a stop
            // landing after the turn finished can kill the NEXT turn instead.
            executionGeneration?.let { params.put("execution_generation", it) }
            rpc("session.interrupt", params, 15000)
        }
    }

    // ── Steer: text into the RUNNING turn ────────────────────────────────────

    /** Queues a prompt while a turn is already running. The same `prompt.submit`
     *  RPC the chat uses, but during a turn the receipt comes back `queued` with
     *  an `admission_id` — the handle `prompt.steer_queued` needs. */
    fun submitQueued(sessionId: String, text: String): JSONObject? = rpc(
        "prompt.submit",
        JSONObject()
            .put("session_id", sessionId)
            .put("text", text)
            .put("submission_id", "apk-" + System.currentTimeMillis()),
        40000
    )

    /** Pours a queued prompt into the running turn (it lands between model
     *  calls, not mid-token). `text` must NOT be passed here: the server takes
     *  it from the queue entry and rejects a resend with 4001 invalid_params. */
    fun steerQueued(sessionId: String, admissionId: String): JSONObject? = rpc(
        "prompt.steer_queued",
        JSONObject()
            .put("session_id", sessionId)
            .put("admission_id", admissionId),
        25000
    )

    // ── Replay catch-up ──────────────────────────────────────────────────────

    /**
     * Joins a session's live event stream without sending anything.
     *
     * Opening a chat used to only fetch history, so a turn that was already
     * running (started here, from Telegram, or from the desktop) never appeared
     * until the user typed something. This resumes the session, attaches the
     * event sink, replays what was missed, and reports any pending questions —
     * i.e. it makes an opened chat live.
     */
    fun attach(sessionId: String, onEvent: (ChatEvent) -> Unit): Boolean {
        error = null
        sink = onEvent
        attachedIds.clear()
        attachedIds.add(sessionId)
        droppedLogged = false
        val resumed = rpc(
            "session.resume",
            JSONObject().put("session_id", sessionId).put("omit_messages", true),
            40000
        )
        if (resumed == null) {
            Diag.log("attach", "resume не удался: ${error ?: "?"}")
            return false
        }
        val liveId = resumed.optString("session_id").ifEmpty { sessionId }
        if (liveId != sessionId) attachedIds.add(liveId)
        if (!resumed.isNull("last_sequence") && !resumed.isNull("replay_epoch")) {
            replayCursor[liveId] = Pair(
                resumed.optLong("last_sequence"), resumed.optString("replay_epoch")
            )
        }
        executionGeneration = if (resumed.isNull("execution_generation")) null
        else resumed.optInt("execution_generation")
        if (!resumed.isNull("revision")) {
            sessionCounters[sessionId] = resumed.optInt("revision") to (executionGeneration ?: 0)
        }
        sendCapabilities()
        openRequests = resumed.optJSONArray("open_requests")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .mapNotNull(ServerRequest::fromResumeEntry)
        } ?: emptyList()
        openRequests += resumed.optJSONArray("prompts")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .mapNotNull(ServerRequest::fromPrompt)
        } ?: emptyList()
        for (request in openRequests) {
            liveRequests[request.requestId] = request
            onRequest?.let { target -> main.post { target(request) } }
        }
        Diag.log("attach", "подписан на сеанс")
        return true
    }

    /** Stops delivering events to the closed screen; the socket stays up. */
    fun detach() {
        sink = null
        attachedIds.clear()
    }

    /** True while a turn is running server-side for this session. */
    fun isRunning(sessionId: String): Boolean {
        val resumed = rpc(
            "session.resume",
            JSONObject().put("session_id", sessionId).put("omit_messages", true),
            20000
        ) ?: return false
        return resumed.optBoolean("running", false)
    }

    private val replayCursor = ConcurrentHashMap<String, Pair<Long, String>>()

    class CatchUpResult(val truncated: Boolean, val applied: Int)

    /**
     * Replays the events a socket drop swallowed. Feed the stored cursor to
     * `session.events.since`; every returned frame goes through the same
     * handleEvent path a live frame would. `truncated` (the ring overran, the
     * epoch rolled, or the server says `snapshot_required`) means the deltas are
     * incomplete and the caller must re-read /messages instead.
     */
    fun catchUp(sessionId: String, onEvent: (ChatEvent) -> Unit): CatchUpResult? {
        val cursor = replayCursor[sessionId]
            ?: return CatchUpResult(truncated = true, applied = 0)
        sink = onEvent
        attachedIds.clear(); attachedIds.add(sessionId)
        droppedLogged = false
        sawDelta = false
        val result = rpc(
            "session.events.since",
            JSONObject()
                .put("session_id", sessionId)
                .put("last_sequence", cursor.first)
                .put("replay_epoch", cursor.second),
            20000
        ) ?: return null
        val truncated = result.optBoolean("truncated", false) ||
            result.optBoolean("snapshot_required", false)
        val events = result.optJSONArray("events") ?: JSONArray()
        if (!truncated) {
            for (i in 0 until events.length()) {
                events.optJSONObject(i)?.let(::handleEvent)
            }
            // Advance the cursor so a second drop replays only the new gap.
            val latest = result.optLong("latest_seq", cursor.first)
            val epoch = result.optString("replay_epoch").ifEmpty { cursor.second }
            replayCursor[sessionId] = Pair(latest, epoch)
        }
        return CatchUpResult(truncated, events.length())
    }

    // ── Server requests (clarify / approval) ─────────────────────────────────

    private fun sendCapabilities() {
        val frame = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", "cap-" + rpcIds.getAndIncrement())
            .put("method", "client.capabilities")
            .put("params", JSONObject().put("server_requests", true))
        socket?.send(frame.toString())
    }

    /** Answer one clarify question. `answer == null` skips it. Both wire
     *  formats accept the skip; the canonical one as an empty string (a
     *  documented skip, never a timeout), the srq one as JSON null. */
    fun answerClarify(requestId: String, questionId: String, answer: String?) {
        val request = liveRequests[requestId]
        if (request?.canonical == true) {
            scopeLaunch {
                rpc(
                    "clarify.respond",
                    JSONObject()
                        .put("session_id", attachedIds.firstOrNull() ?: return@scopeLaunch)
                        .put("execution_generation", request.generation ?: return@scopeLaunch)
                        .put("prompt_id", requestId)
                        .put("answer", answer ?: ""),
                    15000
                )
                liveRequests.remove(requestId)
            }
            return
        }
        scopeLaunch {
            rpc(
                "clarify.lock",
                JSONObject()
                    .put("request_id", requestId)
                    .put("question_id", questionId)
                    .put("answer", answer ?: JSONObject.NULL),
                15000
            )
        }
    }

    /** Dismiss a whole clarify batch: a bare reply to the srq id, no `answers`;
     *  on the canonical wire a skip is an empty answer. */
    fun cancelClarify(requestId: String) {
        val request = liveRequests[requestId]
        if (request?.canonical == true) {
            answerClarify(requestId, requestId, null)
            return
        }
        replyTo(requestId) { JSONObject() }
    }

    /** Approval verdict — a reply frame to the srq id with the chosen scope.
     *  `session`/`always` apply to every pending approval at once (`all: true`);
     *  `once`/`deny` answer just this request. The canonical wire carries just
     *  the choice; scope widening is the server's business there. */
    fun answerApproval(requestId: String, choice: String) {
        val request = liveRequests[requestId]
        if (request?.canonical == true) {
            scopeLaunch {
                rpc(
                    "approval.respond",
                    JSONObject()
                        .put("session_id", attachedIds.firstOrNull() ?: return@scopeLaunch)
                        .put("execution_generation", request.generation ?: return@scopeLaunch)
                        .put("prompt_id", requestId)
                        .put("choice", choice),
                    15000
                )
                liveRequests.remove(requestId)
            }
            return
        }
        replyTo(requestId) {
            JSONObject().put("choice", choice).put("all", choice == "session" || choice == "always")
        }
    }

    private fun replyTo(requestId: String, result: () -> JSONObject) {
        val frame = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestId)
            .put("result", result())
        socket?.send(frame.toString())
    }

    /** rpc()/replyTo() may be called from the main thread (button taps); the
     *  sends themselves are cheap but the lock RPC blocks, so push it off. */
    private fun scopeLaunch(block: () -> Unit) {
        Thread { runCatching(block) }.start()
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /** null means "not logged in or unreachable" — the caller shows the login
     *  screen rather than an error dialog. */
    private fun get(path: String): String? =
        execute(Request.Builder().url(base + path).get().build())

    /** Same as get(), but reports the HTTP code (or -1 on IOException) so the
     *  archive can tell an auth failure (302/401) from no connectivity.
     *
     *  Public because the update check needs the same distinction: `version.json`
     *  sits behind the login, and "Не удалось проверить обновления" covered both
     *  an expired session and a dead network. */
    fun getWithStatus(path: String): Pair<Int, String?> = try {
        client.newCall(Request.Builder().url(base + path).get().build()).execute().use { r ->
            when {
                r.isSuccessful -> r.code to r.body?.string()
                else -> r.code to null
            }
        }
    } catch (e: IOException) {
        -1 to null
    }

    /**
     * The Cookie header for [url], ready to be handed to DownloadManager.
     *
     * DownloadManager has its own cookie handling (WebView's CookieManager) and
     * knows nothing about our OkHttp jar — so a download of the APK went out
     * unauthenticated, got a 302 to /login and saved an HTML page as .apk.
     */
    fun cookieHeader(url: String): String? {
        val http = HttpUrl.parse(url) ?: return null
        val cookies = cookieJar.loadForRequest(http)
        if (cookies.isEmpty()) return null
        return cookies.joinToString("; ") { it.name + "=" + it.value }
    }

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
        const val ARCHIVE_ERR_OFFLINE = "Нет связи с сервером"
        const val ARCHIVE_ERR_AUTH = "Нужно войти заново"
        private const val KEY_BASE = "base_url"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_PROFILE = "active_profile"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        @Volatile private var shared: ApiClient? = null

        /** The process-wide client. Activities come and go; the socket must not. */
        fun get(context: Context): ApiClient =
            shared ?: synchronized(this) {
                shared ?: ApiClient(context.applicationContext).also { shared = it }
            }
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
    val timestamp: Double,
    /** A compaction summary: rendered as one collapsed line, not the blob. */
    val compacted: Boolean = false,
    /** Numeric server row id; -1 for rows the server did not number. */
    val id: Long = -1
) {
    val isUser: Boolean get() = role == "user"
    val isTool: Boolean get() = role == "tool"

    companion object {
        fun from(json: JSONObject): ChatMessage? {
            val role = json.optString("role").ifEmpty { return null }
            // display_content is the server's human-facing projection (compaction
            // summaries, steers): when present it wins over the model-facing
            // `content`, which for a summary is a 20K+ service blob.
            val content = json.optString("display_content").ifEmpty {
                json.optString("content")
            }
            val compacted = json.optBoolean("_compressed_summary", false) ||
                content.startsWith("[CONTEXT COMPACTION")
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
                compacted = compacted,
                toolName = toolName,
                reasoning = reasoning,
                timestamp = json.optDouble("timestamp", 0.0),
                id = json.optLong("id", -1L)
            )
        }
    }
}
