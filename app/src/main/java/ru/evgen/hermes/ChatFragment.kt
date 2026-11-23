package ru.evgen.hermes

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.fragment.app.Fragment
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
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.text.style.BackgroundColorSpan
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ScrollView

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
class ChatFragment : Fragment() {

    /** What the fragment needs from its host: the back button goes to the
     *  sessions page instead of finish()ing anything. */
    interface Host {
        fun onChatBack()
        /** The session this page must show, if the host parked one while the page
         *  did not exist yet. Taken once — the caller owns the value after that. */
        fun takePendingChat(): Pair<String, String?>?
    }

    private lateinit var api: ApiClient
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    /** Slash palette: the gateway's command catalog, filtered as the user types. */
    private var slashList: RecyclerView? = null
    private var slashAdapter: SlashAdapter? = null
    private var slashJob: Job? = null
    /** Rows visible in the palette at once. */
    private val slashRows = 6
    private lateinit var send: ImageButton
    private lateinit var steer: ImageButton
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var adapter: ChatAdapter
    private lateinit var timeline: TextView
    private lateinit var attach: ImageButton
    private lateinit var mic: ImageButton
    private lateinit var modelChip: TextView
    private lateinit var btnDown: ImageButton

    private var sessionId: String? = null
    private var streaming = false

    // ── In-chat search ────────────────────────────────────────────────────────
    // The gateway has no per-session message search (GET /api/sessions/search
    // matches SESSIONS, not messages, and returns no message ids), so search
    // runs on the phone: the first query indexes the whole transcript through
    // the same paged /messages endpoint and filters in memory. The scope line
    // says so out loud — "поиск по сеансу: N из M сообщений" — instead of
    // pretending the server did it.
    private lateinit var searchBar: View
    private lateinit var searchInput: EditText
    private lateinit var searchCount: TextView
    private lateinit var searchScroll: ScrollView
    private lateinit var searchResults: LinearLayout
    private var searchOpen = false
    /** Full transcript, oldest-first, filled once on the first query. */
    private var transcript: List<ChatMessage> = emptyList()
    private var transcriptLoading = false
    /** Server message_count, -1 until fetched. */
    private var totalMessages = -1
    private var searchJob: Job? = null
    private val searchMatches = mutableListOf<ChatMessage>()
    private var matchIndex = -1

    private var runId: String? = null
    private var streamJob: Job? = null
    private var fallbackJob: Job? = null
    /** True once the user scrolled AWAY from the bottom while a turn ran —
     *  the down-button's visibility condition. */
    private var scrolledUp = false
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
    /** Same for the file branch: raw bytes kept as a data URL only up to the
     *  20 MB cap, plus the display name/size for the bubble line. */
    private var pendingFile: PendingFile? = null
    /** The last failed file turn, so «Повторить» can resend it verbatim. */
    private var failedFile: PendingFile? = null
    private var failedText: String = ""
    private var fileRowKey: String? = null

    class PendingImage(val uri: Uri, val base64: String, val filename: String)

    class PendingFile(val uri: Uri, val name: String, val size: Long, val dataUrl: String)

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

    /** Set in onAttach, cleared in onDetach: a fragment has no findViewById
     *  of its own, so views are bound lazily through the activity. */
    private var act: MainActivity? = null

