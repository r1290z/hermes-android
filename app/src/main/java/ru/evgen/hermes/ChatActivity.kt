package ru.evgen.hermes

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The chat itself — native, no WebView.
 *
 * Reads the transcript from `GET /api/sessions/{id}/messages` and runs a turn
 * through `POST /api/sessions/{id}/chat/stream`, which answers in SSE frames:
 * `assistant.delta` per token, `tool.started/completed`, `assistant.completed`
 * with the final text, then `run.completed`.
 *
 * Two rendering paths on purpose: while tokens arrive the turn is plain text
 * (re-parsing markdown 40 times a second stutters), and the finished turn is
 * re-rendered into blocks with code cards and lists.
 */
class ChatActivity : Activity() {

    private lateinit var api: ApiClient
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var send: ImageButton
    private lateinit var steer: ImageButton
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var adapter: ChatAdapter
    private lateinit var timeline: TextView
    private lateinit var attach: ImageButton
    private lateinit var modelChip: TextView

    private var sessionId: String? = null
    private var streaming = false
    private var runId: String? = null
    private var streamJob: Job? = null
    private var fallbackJob: Job? = null
    /** Set once the answer came from history: late socket deltas would duplicate it. */
    private var fallbackTook = false
    /** Any token arrived for the current turn — the stream is alive, no fallback. */
    private var sawDeltaThisTurn = false
    /** Last assistant text stored BEFORE this turn: the discriminator that tells a
     *  new answer from the previous one. Null until captured. */
    private var baselineText: String? = null
    /** How many history rows are already loaded — the next `offset`. */
    private var olderOffset = 0
    private var loadingOlder = false
    /** Newest message timestamp already on screen — the poll compares against it. */
    private var lastSeenMs = 0L
    private var liveJob: Job? = null
    /** Throttle for pushing the streaming tail into the ongoing notification. */
    private var lastNotifyMs = 0L
    /** One-time toast so the first steer during a turn is not a surprise. */
    private var steerHintShown = false

    /** Picked but not yet sent: the content URI, the decoded bytes, the name. */
    private var pendingImage: PendingImage? = null

    class PendingImage(val uri: Uri, val base64: String, val filename: String)

    private val ui = Handler(Looper.getMainLooper())
    private var pendingIndex = -1
    private var refreshScheduled = false

    // Notification bookkeeping: the card is only useful when the screen is off,
    // and it must not survive the turn.
    private var resumed = true
    private var lastFinalText = ""

