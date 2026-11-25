package ru.evgen.hermes.kanban

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.evgen.hermes.R

/**
 * The board as tabs, not seven squeezed columns: a phone screen fits one
 * column at a time, so a chip row with live counters replaces the horizontal
 * pan of the web UI. Chips follow [column_order] from /api/board.
 */
class KanbanBoardActivity : Activity() {

    private lateinit var api: KanbanApi
    private lateinit var chips: LinearLayout
    private lateinit var status: TextView
    private lateinit var adapter: TaskListAdapter
    private lateinit var noToken: LinearLayout

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var streaming = false
    private var loading = false
    private var selectedStatus: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = KanbanApi(this)
        setContentView(R.layout.activity_kanban)

        chips = findViewById(R.id.chips)
        status = findViewById(R.id.status)
        noToken = findViewById(R.id.noToken)
        adapter = TaskListAdapter { openTask(it) }

        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@KanbanBoardActivity)
            adapter = this@KanbanBoardActivity.adapter
            itemAnimator = null
        }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener { load() }
        findViewById<View>(R.id.btnToken).setOnClickListener { showTokenDialog() }
    }

    override fun onResume() {
        super.onResume()
        if (!api.hasToken()) {
            noToken.visibility = View.VISIBLE
            findViewById<View>(R.id.chipsScroll).visibility = View.GONE
            findViewById<RecyclerView>(R.id.list).visibility = View.GONE
            return
        }
        noToken.visibility = View.GONE
        findViewById<View>(R.id.chipsScroll).visibility = View.VISIBLE
        findViewById<RecyclerView>(R.id.list).visibility = View.VISIBLE
        load()
        startStream()
    }

    override fun onPause() {
        super.onPause()
        streaming = false
    }

    private fun load() {
        if (loading || !api.hasToken()) return
        loading = true
        if (KanbanStore.board == null) {
            status.visibility = View.VISIBLE
            status.text = getString(R.string.kanban_loading)
        }
        scope.launch {
            try {
                val board = withContext(Dispatchers.IO) { api.board() }
                KanbanStore.replace(board)
                loading = false
                render()
            } catch (e: Exception) {
                loading = false
                if (KanbanStore.board == null) {
                    status.visibility = View.VISIBLE
                    status.text = getString(R.string.kanban_error, e.message ?: e.toString())
                } else {
                    Toast.makeText(this@KanbanBoardActivity,
                        getString(R.string.kanban_error, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startStream() {
        if (streaming) return
        streaming = true
        scope.launch {
            while (streaming) {
                try {
                    withContext(Dispatchers.IO) {
                        api.streamEvents(KanbanStore.latestEventId) { frame ->
                            if (KanbanStore.applyFrame(frame)) load()
                        }
                    }
                } catch (_: Exception) {
                    // Stream drop is routine; back off and reconnect while visible.
                }
                if (!streaming) break
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    private fun render() {
        status.visibility = View.GONE
        val order = KanbanStore.columnOrder
        if (selectedStatus == null || selectedStatus !in order) {
            selectedStatus = order.firstOrNull { KanbanStore.tasksOf(it).isNotEmpty() } ?: order.firstOrNull()
        }
        buildChips(order)
        val tasks = KanbanStore.tasksOf(selectedStatus ?: return)
        adapter.submit(tasks)
        if (tasks.isEmpty()) {
            status.visibility = View.VISIBLE
            status.text = getString(R.string.kanban_empty_column)
        }
    }

    private fun buildChips(order: List<String>) {
        chips.removeAllViews()
        for (col in order) {
            val count = KanbanStore.tasksOf(col).size
            val chip = TextView(this).apply {
                text = " ${label(col)} $count "
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(36, 12, 36, 12)
                val active = col == selectedStatus
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(getColor(if (active) ru.evgen.hermes.R.color.bubble_user_text else ru.evgen.hermes.R.color.text_secondary))
                setBackgroundResource(if (active) R.drawable.bg_chip_active else R.drawable.bg_chip)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 24 }
                layoutParams = lp
                setOnClickListener {
                    selectedStatus = col
                    render()
                }
            }
            chips.addView(chip)
        }
    }

    private fun label(status: String): String = when (status) {
        "scheduled" -> "Запланировано"
        "triage" -> "Триаж"
        "todo" -> "Todo"
        "ready" -> "Ready"
        "running" -> "Running"
        "blocked" -> "Blocked"
        "done" -> "Done"
        else -> status
    }

    private fun openTask(id: String) {
        startActivity(Intent(this, KanbanTaskActivity::class.java)
            .putExtra(KanbanTaskActivity.EXTRA_TASK_ID, id))
    }

    fun showTokenDialog() {
        val serverField = EditText(this).apply {
            setText(api.base)
            setSingleLine(true)
            hint = getString(R.string.kanban_server_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(48, 32, 48, 32)
        }
        val field = EditText(this).apply {
            setText(api.token)
            setSingleLine(true)
            hint = getString(R.string.kanban_token_hint)
            setPadding(48, 32, 48, 32)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(serverField)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.kanban_token_title)
            .setMessage(R.string.kanban_token_message)
            .setView(box)
            .setPositiveButton(R.string.kanban_check) { _, _ ->
                val value = field.text.toString().trim()
                val server = serverField.text.toString().trim()
                if (value.isEmpty() || server.isEmpty()) return@setPositiveButton
                api.base = server
                api.token = value
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try { api.stats(); true } catch (e: Exception) { false }
                    }
                    if (ok) {
                        KanbanStore.clear()
                        Toast.makeText(this@KanbanBoardActivity, R.string.kanban_token_ok, Toast.LENGTH_SHORT).show()
                        onResume()
                    } else {
                        Toast.makeText(this@KanbanBoardActivity, R.string.kanban_token_bad, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        streaming = false
        scope.cancel()
        super.onDestroy()
    }

    // ── List ──────────────────────────────────────────────────────────────────

    private class TaskListAdapter(private val onOpen: (String) -> Unit) :
        RecyclerView.Adapter<TaskListAdapter.Holder>() {

        private val tasks = mutableListOf<JSONObject>()

        fun submit(list: List<JSONObject>) {
            tasks.clear()
            tasks.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_kanban_task, parent, false)
            return Holder(view)
        }

        override fun getItemCount() = tasks.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val t = tasks[position]
            val context = holder.itemView.context
            holder.id.text = t.optString("id")
            holder.title.text = t.optString("title")
            val assignee = t.optString("assignee")
            holder.assignee.text = if (assignee.isEmpty()) "—" else "@$assignee"
            holder.time.text = formatAge(context, t)
            val progress = t.optJSONObject("progress")
            if (progress != null && progress.optInt("total", 0) > 0) {
                holder.progress.visibility = View.VISIBLE
                holder.progress.text = "${progress.optInt("done")}/${progress.optInt("total")}"
            } else {
                holder.progress.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { onOpen(t.optString("id")) }
        }

        /** "2 ч 15 м" since started_at/created_at — the age is what you scan for. */
        private fun formatAge(context: android.content.Context, t: JSONObject): String {
            val started = t.optLong("started_at", 0L)
            val created = t.optLong("created_at", 0L)
            val from = if (started > 0) started else created
            if (from <= 0) return ""
            val mins = (System.currentTimeMillis() / 1000 - from) / 60
            return when {
                t.optString("status") == "done" && t.optLong("completed_at", 0L) > 0 -> {
                    val total = (t.optLong("completed_at") - from) / 60
                    "✓ $total мин"
                }
                mins < 60 -> "$mins мин"
                mins < 1440 -> "${mins / 60} ч ${mins % 60} м"
                else -> "${mins / 1440} д ${mins % 1440 / 60} ч"
            }
        }

        private class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val id: TextView = view.findViewById(R.id.taskId)
            val title: TextView = view.findViewById(R.id.taskTitle)
            val assignee: TextView = view.findViewById(R.id.taskAssignee)
            val time: TextView = view.findViewById(R.id.taskTime)
            val progress: TextView = view.findViewById(R.id.taskProgress)
        }
    }
}