    /** The result launchers must be registered before onCreate finishes;
     *  the codes match the old REQ_* constants one for one. */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> onPickerResult(REQ_PICK_IMAGE, result.resultCode, result.data) }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> onPickerResult(REQ_PICK_FILE, result.resultCode, result.data) }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> onPickerResult(REQ_SPEECH, result.resultCode, result.data) }

    @Suppress("DEPRECATION")
    private fun onPickerResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            REQ_PICK_IMAGE -> {
                val uri = data?.data ?: return
                scope.launch {
                    val decoded = withContext(Dispatchers.IO) { decodeImage(uri) }
                    if (decoded == null) {
                        ui.post { Toast.makeText(context, R.string.image_too_big, Toast.LENGTH_LONG).show() }
                        return@launch
                    }
                    pendingImage = decoded
                    ui.post {
                        showPendingImage()
                        updateSendState()
                    }
                }
            }
            REQ_PICK_FILE -> {
                val uri = data?.data
                if (uri == null) {
                    Diag.log("file", "выбор файла не вернул uri")
                    return
                }
                Diag.log("file", "выбран: $uri")
                scope.launch {
                    val (picked, why) = withContext(Dispatchers.IO) { readFile(uri) }
                    if (picked == null) {
                        Diag.log("file", "не прочитан: ${why ?: "?"}")
                        val msg = why?.let { getString(R.string.file_read_failed, it) }
                            ?: getString(R.string.file_too_big)
                        ui.post { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                        return@launch
                    }
                    // Name and size up front — never send a file sight-unseen.
                    ui.post { confirmFile(picked) }
                }
            }
            REQ_SPEECH -> {
                val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull { it.isNotBlank() } ?: return
                input.append((if (input.text.isNotEmpty() && !input.text.last().isWhitespace()) " " else "") + text)
                input.setSelection(input.text.length)
                input.requestFocus()
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        act = context as? MainActivity
        api = ApiClient.get(context)
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_chat, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(R.id.messages)
        input = view.findViewById(R.id.input)
        send = view.findViewById(R.id.btnSend)
        steer = view.findViewById(R.id.btnSteer)
        attach = view.findViewById(R.id.btnAttach)
        mic = view.findViewById(R.id.btnMic)
        title = view.findViewById(R.id.title)
        status = view.findViewById(R.id.status)
        modelChip = view.findViewById(R.id.btnModel)
        modelChip.setOnClickListener { showModelPicker() }
        // The default model name until /api/model/info answers; picked lazily
        // so an offline phone still opens the chat instantly.
        scope.launch {
            val info = withContext(Dispatchers.IO) { api.modelInfo() }
            val name = info?.optString("model")?.ifEmpty { null }
            if (name != null && currentModel == null) ui.post { setModelChip(name) }
        }

        adapter = ChatAdapter(requireContext(), { url -> openLink(url) }, { loadOlder() },
            { path -> openAttachment(path) })
        adapter.answerHandler = { requestId, a, b -> onAnswer(requestId, a, b) }
        val layout = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        list.layoutManager = layout
        list.adapter = adapter
        adapter.listRef = list
        list.itemAnimator = null
        timeline = view.findViewById(R.id.timeline)
        btnDown = view.findViewById(R.id.btnDown)
        btnDown.setOnClickListener {
            scrolledUp = false
            btnDown.visibility = View.GONE
            scrollToEnd()
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) showTimeline(layout.findFirstVisibleItemPosition())
                updateDownButton()
            }
        })

        api.onRequest = { request -> ui.post { onRequestArrived(request) } }
        api.onRequestCancel = { requestId -> ui.post { onRequestCancelled(requestId) } }

        // What the host parked while this page did not exist yet wins over the launch
        // arguments: args are written at fragment creation, and the tap that named a
        // session often happens after that (see MainActivity.showChat).
        val start = resolveStart(
            parked = (activity as? Host)?.takePendingChat(),
            argId = arguments?.getString(EXTRA_SESSION_ID),
            argTitle = arguments?.getString(EXTRA_SESSION_TITLE)
        )
        sessionId = start.first
        title.text = start.second ?: getString(R.string.new_session)

        view.findViewById<View>(R.id.btnBack).setOnClickListener { goBackToList() }
        view.findViewById<View>(R.id.btnReload).setOnClickListener { loadHistory() }
        view.findViewById<View>(R.id.btnMore).setOnClickListener { showActions() }

        searchBar = view.findViewById(R.id.searchBar)
        searchInput = view.findViewById(R.id.searchInput)
        searchCount = view.findViewById(R.id.searchCount)
        searchScroll = view.findViewById(R.id.searchScroll)
        searchResults = view.findViewById(R.id.searchResults)
        view.findViewById<View>(R.id.btnSearch).setOnClickListener { openSearch() }
        view.findViewById<View>(R.id.btnCloseSearch).setOnClickListener { closeSearch() }
        view.findViewById<View>(R.id.btnPrevMatch).setOnClickListener { stepMatch(-1) }
        view.findViewById<View>(R.id.btnNextMatch).setOnClickListener { stepMatch(1) }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = scheduleSearch()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        send.setOnClickListener { if (streaming) stopTurn() else submit() }
        steer.setOnClickListener { steerDuringTurn() }
        attach.setOnClickListener { showAttachMenu() }
        mic.setOnClickListener { startSpeech() }

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                updateSendState()
                onComposerChanged(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        if (sessionId != null) {
            loadHistory()
            startLive(sessionId!!)
        }
        setupSlashPalette()
        restoreDraft()
        updateSendState()
        renderEmptyState()
    }

    /** Page 2 without an open session: an explicit empty state, not a blank
     *  screen or a crash. The banner disappears the moment a session opens. */
    private fun renderEmptyState() {
        val empty = view?.findViewById<View>(R.id.chatEmpty) ?: return
        empty.visibility = if (sessionId == null) View.VISIBLE else View.GONE
    }

    /** Back from the chat: to the sessions page, never finish() — the host
     *  owns the pager and the back stack. */
    private fun goBackToList() {
        (activity as? Host)?.onChatBack()
    }

    // ── Composer draft ────────────────────────────────────────────────────────

    /**
     * Unsent composer text survives leaving the screen: saved per session in
     * SharedPreferences on pause/destroy, restored on create. A send clears it.
     */
    private fun draftKey(): String = "draft:" + (sessionId ?: "new")

    private fun draftPrefs() =
        requireActivity().getSharedPreferences("ChatFragment", Context.MODE_PRIVATE)

    private fun saveDraft() {
        if (input.text.isBlank()) {
            draftPrefs().edit().remove(draftKey()).apply()
        } else {
            draftPrefs().edit().putString(draftKey(), input.text.toString()).apply()
        }
    }

    private fun restoreDraft() {
        val saved = draftPrefs().getString(draftKey(), null) ?: return
        input.setText(saved)
        input.setSelection(input.text.length)
    }

    private fun clearDraft() {
        draftPrefs().edit().remove(draftKey()).apply()
    }

    /**
     * A notification tap (or a tap on a session row) while this page is already
     * open. This is what the old `onNewIntent` did: switch the session in place,
     * without recreating the fragment. A null id just re-syncs the current one.
     */
    fun openSession(id: String, title: String?) {
        if (id != sessionId) {
            saveDraft() // the draft belongs to the session it was typed in
            clearStagedAttachments()
            sessionId = id
            this.title.text = title ?: getString(R.string.new_session)
            adapter.submit(emptyList())
            olderOffset = 0
            lastSeenMs = 0
            // Search state belongs to the previous session's transcript.
            transcript = emptyList()
            totalMessages = -1
            if (searchOpen) closeSearch()
        }
        if (context != null) {
            Notifications.cancelWorking(requireContext())
            // The card existed because this session's answer went unread; the user is
            // now reading it, so the card has no job left.
            Notifications.cancelAnswer(requireContext(), id)
        }
        loadHistory()
        startLive(id)
        renderEmptyState()
    }

    /**
     * «Новый сеанс»: empty the page without creating anything server-side.
     *
     * A session is only born when the first message is sent (see submit →
     * createSession), so "new" is a state of this page, not an API call. The bug
     * this fixes: the button used to call showChat(null, null), which only paged
     * to the chat and left the previously open session in place — the composer
     * kept writing into the old chat.
     */
    fun startNewSession() {
        val previous = sessionId
        saveDraft()  // the draft belongs to the session it was typed in
        clearStagedAttachments()
        liveJob?.cancel()
        liveJob = null
        // A turn still streaming belongs to the session we are leaving. Cancel the
        // listener and free the composer: the run itself continues on the server,
        // and its answer lands in the old session (where the list shows it as
        // unread). Leaving `streaming` true would block typing into the new chat.
        if (streaming) {
            streamJob?.cancel()
            streaming = false
            updateSendState()
        }
        // Stop receiving the old session's events: they would otherwise land in
        // the empty transcript of a chat that has no session yet. The socket
        // itself stays up, so a turn started earlier still completes and notifies.
        api.detach()
        sessionId = null
        title.text = getString(R.string.new_session)
        adapter.submit(emptyList())
        adapter.setOlder(false)
        olderOffset = 0
        lastSeenMs = 0
        lastFinalText = ""
        transcript = emptyList()
        totalMessages = -1
        if (searchOpen) closeSearch()
        status.visibility = View.GONE
        restoreDraft()  // a fresh session still has its own saved draft
        renderEmptyState()
        // The user tapped "new" to type, so put the cursor where they will type.
        input.requestFocus()
        input.post {
            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        if (previous != null) Diag.log("chat", "новый сеанс (был ${previous.take(12)}…)")
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

    // ── In-chat search ───────────────────────────────────────────────────────

    private fun openSearch() {
        if (searchOpen) return
        searchOpen = true
        title.visibility = View.GONE
        searchBar.visibility = View.VISIBLE
        searchScroll.visibility = View.VISIBLE
        searchInput.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchInput, 0)
        val id = sessionId ?: return
        if (transcript.isEmpty() && !transcriptLoading) indexTranscript(id)
    }

    private fun closeSearch() {
        searchOpen = false
        searchBar.visibility = View.GONE
        searchScroll.visibility = View.GONE
        title.visibility = View.VISIBLE
        searchInput.setText("")
        searchMatches.clear()
        matchIndex = -1
        hideKeyboard()
    }

    /** Pulls the whole transcript through the same paged endpoint the feed
     *  uses. 200 rows are ~0.5 MB; a 1000-message session is a few seconds on
     *  Wi-Fi, once per screen. */
    private fun indexTranscript(id: String) {
        transcriptLoading = true
        searchCount.visibility = View.VISIBLE
        searchCount.text = getString(R.string.search_loading_index)
        scope.launch {
            val chunks = mutableListOf<ChatMessage>()
            var offset = 0
            while (true) {
                val page = withContext(Dispatchers.IO) { api.messages(id, SEARCH_PAGE, offset) }
                if (page.isEmpty()) break
                chunks += page
                offset += page.size
                if (page.size < SEARCH_PAGE) break
            }
            chunks.reverse() // pages are newest-first; search reads oldest-first
            transcript = chunks
            transcriptLoading = false
            totalMessages = withContext(Dispatchers.IO) { api.messageCount(id) }
            if (searchOpen) scheduleSearch()
        }
    }

    /** Debounced re-filter: 350 ms after the last keystroke. */
    private fun scheduleSearch() {
        if (!searchOpen) return
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(350)
            runSearch()
        }
    }

    private fun runSearch() {
        val q = searchInput.text.toString().trim()
        searchResults.removeAllViews()
        searchMatches.clear()
        matchIndex = -1
        if (q.isEmpty()) {
            searchCount.visibility = View.GONE
            return
        }
        val scopeNote = TextView(requireContext()).apply {
            text = getString(
                R.string.search_scope,
                if (transcriptLoading) 0 else transcript.size,
                if (totalMessages > 0) totalMessages else transcript.size
            )
            textSize = 11f
            setTextColor(requireContext().getColor(R.color.text_secondary))
        }
        searchResults.addView(scopeNote)
        if (transcriptLoading) return
        val needle = q.lowercase()
        for (m in transcript) {
            if (m.compacted) continue
            val hay = (m.content + "\n" + (m.reasoning ?: "")).lowercase()
            if (!hay.contains(needle)) continue
            searchMatches += m
            if (searchResults.childCount > 30) continue // UI cap; matches stay navigable
            searchResults.addView(matchRow(m, q))
        }
        searchCount.visibility = View.VISIBLE
        searchCount.text = when {
            searchMatches.isEmpty() -> getString(R.string.search_no_matches)
            else -> getString(R.string.search_of, 1, searchMatches.size)
        }
        if (searchMatches.isNotEmpty()) {
            matchIndex = 0
            jumpTo(searchMatches[0])
        }
    }

    /** One compact result line: time · role · fragment with the match bold. */
    private fun matchRow(m: ChatMessage, q: String): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 10)
        }
        val head = TextView(requireContext()).apply {
            text = formatTime(m.timestamp) + " · " + roleLabel(m)
            textSize = 11f
            setTextColor(requireContext().getColor(R.color.text_secondary))
        }
        val body = TextView(requireContext()).apply {
            text = highlight(m.content, q)
            textSize = 13f
            setTextColor(requireContext().getColor(R.color.text_primary))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(head)
        row.addView(body)
        row.setOnClickListener { jumpTo(m) }
        return row
    }

    private fun highlight(text: String, q: String): CharSequence {
        val idx = text.lowercase().indexOf(q.lowercase())
        if (idx < 0 || q.isEmpty()) return text.take(160)
        val from = (idx - 40).coerceAtLeast(0)
        val to = (idx + q.length + 120).coerceAtMost(text.length)
        val span = SpannableString((if (from > 0) "…" else "") + text.substring(from, to))
        val shift = if (from > 0) 1 else 0
        span.setSpan(
            StyleSpan(Typeface.BOLD), idx - from + shift, idx - from + shift + q.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return span
    }

    private fun roleLabel(m: ChatMessage): String = when {
        m.isUser -> getString(R.string.search_role_user)
        m.isTool -> getString(R.string.search_role_tool)
        else -> getString(R.string.search_role_assistant)
    }

    private fun formatTime(ts: Double): String {
        val ms = (ts * 1000).toLong()
        if (ms <= 0) return ""
        return java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }

    private fun stepMatch(dir: Int) {
        if (searchMatches.isEmpty()) return
        matchIndex = (matchIndex + dir + searchMatches.size) % searchMatches.size
        searchCount.text = getString(R.string.search_of, matchIndex + 1, searchMatches.size)
        jumpTo(searchMatches[matchIndex])
    }

    /** Scrolls the transcript to a search hit. When the row is not among the
     *  loaded pages, fetches a window around the nearest preceding user row
     *  (the around endpoint anchors on prompts) and splices it in via
     *  prependOlder — the feed's own paging logic is untouched. */
    private fun jumpTo(m: ChatMessage) {
        if (m.id < 0) return
        val pos = adapter.positionOfRowId(m.id)
        if (pos >= 0) {
            list.scrollToPosition(pos)
            ui.postDelayed({ adapter.flashRow(adapter.positionOfRowId(m.id)) }, 200)
            return
        }
        val id = sessionId ?: return
        scope.launch {
            // nearest user row at or before the hit, inside the indexed transcript
            val anchor = transcript.lastOrNull { it.isUser && it.id in 1..m.id }
                ?: transcript.firstOrNull { it.isUser }
            if (anchor == null) { withContext(Dispatchers.Main) { list.scrollToPosition(0) }; return@launch }
            val window = withContext(Dispatchers.IO) { api.messagesAround(id, anchor.id, 60) }
            if (window.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                olderOffset += adapter.prependOlder(window, hasMore = true)
                val p = adapter.positionOfRowId(m.id)
                if (p >= 0) {
                    list.scrollToPosition(p)
                    ui.postDelayed({ adapter.flashRow(adapter.positionOfRowId(m.id)) }, 200)
                }
            }
        }
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        runCatching {
            pickImageLauncher.launch(Intent.createChooser(intent, getString(R.string.attach_image)))
        }.onFailure { Toast.makeText(context, R.string.no_gallery, Toast.LENGTH_SHORT).show() }
    }

    /** The paperclip now offers both branches: the working image picker and the
     *  any-type file picker. Image logic below is untouched on purpose. */
    private fun showAttachMenu() {
        val options = arrayOf(getString(R.string.attach_photo), getString(R.string.attach_file))
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.attach_menu_title)
            .setItems(options) { _, which -> if (which == 0) pickImage() else pickFile() }
            .show()
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching {
            pickFileLauncher.launch(Intent.createChooser(intent, getString(R.string.attach_file)))
        }.onFailure { Toast.makeText(context, R.string.no_file_picker, Toast.LENGTH_SHORT).show() }
    }

    /** Size for the confirm dialog; adapter has its own copy for the row. */
    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f МБ", bytes / 1048576.0)
        bytes >= 1024 -> "${bytes / 1024} КБ"
        else -> "$bytes Б"
    }

    /**
     * Streams the picked document into a data URL.
     *
     * Returns the reason on failure instead of a bare null: every failure used to
     * surface as «файл слишком большой», which is wrong for an unreadable URI, a
     * provider that refuses the stream, or a name lookup that throws — and it left
     * nothing in the log to tell them apart.
     */
    private fun readFile(uri: Uri): Pair<PendingFile?, String?> {
        var size = -1L
        var name: String? = null
        val bytes = try {
            size = runCatching {
                requireContext().contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                    ?: -1L
            }.getOrDefault(-1L)
            if (size > MAX_FILE_BYTES) {
                return null to "файл ${formatFileSize(size)} — больше лимита ${formatFileSize(MAX_FILE_BYTES)}"
            }
            runCatching {
                requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = c.getString(idx)
                    }
                }
            }
            val bos = java.io.ByteArrayOutputStream()
            requireContext().contentResolver.openInputStream(uri)?.use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    bos.write(buf, 0, n)
                    // Base64 grows by 4/3 and the gateway refuses frames above
                    // ~16 MiB, so the raw cap is well below the file-manager limit.
                    if (bos.size() > MAX_FILE_BYTES) return null to
                        "файл больше ${formatFileSize(MAX_FILE_BYTES)}"
                }
            } ?: return null to "провайдер не отдал содержимое файла"
            bos.toByteArray()
        } catch (e: Exception) {
            return null to (e.message ?: e.javaClass.simpleName)
        }
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val resolvedName = name ?: "file"
        Diag.log("file", "прочитан $resolvedName, ${bytes.size} байт")
        return PendingFile(
            uri, resolvedName,
            if (size >= 0) size else bytes.size.toLong(),
            "data:application/octet-stream;base64,$b64"
        ) to null
    }

    /** System speech recognition: no RECORD_AUDIO permission of our own — the
     *  recognizer app records, we only get text back. Result is appended to the
     *  composer, never sent: the user must see and edit it. */
    private fun startSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speech_prompt))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.speech_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** Downscale to ≤1600px and re-encode PNG/JPEG: a 12MP photo is a 4MB blob
     *  the gateway would refuse, and base64 inflates it another 33%. */
    private fun decodeImage(uri: Uri): PendingImage? = runCatching {
        val source = android.graphics.ImageDecoder.createSource(requireContext().contentResolver, uri)
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

    private fun showPendingImage() {        val frame = view!!.findViewById<android.widget.FrameLayout>(R.id.attachPreview)
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

    /**
     * A picked file: confirm, then stage it visibly.
     *
     * Two buttons, because one was ambiguous. «Прикрепить» stages the file and the
     * user types a message first; «Отправить сейчас» sends it immediately. The old
     * single «Отправить» only staged — nothing appeared in the composer, so it
     * looked like the file had been sent when nothing had left the phone.
     */
    private fun confirmFile(picked: PendingFile) {
        val size = formatFileSize(picked.size)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.attach_file)
            .setMessage(getString(R.string.file_send_confirm, picked.name, size))
            .setPositiveButton(R.string.file_attach_only) { _, _ ->
                pendingFile = picked
                showFileChip()
                updateSendState()
                input.requestFocus()
            }
            .setNeutralButton(R.string.file_send_now) { _, _ ->
                pendingFile = picked
                showFileChip()
                updateSendState()
                submit()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── Slash commands ────────────────────────────────────────────────────────

    /**
     * Shows the command palette while the field holds a bare `/word`.
     *
     * The catalog is fetched from the gateway on every keystroke (debounced), because
     * it is the server that knows about skills and plugin commands — a cached list
     * would go stale the moment a skill is installed.
     */
    private fun onComposerChanged(text: String) {
        val list = slashList ?: return
        val query = Slash.paletteQuery(text)
        if (query == null) {
            list.visibility = View.GONE
            slashJob?.cancel()
            return
        }
        slashJob?.cancel()
        slashJob = scope.launch {
            delay(150) // one keystroke burst, one request
            val id = sessionId ?: return@launch
            val items = withContext(Dispatchers.IO) { api.completeSlash(id, query) }
            if (!isAdded) return@launch
            if (items.isEmpty()) {
                list.visibility = View.GONE
                return@launch
            }
            // Built-ins first: a skill named like a command would otherwise bury it.
            // Capped: the palette is a shortcut, and a 140-row list over the keyboard
            // is a menu you have to scroll before you can type.
            val ordered = items.sortedBy { if (it.kind == "command") 0 else 1 }
                .take(slashRows)
            slashAdapter?.submit(ordered)
            list.visibility = View.VISIBLE
        }
    }

    /** Runs a command typed by hand: local ones in the app, the rest on the server. */
    private fun runSlash(raw: String) {
        val parsed = Slash.parse(raw) ?: return
        hideSlashPalette()
        when {
            parsed.name == Slash.NEW || parsed.name == Slash.RESET -> {
                input.setText("")
                clearDraft()
                hideKeyboard()
                startNewSession()
                addNote(getString(R.string.slash_new_note))
            }
            parsed.name == Slash.CLEAR -> {
                input.setText("")
                clearDraft()
                hideSlashPalette()
            }
            // While a turn runs, `/stop` means the turn — see Slash.kt. Without a
            // turn it goes to the server, where it means "kill background processes".
            parsed.name == Slash.STOP && streaming -> {
                input.setText("")
                clearDraft()
                stopTurn()
                addNote(getString(R.string.slash_stop_turn_note))
            }
            else -> execRemote(raw)
        }
    }

    /** Sends the command to the gateway and shows its answer as a console line. */
    private fun execRemote(command: String) {
        val id = sessionId
        if (id == null) {
            Toast.makeText(context, R.string.slash_no_session, Toast.LENGTH_SHORT).show()
            return
        }
        input.setText("")
        clearDraft()
        hideKeyboard()
        val key = addNote(getString(R.string.slash_running, command))
        scrollToEnd()
        scope.launch {
            val output = withContext(Dispatchers.IO) { api.slashExec(id, command) }
            if (!isAdded) return@launch
            val text = output ?: api.error ?: getString(R.string.slash_failed)
            adapter?.replaceNote(key, text)
            scrollToEnd()
            // Some commands change what the transcript should show (a compaction, a
            // rollback). Re-reading is cheap and beats a stale view.
            if (Slash.refreshesHistory(command)) loadHistory()
        }
    }

    /** Appends a console line and returns its key, so a pending line can be
     *  replaced in place once the answer arrives. */
    private fun addNote(text: String): String = adapter?.addNote(text) ?: ""

    private fun hideSlashPalette() {
        slashJob?.cancel()
        slashList?.visibility = View.GONE
    }

    private fun setupSlashPalette() {
        val list = view?.findViewById<RecyclerView>(R.id.slashList) ?: return
        val adapter = SlashAdapter { item -> pickSlash(item) }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        slashList = list
        slashAdapter = adapter
    }

    /** Tap on a palette row: fill the field when the command wants arguments,
     *  run it straight away when it does not. */
    private fun pickSlash(item: SlashItem) {
        val command = "/" + item.text
        if (Slash.takesArgs(item)) {
            input.setText("$command ")
            input.setSelection(input.text.length)
            hideSlashPalette()
        } else {
            runSlash(command)
        }
    }

    /**
     * Opens a file the agent handed over.
     *
     * The bytes are fetched through the gateway (the phone has no access to the
     * server's filesystem), written into the app cache under the original name, and
     * handed to whatever app owns that type. A type nobody claims falls back to the
     * share sheet instead of an error — the file is on the phone either way, which
     * is the point of "the file arrived".
     */
    private fun openAttachment(path: String) {
        val name = MediaTag.name(path)
        Toast.makeText(context, getString(R.string.attachment_downloading, name), Toast.LENGTH_SHORT).show()
        scope.launch {
            val dataUrl = withContext(Dispatchers.IO) { api.readDataUrl(path) }
            if (dataUrl == null) {
                ui.post { Toast.makeText(context, R.string.attachment_failed, Toast.LENGTH_LONG).show() }
                return@launch
            }
            val file = withContext(Dispatchers.IO) { saveToCache(name, dataUrl) }
            if (file == null) {
                ui.post { Toast.makeText(context, R.string.attachment_failed, Toast.LENGTH_LONG).show() }
                return@launch
            }
            Diag.log("media", "скачан $name, ${file.length()} байт")
            ui.post { showDownloaded(file) }
        }
    }

    private fun saveToCache(name: String, dataUrl: String): java.io.File? = runCatching {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return@runCatching null
        val bytes = android.util.Base64.decode(dataUrl.substring(comma + 1), android.util.Base64.DEFAULT)
        val dir = java.io.File(requireContext().cacheDir, "media").apply { mkdirs() }
        // Same name from two turns must not overwrite a file the viewer may still
        // hold open — keep both.
        var target = java.io.File(dir, name)
        var counter = 2
        while (target.exists()) {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            target = java.io.File(dir, "$base-$counter$ext")
            counter++
        }
        target.writeBytes(bytes)
        target
    }.getOrNull()

    private fun showDownloaded(file: java.io.File) {
        val context = context ?: return
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".files", file
        )
        val mime = context.contentResolver.getType(uri) ?: guessMime(file.name)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val opened = runCatching { context.startActivity(view) }.isSuccess
        if (opened) return
        // No app claims this type: offer the file itself instead of a dead end.
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, getString(R.string.attachment_open)))
        }.onFailure {
            Toast.makeText(context, R.string.attachment_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "txt", "log", "md" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }

    /** Drops a staged file/image and hides their views. Used when the chat
     *  changes and after a send: a chip that outlives its file is a lie. */
    private fun clearStagedAttachments() {
        pendingFile = null
        pendingImage = null
        showFileChip()
        view?.findViewById<android.widget.FrameLayout>(R.id.attachPreview)?.visibility = View.GONE
        updateSendState()
    }

    /** The staged file, visible above the composer with its size and a cross. */
    private fun showFileChip() {
        val chip = view?.findViewById<View>(R.id.fileChip) ?: return
        val label = view?.findViewById<TextView>(R.id.fileChipName) ?: return
        val file = pendingFile
        if (file == null) {
            chip.visibility = View.GONE
            return
        }
        label.text = file.name + " · " + formatFileSize(file.size)
        chip.visibility = View.VISIBLE
        view?.findViewById<View>(R.id.fileChipRemove)?.setOnClickListener {
            pendingFile = null
            chip.visibility = View.GONE
            updateSendState()
        }
    }

    private fun submit() {
        val text = input.text.toString().trim()
        // A slash command is not a prompt: it must not reach the agent as text, and
        // it must not consume a staged attachment.
        if (text.startsWith("/") && Slash.parse(text) != null) {
            runSlash(text)
            return
        }
        if (streaming) return
        if (text.isEmpty() && pendingImage == null && pendingFile == null) return
        input.setText("")
        clearDraft()
        hideKeyboard()

        val image = pendingImage
        pendingImage = null
        view?.findViewById<android.widget.FrameLayout>(R.id.attachPreview)?.visibility = View.GONE
        val file = pendingFile
        pendingFile = null
        showFileChip()
        Diag.log(
            "file",
            if (file != null) "отправка ${file.name}, ${file.size} байт"
            else "в отправке файла нет"
        )

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
                renderEmptyState()
                sendTurn(created, text, image, file)
            }
            return
        }
        sendTurn(id, text, image, file)
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
        // A steer is text-only on the wire: `prompt.steer_queued` has no attachment
        // field. Sending the text anyway silently dropped the staged file and left
        // its chip hanging above the composer, so it looked like the file had been
        // sent. Refuse and say why instead of losing it.
        if (pendingFile != null || pendingImage != null) {
            Diag.log("file", "steer отклонён: есть вложение")
            Toast.makeText(context, R.string.steer_no_attachments, Toast.LENGTH_LONG).show()
            return
        }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        clearDraft()
        if (!steerHintShown) {
            steerHintShown = true
            Toast.makeText(context, R.string.steer_hint_toast, Toast.LENGTH_SHORT).show()
        }
        adapter.addSteer(text)
        scrollToEnd()
        scope.launch {
            val receipt = withContext(Dispatchers.IO) { api.submitQueued(id, text) }
            val admissionId = receipt?.optString("admission_id").orEmpty()
            if (receipt == null || admissionId.isEmpty()) {
                // The server took nothing: restore the text so it is not lost
                // and mark the row as never sent — "queued" would lie.
                input.setText(text)
                adapter.setSteerState(text, "not_sent")
                return@launch
            }
            val steerResult = withContext(Dispatchers.IO) { api.steerQueued(id, admissionId) }
            val state = if (steerResult?.optString("status") == "started") "started"
                else if (steerResult?.optString("status") == "queued") "queued" else "failed"
            adapter.setSteerState(text, state)
        }
    }

    private fun sendTurn(id: String, text: String, image: PendingImage? = null, file: PendingFile? = null) {
        fileRowKey = adapter.addLocalUser(text, image?.uri, file?.name, file?.size ?: 0L)
        scrollToEnd()
        streaming = true
        fallbackTook = false
        // A long answer has to survive the screen going off: the socket lives in
        // this process, and without a foreground service Android is free to freeze
        // it the moment the chat is not visible.
        TurnWatchService.start(requireContext(), id, title.text.toString(), currentModel)
        Running.started(requireContext(), id)
        sawDeltaThisTurn = false
        baselineText = null
        // A stale index would repaint a row from the previous turn instead of the
        // new answer, which looks exactly like "the chat stopped updating".
        pendingIndex = -1
        updateSendState()
        status.visibility = View.VISIBLE
        status.text = getString(if (file != null) R.string.sending_file else R.string.waiting)
        if (file != null) {
            failedFile = null
            failedText = ""
        }

        streamJob = scope.launch {
            try {
                // What the session held before this turn. Captured here, not at
                // fallback time: by then the answer may already be stored and the
                // two would be indistinguishable.
                baselineText = withContext(Dispatchers.IO) { api.latestAssistantText(id) }
                withContext(Dispatchers.IO) {
                    api.streamTurn(id, text, image?.base64, image?.filename,
                        file?.dataUrl, file?.name) { event -> onEvent(event) }
                }
                if (file != null) {
                    ui.post { status.text = getString(R.string.file_sent) }
                }
            } catch (e: Exception) {
                ui.post { onEvent(ChatEvent.Error(e.message ?: "Ошибка")) }
            }
        }
        startHistoryFallback(id)
        if (!resumed) {
            Notifications.showWorking(requireContext(), id, title.text.toString(), getString(R.string.waiting), currentModel)
        }
    }

    /** A failed FILE turn keeps the bytes so «Повторить» resends verbatim;
     *  network vs server refusal is told apart by the RPC error text. */
    private fun onFileTurnFailed(file: PendingFile, text: String, why: String?) {
        failedFile = file
        failedText = text
        val msg = if (why != null && why.contains("сокет", ignoreCase = true))
            getString(R.string.no_connection) else getString(R.string.file_rejected)
        val key = fileRowKey
        if (key != null) adapter.setUserFileNote(key, "📎 ${file.name} — $msg")
        status.text = msg
        val retry = AlertDialog.Builder(requireContext())
            .setTitle(R.string.retry)
            .setMessage("${file.name}: $msg")
            .setPositiveButton(R.string.retry) { _, _ ->
                val id = sessionId
                val f = failedFile
                if (id != null && f != null) {
                    failedFile = null
                    sendTurn(id, failedText, null, f)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        retry.show()
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
            Notifications.showAnswer(requireContext(), sessionId, title.text.toString(), lastFinalText.take(300))
        } else {
            Notifications.cancelWorking(requireContext())
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
        // New tokens below the fold are what the down-button exists for.
        if (scrolledUp) updateDownButton()
        // In the shade, the answer should be readable while it is being written,
        // not just announced when it is done.
        if (!resumed && System.currentTimeMillis() - lastNotifyMs > 4000) {
            lastNotifyMs = System.currentTimeMillis()
            val tail = adapter.currentAssistantText().takeLast(200).trim()
            if (tail.isNotEmpty()) {
                Notifications.showWorking(requireContext(), sessionId, title.text.toString(), "…" + tail, currentModel)
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
                ui.post {
                    adapter.notifyItemInserted(pendingIndex)
                    status.text = event.name
                    scrollToEnd()
                }
            }

            is ChatEvent.ToolFinished -> ui.post {
                status.text = event.name
            }

            is ChatEvent.Completed -> {
                lastFinalText = event.content
                adapter.finishTurn(event.content)
                ui.post { adapter.notifyDataSetChanged() }
                maybeAutoTitle(event.content)
            }

            is ChatEvent.Error -> ui.post {
                streaming = false
                updateSendState()
                status.visibility = View.VISIBLE
                status.text = event.message
                // Away from the screen, a failed turn used to produce nothing at all:
                // from the phone an error looked exactly like a very slow answer.
                if (!resumed) {
                    Notifications.showError(
                        requireContext(), sessionId, title.text.toString(), event.message
                    )
                }
                val f = failedFile
                if (f != null) {
                    // The turn carried a file: offer a verbatim retry instead of
                    // a bare toast the user cannot act on.
                    onFileTurnFailed(f, failedText, event.message)
                } else {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
            }

            is ChatEvent.Done -> ui.post {
                streaming = false
                // The turn is over, so the protection is no longer needed and the
                // card it holds is replaced by the answer notification below.
                TurnWatchService.stop(requireContext())
                sessionId?.let { Running.finished(requireContext(), it) }
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
                        Notifications.showAnswer(requireContext(), sessionId, title.text.toString(), snippet.take(400))
                    } else {
                        scope.launch {
                            val stored = withContext(Dispatchers.IO) {
                                sessionId?.let { api.latestAssistantText(it) }.orEmpty()
                            }
                            Notifications.showAnswer(
                                requireContext(), sessionId, title.text.toString(),
                                stored.take(400).ifBlank { "Ответ готов" }
                            )
                        }
                    }
                } else {
                    Notifications.cancelWorking(requireContext())
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
            if (newTitle != null) ui.post { title.text = newTitle }
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
        TurnWatchService.stop(requireContext())
        sessionId?.let { Running.finished(requireContext(), it) }
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
                Toast.makeText(context, api.error ?: "Список моделей не получен", Toast.LENGTH_LONG).show()
                return@launch
            }
            ui.post { activity?.let { ModelPickerDialog(it, providers, currentModel) { picked -> switchModel(id, picked) }.show() } }
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
                Toast.makeText(context, getString(R.string.model_switched, result.model ?: slug), Toast.LENGTH_SHORT).show()
            } else {
                currentModel = previous
                setModelChip(previous)
                Toast.makeText(context, getString(R.string.model_switch_failed, result.reason ?: ""), Toast.LENGTH_LONG).show()
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
        ModesDialog(requireActivity(), api, id) { key, value ->
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
        AlertDialog.Builder(requireContext())
            .setTitle(title.text)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askRename(id)
                    1 -> scope.launch {
                        withContext(Dispatchers.IO) { api.setPinned(id, true) }
                        Toast.makeText(context, R.string.pin, Toast.LENGTH_SHORT).show()
                    }
                    2 -> showModes()
                    3 -> askDelete(id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askRename(id: String) {
        val field = EditText(requireContext()).apply {
            setText(title.text)
            setSingleLine(true)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(requireContext())
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
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(title.text)
            .setPositiveButton(R.string.delete) { _, _ ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { api.deleteSession(id) }
                    if (ok) {
                        Toast.makeText(context, R.string.deleted, Toast.LENGTH_SHORT).show()
                        goBackToList()
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
        send.alpha = if (streaming || input.text.isNotBlank() || pendingImage != null || pendingFile != null) 1f else 0.4f
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

    /** Down-button: shown while the last visible row is not the transcript's
     *  end; accent tint marks "new content below" while a turn is streaming. */
    private fun updateDownButton() {
        val layout = list.layoutManager as? LinearLayoutManager ?: return
        val last = layout.findLastVisibleItemPosition()
        val atBottom = last < 0 || last >= adapter.itemCount - 2
        scrolledUp = !atBottom
        if (scrolledUp) {
            btnDown.visibility = View.VISIBLE
            btnDown.setColorFilter(requireContext().getColor(if (streaming) R.color.accent else R.color.text_secondary))
            btnDown.contentDescription =
                getString(if (streaming) R.string.new_messages else R.string.scroll_down)
        } else {
            btnDown.visibility = View.GONE
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
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
            Notifications.showRequest(requireContext(), sessionId, title.text.toString(), request)
        }
    }

    private fun onRequestCancelled(requestId: String) {
        if (!adapter.hasQuestion(requestId)) return
        adapter.resolveQuestion(requestId, getString(R.string.question_cancelled))
        Notifications.cancelRequest(requireContext(), Notifications.ID_REQUEST_BASE + requestId.hashCode() % 1000)
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
        Notifications.cancelRequest(requireContext(), Notifications.ID_REQUEST_BASE + requestId.hashCode() % 1000)
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
        // The list owns the message count; this just says "this one was on
        // screen", so the next listing can mark it read.
        sessionId?.let { ReadMarks.noteOpened(requireContext(), it) }
        // Opening a chat that is not streaming anything means the turn this phone
        // started is over: drop the local "running" entry, or the row in the list
        // keeps pulsing after the answer has been read.
        if (!streaming) sessionId?.let { Running.finished(requireContext(), it) }
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
        // Leaving: whatever was written while the chat was open has been seen.
        sessionId?.let { ReadMarks.noteOpened(requireContext(), it) }
        saveDraft()
        if (streaming) {
            Notifications.showWorking(requireContext(), sessionId, title.text.toString(), getString(R.string.waiting)
            )
        }
    }

    override fun onDestroy() {
        saveDraft()
        streamJob?.cancel()
        // Only when nothing is running. Leaving the screen mid-turn must NOT drop
        // the foreground service — that is the whole point of it.
        if (context != null && !streaming) TurnWatchService.stop(requireContext())
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

    override fun onDetach() {
        act = null
        super.onDetach()
    }

    companion object {
        /**
         * Which session the page opens with.
         *
         * A parked handoff wins over the launch arguments because the two are written
         * at different moments: args once, when the fragment object is created, and
         * the handoff whenever a row is tapped. Reading args only — the original
         * behaviour — is why the first tap after launch opened an empty «Новый
         * сеанс»: the fragment had already been created with no args, and nothing
         * ever revisited them.
         */
        fun resolveStart(
            parked: Pair<String, String?>?,
            argId: String?,
            argTitle: String?
        ): Pair<String?, String?> {
            if (parked != null) return parked
            return argId to argTitle
        }

        const val EXTRA_SESSION_ID = "ru.evgen.hermes.EXTRA_SESSION_ID"
        const val EXTRA_SESSION_TITLE = "ru.evgen.hermes.EXTRA_SESSION_TITLE"
        const val REQ_PICK_IMAGE = 4101
        const val REQ_SPEECH = 4102
        const val REQ_PICK_FILE = 4103
        /** Client-side cap for the file branch. The gateway itself accepts 256 MB,
         *  but `file.attach` rides one WebSocket frame whose ceiling is ~16 MiB —
         *  and base64 inflates by a third, so 12 MB raw is the honest maximum.
         *  Above that the frame is rejected (or the socket drops) with no useful
         *  message, which is why the phone refuses first and says so. */
        const val MAX_FILE_BYTES = 12L * 1024 * 1024

        /** Rows per history page. 200 is ~0.5 MB of JSON on a busy session and
         *  still parses in well under a second; older pages load on demand. */
        private const val PAGE = 200

        /** Page size for the search index — same endpoint, slightly smaller
         *  pages so the first query starts filtering sooner. */
        private const val SEARCH_PAGE = 100

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