    /** Own scope: plain Activity is not a LifecycleOwner, so there is no
     *  lifecycleScope to hand. Cancelled in onDestroy. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        api = ApiClient.get(this)
        list = findViewById(R.id.messages)
        input = findViewById(R.id.input)
        send = findViewById(R.id.btnSend)
        steer = findViewById(R.id.btnSteer)
        attach = findViewById(R.id.btnAttach)
        title = findViewById(R.id.title)
        status = findViewById(R.id.status)
        modelChip = findViewById(R.id.btnModel)
        modelChip.setOnClickListener { showModelPicker() }
        // The default model name until /api/model/info answers; picked lazily
        // so an offline phone still opens the chat instantly.
        scope.launch {
            val info = withContext(Dispatchers.IO) { api.modelInfo() }
            val name = info?.optString("model")?.ifEmpty { null }
            if (name != null && currentModel == null) runOnUiThread { setModelChip(name) }
        }

        adapter = ChatAdapter(this, { url -> openLink(url) }, { loadOlder() })
        adapter.answerHandler = { requestId, a, b -> onAnswer(requestId, a, b) }
        val layout = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.layoutManager = layout
        list.adapter = adapter
        list.itemAnimator = null
        timeline = findViewById(R.id.timeline)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) showTimeline(layout.findFirstVisibleItemPosition())
            }
        })

        api.onRequest = { request -> runOnUiThread { onRequestArrived(request) } }
        api.onRequestCancel = { requestId -> runOnUiThread { onRequestCancelled(requestId) } }

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        title.text = intent.getStringExtra(EXTRA_SESSION_TITLE) ?: getString(R.string.new_session)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnReload).setOnClickListener { loadHistory() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showActions() }
        send.setOnClickListener { if (streaming) stopTurn() else submit() }
        steer.setOnClickListener { steerDuringTurn() }
        attach.setOnClickListener { pickImage() }

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateSendState()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        if (sessionId != null) {
            loadHistory()
            startLive(sessionId!!)
        }
        updateSendState()
    }

    /**
     * A notification tap while this screen is already open.
     *
     * The activity is `singleTop`, so Android reuses the running instance and
     * hands the intent here instead of creating a new one. Ignoring it meant a tap
     * from the shade did nothing visible: the screen kept showing the previous
     * session, or the session it opened never subscribed to events.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = intent.getStringExtra(EXTRA_SESSION_ID)
        val incomingTitle = intent.getStringExtra(EXTRA_SESSION_TITLE)
        if (incoming == null) return
        if (incoming != sessionId) {
            sessionId = incoming
            title.text = incomingTitle ?: getString(R.string.new_session)
            adapter.submit(emptyList())
            olderOffset = 0
            lastSeenMs = 0
        }
        Notifications.cancelWorking(this)
        loadHistory()
        startLive(incoming)
    }

    /**
     * Makes an opened chat live.
     *
     * Two layers, because one is not enough: [ApiClient.attach] subscribes to the
     * session's events (so a turn running right now streams in), and the poll
     * catches what events cannot — a turn that finished while the screen was
     * closed, or a message added from another device. Without this, opening a
     * chat showed a snapshot and stayed frozen until the user typed.
     */
    private fun startLive(id: String) {
        liveJob?.cancel()
        liveJob = scope.launch {
            val attached = withContext(Dispatchers.IO) {
                api.attach(id) { event -> onEvent(event) }
            }
            if (attached) {
                // A running turn means the stream will do the work; nothing was
                // missed, but the history may be ahead of what we rendered.
                loadHistory(keepScroll = true)
            }
            while (true) {
                delay(3000)
                if (streaming) continue
                val newest = withContext(Dispatchers.IO) { api.messages(id, 1) }.lastOrNull()
                    ?: continue
                if (newest.timestamp * 1000 > lastSeenMs) {
                    Diag.log("poll", "на сервере новое сообщение — перечитываю")
                    loadHistory(keepScroll = true)
                }
            }
        }
    }

    // ── History ──────────────────────────────────────────────────────────────

    private fun loadHistory(keepScroll: Boolean = false) {
        val id = sessionId ?: return
        if (!keepScroll) {
            status.visibility = View.VISIBLE
            status.text = getString(R.string.loading)
        }
        scope.launch {
            val messages = withContext(Dispatchers.IO) { api.messages(id, PAGE) }
            olderOffset = messages.size
            lastSeenMs = (messages.maxOfOrNull { it.timestamp } ?: 0.0).let { (it * 1000).toLong() }
            adapter.submit(messages)
            adapter.setOlder(messages.size >= PAGE)
            if (!keepScroll) {
                status.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                if (messages.isEmpty()) status.text = getString(R.string.empty_chat)
                scrollToEnd()
            }
        }
    }

