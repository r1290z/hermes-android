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
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var adapter: ChatAdapter

    private var sessionId: String? = null
    private var streaming = false
    private var runId: String? = null
    private var streamJob: Job? = null

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

        api = ApiClient(this)
        list = findViewById(R.id.messages)
        input = findViewById(R.id.input)
        send = findViewById(R.id.btnSend)
        title = findViewById(R.id.title)
        status = findViewById(R.id.status)

        adapter = ChatAdapter(this) { url -> openLink(url) }
        val layout = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.layoutManager = layout
        list.adapter = adapter
        list.itemAnimator = null

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        title.text = intent.getStringExtra(EXTRA_SESSION_TITLE) ?: getString(R.string.new_session)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnReload).setOnClickListener { loadHistory() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showActions() }
        send.setOnClickListener { if (streaming) stopTurn() else submit() }

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateSendState()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        if (sessionId != null) loadHistory()
        updateSendState()
    }

    // ── History ──────────────────────────────────────────────────────────────

    private fun loadHistory() {
        val id = sessionId ?: return
        status.visibility = View.VISIBLE
        status.text = getString(R.string.loading)
        scope.launch {
            val messages = withContext(Dispatchers.IO) { api.messages(id) }
            adapter.submit(messages)
            status.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
            if (messages.isEmpty()) status.text = getString(R.string.empty_chat)
            scrollToEnd()
        }
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || streaming) return
        input.setText("")
        hideKeyboard()

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
                sendTurn(created, text)
            }
            return
        }
        sendTurn(id, text)
    }

    private fun sendTurn(id: String, text: String) {
        adapter.addLocalUser(text)
        scrollToEnd()
        streaming = true
        updateSendState()
        status.visibility = View.VISIBLE
        status.text = getString(R.string.waiting)

        streamJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    api.streamTurn(id, text) { event -> onEvent(event) }
                }
            } catch (e: Exception) {
                runOnUiThread { onEvent(ChatEvent.Error(e.message ?: "Ошибка")) }
            }
        }
        if (!resumed) {
            Notifications.showWorking(this, id, title.text.toString(), getString(R.string.waiting))
        }
    }

    private fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.Started -> runId = event.runId.ifEmpty { runId }

            is ChatEvent.Delta -> {
                pendingIndex = adapter.appendDelta(event.text)
                scheduleRefresh()
            }

            is ChatEvent.Reasoning -> {
                pendingIndex = adapter.appendReasoning(event.text)
                scheduleRefresh()
            }

            is ChatEvent.Commentary -> {
                if (event.text.isNotBlank()) {
                    pendingIndex = adapter.appendDelta("\n\n" + event.text + "\n\n")
                    scheduleRefresh()
                }
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
                runId = null
                updateSendState()
                status.visibility = View.GONE
                adapter.notifyDataSetChanged()
                scrollToEnd()
                // Away from the screen when the turn ended: the answer is what
                // the notification should say. On screen: nothing to announce.
                if (!resumed) {
                    Notifications.showAnswer(
                        this, sessionId, title.text.toString(),
                        lastFinalText.take(300)
                    )
                } else {
                    Notifications.cancelWorking(this)
                }
            }
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
                scrollToEnd()
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

    // ── Session actions ──────────────────────────────────────────────────────

    private fun showActions() {
        val id = sessionId ?: return
        val items = arrayOf<CharSequence>(
            getString(R.string.rename),
            getString(R.string.pin),
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
                    2 -> askDelete(id)
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
        send.alpha = if (streaming || input.text.isNotBlank()) 1f else 0.4f
    }

    private fun scrollToEnd() {
        if (adapter.itemCount == 0) return
        list.scrollToPosition(adapter.itemCount - 1)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun openLink(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
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
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SESSION_ID = "ru.evgen.hermes.EXTRA_SESSION_ID"
        const val EXTRA_SESSION_TITLE = "ru.evgen.hermes.EXTRA_SESSION_TITLE"
    }
}