    /**
     * Loads the page before the oldest message on screen.
     *
     * The endpoint pages backwards from the newest message (`order=latest` +
     * `offset`), so the offset is simply how many rows are already loaded. The
     * list keeps its scroll anchor at the top so reading does not jump.
     */
    private fun loadOlder() {
        val id = sessionId ?: return
        if (loadingOlder) return
        loadingOlder = true
        adapter.markOlderLoading(true)
        scope.launch {
            val page = withContext(Dispatchers.IO) { api.messages(id, PAGE, olderOffset) }
            loadingOlder = false
            if (page.isEmpty()) {
                adapter.setOlder(false)
                return@launch
            }
            olderOffset += page.size
            adapter.prependOlder(page, hasMore = page.size >= PAGE)
        }
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        runCatching {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.attach_image)), REQ_PICK_IMAGE)
        }.onFailure { Toast.makeText(this, R.string.no_gallery, Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_IMAGE) return
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        scope.launch {
            val decoded = withContext(Dispatchers.IO) { decodeImage(uri) }
            if (decoded == null) {
                runOnUiThread { Toast.makeText(this@ChatActivity, R.string.image_too_big, Toast.LENGTH_LONG).show() }
                return@launch
            }
            pendingImage = decoded
            runOnUiThread {
                showPendingImage()
                updateSendState()
            }
        }
    }

    /** Downscale to ≤1600px and re-encode PNG/JPEG: a 12MP photo is a 4MB blob
     *  the gateway would refuse, and base64 inflates it another 33%. */
    private fun decodeImage(uri: Uri): PendingImage? = runCatching {
        val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
        val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.setTargetSampleSize(4)
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val maxSide = maxOf(bitmap.width, bitmap.height)
        val scaled = if (maxSide > 1600) {
            val k = 1600f / maxSide
            android.graphics.Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * k).toInt().coerceAtLeast(1), (bitmap.height * k).toInt().coerceAtLeast(1), true
            )
        } else bitmap
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        if (bytes.size > 4 * 1024 * 1024) return@runCatching null
        val name = "img-" + System.currentTimeMillis() + ".jpg"
        PendingImage(uri, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), name)
    }.getOrNull()

    private fun showPendingImage() {
        val frame = findViewById<android.widget.FrameLayout>(R.id.attachPreview)
        val img = frame.findViewById<android.widget.ImageView>(R.id.attachThumb)
        val remove = frame.findViewById<View>(R.id.attachRemove)
        frame.visibility = View.VISIBLE
        img.setImageURI(pendingImage?.uri)
        remove.setOnClickListener {
            pendingImage = null
            frame.visibility = View.GONE
            updateSendState()
        }
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (streaming) return
        if (text.isEmpty() && pendingImage == null) return
        input.setText("")
        hideKeyboard()

        val image = pendingImage
        pendingImage = null
        findViewById<android.widget.FrameLayout>(R.id.attachPreview).visibility = View.GONE

        val id = sessionId
        if (id == null) {
            // A brand-new session has no id until the server creates one; do it
            // on the first message so an empty chat never litters the list.
            status.visibility = View.VISIBLE
            status.text = getString(R.string.waiting)
            scope.launch {
                val created = withContext(Dispatchers.IO) { api.createSession() }
                if (created == null) {
                    status.text = getString(R.string.login_failed)
                    return@launch
                }
                sessionId = created
                title.text = text.take(60)
                sendTurn(created, text, image)
            }
            return
        }
        sendTurn(id, text, image)
    }

    /**
     * Pours the composer's text into the RUNNING turn.
     *
     * Two RPCs, in order and both required: `prompt.submit` while a turn is
     * live answers `queued` with an `admission_id`, and only
     * `prompt.steer_queued {admission_id}` (no text — the server takes it from
     * the queue) moves it into the current turn. Skip the second and the text
     * silently becomes the next turn instead.
     */
    private fun steerDuringTurn() {
        val id = sessionId ?: return
        if (!streaming) { submit(); return }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        if (!steerHintShown) {
            steerHintShown = true
            Toast.makeText(this, R.string.steer_hint_toast, Toast.LENGTH_SHORT).show()
        }
        adapter.addSteer(text)
        scrollToEnd()
        scope.launch {
            val receipt = withContext(Dispatchers.IO) { api.submitQueued(id, text) }
            val admissionId = receipt?.optString("admission_id").orEmpty()
            if (receipt == null || admissionId.isEmpty()) {
                // Nothing was queued: without a marker the row looks delivered.
                adapter.setSteerState(text, "queued")
                return@launch
            }
            val steerResult = withContext(Dispatchers.IO) { api.steerQueued(id, admissionId) }
            val state = if (steerResult?.optString("status") == "started") "started"
                else if (steerResult?.optString("status") == "queued") "queued" else "failed"
            adapter.setSteerState(text, state)
        }
    }

    private fun sendTurn(id: String, text: String, image: PendingImage? = null) {
        adapter.addLocalUser(text, image?.uri)
        scrollToEnd()
        streaming = true
        fallbackTook = false
        sawDeltaThisTurn = false
        baselineText = null
        // A stale index would repaint a row from the previous turn instead of the
        // new answer, which looks exactly like "the chat stopped updating".
        pendingIndex = -1
        updateSendState()
        status.visibility = View.VISIBLE
        status.text = getString(R.string.waiting)

        streamJob = scope.launch {
            try {
                // What the session held before this turn. Captured here, not at
                // fallback time: by then the answer may already be stored and the
                // two would be indistinguishable.
                baselineText = withContext(Dispatchers.IO) { api.latestAssistantText(id) }
                withContext(Dispatchers.IO) {
                    api.streamTurn(id, text, image?.base64, image?.filename) { event -> onEvent(event) }
                }
            } catch (e: Exception) {
                runOnUiThread { onEvent(ChatEvent.Error(e.message ?: "Ошибка")) }
            }
        }
        startHistoryFallback(id)
        if (!resumed) {
            Notifications.showWorking(this, id, title.text.toString(), getString(R.string.waiting))
        }
    }

    /**
     * The answer must appear even when the socket goes quiet — but only then.
     *
     * Streaming is the fast path and the truth; this is a safety net for a
     * dropped socket, a rejected resume, or a gateway that accepts the submit and
     * never publishes deltas. Two rules make it safe:
     *
     *  - it never fires while deltas are arriving ([sawDeltaThisTurn]);
     *  - it only accepts a history answer that DIFFERS from the text already
     *    stored when the turn started ([baselineText]).
     *
     * The second rule is the one that was missing: the first version compared the
     * history against whatever was on screen, which is empty right after the user
     * bubble is added, so one second after submitting it grabbed the PREVIOUS
     * answer, rendered it as the new one, and then suppressed the real deltas —
     * the reply was "lost" by the very code meant to rescue it.
     */
    private fun startHistoryFallback(sessionId: String) {
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            val deadline = System.currentTimeMillis() + 6 * 60_000
            var waited = 0
            while (streaming && System.currentTimeMillis() < deadline) {
                delay(3000)
                if (!streaming || sawDeltaThisTurn) break
                waited += 3
                // Said out loud, because "no tokens for a minute" and "tokens are
                // being dropped" look identical on screen and need different fixes.
                if (waited == 15) Diag.log("turn", "нет дельт 15 с — ждём")
                val base = baselineText ?: continue
                val answerText = withContext(Dispatchers.IO) {
                    api.latestAssistantText(sessionId)
                }
                if (answerText.isEmpty()) continue
                // Same text as before the turn = nothing new was stored yet.
                if (answerText == base) continue
                Diag.log("fallback", "сокет молчит, беру ответ из истории (${answerText.length} симв)")
                fallbackTook = true
                adapter.setAssistantText(answerText)
                lastFinalText = answerText
                finishFromHistory()
                maybeAutoTitle(answerText)
                break
            }
        }
    }

    /** Ends the turn from the REST answer: same state a clean `Done` leaves. */
    private fun finishFromHistory() {
        streaming = false
        updateSendState()
        status.visibility = View.GONE
        adapter.notifyDataSetChanged()
        scrollToEnd()
        if (!resumed) {
            Notifications.showAnswer(this, sessionId, title.text.toString(), lastFinalText.take(300))
        } else {
            Notifications.cancelWorking(this)
        }
    }

    /** Appends a token to the streaming turn, telling the list when the row is
     *  new. Without the INSERT a first token creates a row RecyclerView does not
     *  know about and the answer stays invisible until a full reload. */
    private fun appendText(text: String) {
        val before = adapter.itemCount
        val index = adapter.appendDelta(text)
        if (adapter.itemCount > before) adapter.notifyItemInserted(index)
        pendingIndex = index
        scheduleRefresh()
        // In the shade, the answer should be readable while it is being written,
        // not just announced when it is done.
        if (!resumed && System.currentTimeMillis() - lastNotifyMs > 4000) {
            lastNotifyMs = System.currentTimeMillis()
            val tail = adapter.currentAssistantText().takeLast(200).trim()
            if (tail.isNotEmpty()) {
                Notifications.showWorking(this, sessionId, title.text.toString(), "…" + tail)
            }
        }
    }

    private fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.Started -> runId = event.runId.ifEmpty { runId }

            is ChatEvent.Delta -> {
                // Tokens are arriving: the stream is healthy, so the history
                // fallback must never take over this turn.
                sawDeltaThisTurn = true
                fallbackJob?.cancel()
                // The turn was already rendered from history; appending tokens now
                // would show the same sentence twice.
                if (fallbackTook) return
                appendText(event.text)
            }

            is ChatEvent.Reasoning -> {
                val before = adapter.itemCount
                val index = adapter.appendReasoning(event.text)
                // A brand-new row needs an INSERT, not a change: RecyclerView keeps
                // its own item count and silently ignores a row it was never told
                // about, so the answer only appeared after a full reload.
                if (adapter.itemCount > before) adapter.notifyItemInserted(index)
                pendingIndex = index
                scheduleRefresh()
            }

            is ChatEvent.Commentary -> {
                if (event.text.isNotBlank()) appendText("\n\n" + event.text + "\n\n")
            }

            is ChatEvent.ToolStarted -> {
                pendingIndex = adapter.startTool(event.name, event.preview, event.args)
                runOnUiThread {
                    adapter.notifyItemInserted(pendingIndex)
                    status.text = event.name
                    scrollToEnd()
                }
            }

            is ChatEvent.ToolFinished -> runOnUiThread {
                status.text = event.name
            }

            is ChatEvent.Completed -> {
                lastFinalText = event.content
                adapter.finishTurn(event.content)
                runOnUiThread { adapter.notifyDataSetChanged() }
                maybeAutoTitle(event.content)
            }

            is ChatEvent.Error -> runOnUiThread {
                streaming = false
                updateSendState()
                status.visibility = View.VISIBLE
                status.text = event.message
                Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
            }

            is ChatEvent.Done -> runOnUiThread {
                streaming = false
                fallbackJob?.cancel()
                runId = null
                updateSendState()
                status.visibility = View.GONE
                adapter.notifyDataSetChanged()
                scrollToEnd()
                // Away from the screen when the turn ended: the notification has to
                // carry the answer itself, not "готово". `lastFinalText` is empty
                // when the turn was streamed only into deltas, so fall back to what
                // the server stored.
                if (!resumed) {
                    val snippet = lastFinalText.takeIf { it.isNotBlank() }
                        ?: adapter.currentAssistantText()
                    if (snippet.isNotBlank()) {
                        Notifications.showAnswer(this, sessionId, title.text.toString(), snippet.take(400))
                    } else {
                        scope.launch {
                            val stored = withContext(Dispatchers.IO) {
                                sessionId?.let { api.latestAssistantText(it) }.orEmpty()
                            }
                            Notifications.showAnswer(
                                this@ChatActivity, sessionId, title.text.toString(),
                                stored.take(400).ifBlank { "Ответ готов" }
                            )
                        }
                    }
                } else {
                    Notifications.cancelWorking(this)
                }
            }
        }
    }

    /** After a turn an unnamed session takes its title from the answer's first
     *  60 chars — otherwise the sidebar stays a wall of "Без названия". */
    private fun maybeAutoTitle(answerText: String) {
        if (answerText.isBlank()) return
        val sid = sessionId ?: return
        scope.launch {
            val newTitle = withContext(Dispatchers.IO) { api.autoTitle(sid, answerText) }
            if (newTitle != null) runOnUiThread { title.text = newTitle }
        }
    }

    /** One repaint per ~80 ms instead of one per token: the transcript is a
     *  RecyclerView and a notify per token makes the list unusable. */
    private fun scheduleRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        ui.postDelayed({
            refreshScheduled = false
            if (pendingIndex >= 0) {
                adapter.notifyItemChanged(pendingIndex)
                scrollToEnd(force = false)
            }
        }, 80)
    }

    private fun stopTurn() {
        streamJob?.cancel()
        streaming = false
        updateSendState()
        // The turn lives on the server, not in this coroutine: cancelling the
        // job only stops listening, so the run has to be interrupted too.
        sessionId?.let { id -> scope.launch(Dispatchers.IO) { api.stopTurn(id) } }
        status.text = getString(R.string.stop)
        status.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    // ── Model & modes ────────────────────────────────────────────────────────

    /** What the chip shows; null until something authoritative answered. */
    private var currentModel: String? = null
    private var modelPickerOpen = false

    private fun setModelChip(slug: String?) {
        currentModel = slug?.ifEmpty { null } ?: currentModel
        modelChip.text = shortModel(currentModel)
    }

    /** `provider/slug:variant` → `slug`. */
    private fun shortModel(slug: String?): String {
        val s = (slug ?: return "модель ?").substringAfter('/')
        return s.ifEmpty { "модель ?" }
    }

    /**
     * The model sheet: search on top, providers as sections, the current model
     * checked. Picking is optimistic — the chip flips at once and rolls back
     * with a toast when the gateway refuses.
     */
    private fun showModelPicker() {
        val id = sessionId ?: return
        if (modelPickerOpen) return
        modelPickerOpen = true
        scope.launch {
            val providers = withContext(Dispatchers.IO) { api.modelOptions() }
            modelPickerOpen = false
            if (providers.isEmpty()) {
                Toast.makeText(this@ChatActivity, api.error ?: "Список моделей не получен", Toast.LENGTH_LONG).show()
                return@launch
            }
            runOnUiThread { ModelPickerDialog(this@ChatActivity, providers, currentModel) { picked -> switchModel(id, picked) }.show() }
        }
    }

    private fun switchModel(id: String, slug: String) {
        if (slug == currentModel) return
        val previous = currentModel
        setModelChip(slug)  // optimistic
        scope.launch {
            val result = withContext(Dispatchers.IO) { api.setSessionModel(id, slug) }
            if (result.ok) {
                setModelChip(result.model ?: slug)
                Toast.makeText(this@ChatActivity, getString(R.string.model_switched, result.model ?: slug), Toast.LENGTH_SHORT).show()
            } else {
                currentModel = previous
                setModelChip(previous)
                Toast.makeText(this@ChatActivity, getString(R.string.model_switch_failed, result.reason ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * «Ещё» → «Режим»: per-session switches. `approvals.mode` is NOT per-session
     * on the server — it lands in the profile's config.yaml — so it gets a
     * warning line of its own.
     */
    private fun showModes() {
        val id = sessionId ?: return
        ModesDialog(this, api, id) { key, value ->
            scope.launch(Dispatchers.IO) { api.setMode(id, key, value) }
        }.show()
    }

    // ── Session actions ──────────────────────────────────────────────────────

    private fun showActions() {
        val id = sessionId ?: return
        val items = arrayOf<CharSequence>(
            getString(R.string.rename),
            getString(R.string.pin),
            getString(R.string.modes),
            getString(R.string.delete)
        )
        AlertDialog.Builder(this)
            .setTitle(title.text)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askRename(id)
                    1 -> scope.launch {
                        withContext(Dispatchers.IO) { api.setPinned(id, true) }
                        Toast.makeText(this@ChatActivity, R.string.pin, Toast.LENGTH_SHORT).show()
                    }
                    2 -> showModes()
                    3 -> askDelete(id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askRename(id: String) {
        val field = EditText(this).apply {
            setText(title.text)
            setSingleLine(true)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(field)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newTitle = field.text.toString().trim()
                if (newTitle.isEmpty()) return@setPositiveButton
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { api.renameSession(id, newTitle) }
                    if (ok) title.text = newTitle
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askDelete(id: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(title.text)
            .setPositiveButton(R.string.delete) { _, _ ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { api.deleteSession(id) }
                    if (ok) {
                        Toast.makeText(this@ChatActivity, R.string.deleted, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private fun updateSendState() {
        send.setImageResource(if (streaming) R.drawable.ic_stop else R.drawable.ic_send)
        send.contentDescription = getString(if (streaming) R.string.stop else R.string.send)
        // While a turn runs, "stop" and "steer" are two separate side-by-side
        // actions — replacing one with the other (or hiding stop) loses one of
        // them, so both stay visible.
        steer.visibility = if (streaming) View.VISIBLE else View.GONE
        send.alpha = if (streaming || input.text.isNotBlank() || pendingImage != null) 1f else 0.4f
    }

    private fun scrollToEnd(force: Boolean = true) {
        if (adapter.itemCount == 0) return
        if (force) {
            list.scrollToPosition(adapter.itemCount - 1)
            return
        }
        // Only follow the stream when the user is already at the bottom: yanking
        // the list down mid-read makes a long answer unreadable.
        val layout = list.layoutManager as? LinearLayoutManager ?: return
        val visible = layout.findLastVisibleItemPosition()
        if (visible < 0 || visible >= adapter.itemCount - 2) {
            list.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /**
     * Shows which moment sits at the top of the screen while scrolling, then
     * fades it out. Cancels any pending fade so a fast flick cannot leave the
     * chip stuck half-transparent.
     */
    private fun showTimeline(position: Int) {
        if (position < 0) return
        val label = ChatAdapter.timelineLabel(adapter.timeAt(position))
        if (label.isEmpty()) return
        timeline.text = label
        timeline.animate().cancel()
        timeline.animate().alpha(1f).setDuration(120).start()
        ui.removeCallbacks(hideTimeline)
        ui.postDelayed(hideTimeline, 1200)
    }

    private val hideTimeline = Runnable {
        timeline.animate().cancel()
        timeline.animate().alpha(0f).setDuration(300).start()
    }

    private fun openLink(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    // ── Server requests (clarify / approval) ─────────────────────────────────

    private fun onRequestArrived(request: ServerRequest) {
        // Answered from the notification shade while this screen was alive:
        // don't re-show a card for a request the receiver already settled.
        if (pendingAnswered.remove(request.requestId) != null) return
        val idx = adapter.addQuestion(request)
        list.scrollToPosition(idx)
        if (!resumed) {
            Notifications.showRequest(this, sessionId, title.text.toString(), request)
        }
    }

    private fun onRequestCancelled(requestId: String) {
        if (!adapter.hasQuestion(requestId)) return
        adapter.resolveQuestion(requestId, getString(R.string.question_cancelled))
        Notifications.cancelRequest(this, Notifications.ID_REQUEST_BASE + requestId.hashCode() % 1000)
        scrollToEnd()
    }

    /** Callback from the question card. Clarify: (qid, answer|null); approval:
     *  (null, choice); cancel batch: (null, null). */
    private fun onAnswer(requestId: String, qid: String?, answer: String?) {
        val label = when {
            qid == null && answer == null -> null                      // cancelled
            qid == null -> labelOfChoice(answer ?: "")                 // approval
            else -> answer ?: getString(R.string.skip)                 // clarify
        }
        when {
            qid == null && answer == null -> api.cancelClarify(requestId)
            qid == null -> api.answerApproval(requestId, answer!!)
            answer == null -> api.answerClarify(requestId, qid, null)
            else -> api.answerClarify(requestId, qid, answer)
        }
        Notifications.cancelRequest(this, Notifications.ID_REQUEST_BASE + requestId.hashCode() % 1000)
        adapter.resolveQuestion(
            requestId,
            label?.let { getString(R.string.answered, it) } ?: getString(R.string.question_cancelled)
        )
        scrollToEnd()
    }

    private fun labelOfChoice(choice: String): String = when (choice) {
        "once" -> getString(R.string.approve_once)
        "session" -> getString(R.string.approve_session)
        "always" -> getString(R.string.approve_always)
        "deny" -> getString(R.string.deny)
        else -> choice
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // Requests answered from the shade while the process was alive but the
        // screen closed: their cards are stale, resolve them quietly.
        for ((requestId, choice) in pendingAnswered) {
            adapter.resolveQuestion(requestId, getString(R.string.answered, labelOfChoice(choice)))
        }
        pendingAnswered.clear()
        // Looking at the chat is not the same as the turn being over: the card
        // stays while the run is alive and only goes on Done (see above).
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        if (streaming) {
            Notifications.showWorking(
                this, sessionId, title.text.toString(), getString(R.string.waiting)
            )
        }
    }

    override fun onDestroy() {
        streamJob?.cancel()
        fallbackJob?.cancel()
        liveJob?.cancel()
        scope.cancel()
        if (liveApi === api) liveApi = null
        api.onRequest = null
        api.onRequestCancel = null
        // Leaving the screen: stop receiving this session's events, but keep the
        // socket up so a turn started here still completes and notifies.
        api.detach()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SESSION_ID = "ru.evgen.hermes.EXTRA_SESSION_ID"
        const val EXTRA_SESSION_TITLE = "ru.evgen.hermes.EXTRA_SESSION_TITLE"
        const val REQ_PICK_IMAGE = 4101

        /** Rows per history page. 200 is ~0.5 MB of JSON on a busy session and
         *  still parses in well under a second; older pages load on demand. */
        private const val PAGE = 200

        /** For RequestActionReceiver: answers need the socket this activity's
         *  ApiClient holds. Kept until onDestroy — the shade action typically
         *  fires while the activity is stopped, not destroyed. */
        @Volatile var liveApi: ApiClient? = null
        val pendingAnswered = mutableMapOf<String, String>()
    }

    override fun onStart() {
        super.onStart()
        liveApi = api
    }
}
