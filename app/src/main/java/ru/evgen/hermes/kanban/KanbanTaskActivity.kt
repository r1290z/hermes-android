package ru.evgen.hermes.kanban

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.evgen.hermes.Markdown
import ru.evgen.hermes.R

/**
 * Task detail: full body rendered as markdown through the existing [Markdown]
 * renderer, comments, comment posting and the three lifecycle buttons that the
 * current status actually permits (a button that would 400 is simply absent).
 */
class KanbanTaskActivity : Activity() {

    private lateinit var api: KanbanApi
    private lateinit var bodyBlocks: LinearLayout
    private lateinit var comments: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var runsBody: TextView
    private lateinit var runsToggle: TextView
    private lateinit var statusView: TextView
    private lateinit var commentField: android.widget.EditText

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var taskId: String = ""
    private var task: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = KanbanApi(this)
        setContentView(R.layout.activity_kanban_task)

        taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: run { finish(); return }
        bodyBlocks = findViewById(R.id.bodyBlocks)
        comments = findViewById(R.id.comments)
        actions = findViewById(R.id.actions)
        runsBody = findViewById(R.id.runsBody)
        runsToggle = findViewById(R.id.runsToggle)
        statusView = findViewById(R.id.status)
        commentField = findViewById(R.id.commentField)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener { load() }
        findViewById<View>(R.id.btnSend).setOnClickListener { sendComment() }
        runsToggle.setOnClickListener {
            val show = runsBody.visibility != View.VISIBLE
            runsBody.visibility = if (show) View.VISIBLE else View.GONE
            runsToggle.text = getString(if (show) R.string.kanban_runs_hide else R.string.kanban_runs)
        }
    }

    override fun onResume() {
        super.onResume()
        if (api.hasToken()) load() else finish()
    }

    private fun load() {
        statusView.text = getString(R.string.loading)
        scope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { api.task(taskId) }
                task = detail.optJSONObject("task") ?: detail
                render(task!!)
                val runs = withContext(Dispatchers.IO) { api.runs(taskId) }
                renderRuns(runs)
                statusView.text = ""
            } catch (e: Exception) {
                statusView.text = getString(R.string.kanban_error, e.message ?: e.toString())
            }
        }
    }

    private fun render(detail: JSONObject) {
        findViewById<TextView>(R.id.title).text = detail.optString("title")
        val meta = StringBuilder()
        meta.append(detail.optString("id"))
        val assignee = detail.optString("assignee")
        if (assignee.isNotEmpty()) meta.append("  @").append(assignee)
        meta.append("  ").append(labelOf(detail.optString("status")))
        findViewById<TextView>(R.id.meta).text = meta.toString()
        renderMarkdown(detail.optString("body"))
        renderActions(detail)
        renderComments(detail.optJSONArray("comments"))
    }

    private fun renderActions(detail: JSONObject) {
        actions.removeAllViews()
        val status = detail.optString("status")
        // Mirrors the server rules: complete needs running/ready/todo/triage/
        // blocked (the CLI completes blocked tasks to unblock), block needs a
        // non-terminal status, unblock only blocked.
        val canComplete = status != "done"
        val canBlock = status != "done" && status != "blocked"
        val canUnblock = status == "blocked"
        if (canComplete) addButton(getString(R.string.kanban_complete)) { lifecycle("complete") }
        if (canUnblock) addButton(getString(R.string.kanban_unblock)) { lifecycle("unblock") }
        if (canBlock) addButton(getString(R.string.kanban_block)) { lifecycle("block") }
    }

    private fun addButton(label: String, onClick: () -> Unit) {
        val btn = android.widget.Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 12 }
        }
        actions.addView(btn)
    }
    private fun lifecycle(action: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (action) {
                        "complete" -> api.complete(taskId)
                        "unblock" -> api.unblock(taskId)
                        else -> api.block(taskId, null)
                    }
                }
                load()
            } catch (e: Exception) {
                Toast.makeText(this@KanbanTaskActivity,
                    getString(R.string.kanban_error, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun labelOf(status: String): String = when (status) {
        "scheduled" -> "Запланировано"
        "triage" -> "Триаж"
        "todo" -> "Todo"
        "ready" -> "Ready"
        "running" -> "Running"
        "blocked" -> "Blocked"
        "done" -> "Done"
        else -> status
    }

    private fun renderRuns(runs: JSONArray) {
        if (runs.length() == 0) {
            runsToggle.visibility = View.GONE
            runsBody.visibility = View.GONE
            return
        }
        runsToggle.visibility = View.VISIBLE
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            val id = run.optLong("id", -1)
            val outcome = run.optString("outcome").ifEmpty { "running" }
            val started = run.optString("started_at")
            val ended = run.optString("ended_at")
            sb.append("run ").append(id)
                .append("  ").append(outcome)
                .append("  ").append(started)
            if (ended.isNotEmpty() && ended != "null") sb.append(" → ").append(ended)
            sb.append('\n')
            val error = run.optString("error")
            if (error.isNotEmpty() && error != "null") sb.append("  err: ").append(error.take(160)).append('\n')
            val summary = run.optString("summary")
            if (summary.isNotEmpty() && summary != "null") sb.append("  ").append(summary.take(160)).append('\n')
        }
        runsBody.text = sb.toString().trimEnd()
    }

    private fun renderComments(list: JSONArray?) {
        comments.removeAllViews()
        if (list == null || list.length() == 0) {
            val empty = TextView(this).apply {
                text = getString(R.string.kanban_no_comments)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                setPadding(0, 4, 0, 4)
            }
            comments.addView(empty)
            return
        }
        val inflater = LayoutInflater.from(this)
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            val view = inflater.inflate(R.layout.item_kanban_comment, comments, false)
            view.findViewById<TextView>(R.id.author).text = c.optString("author")
            view.findViewById<TextView>(R.id.commentBody).text = c.optString("body")
            comments.addView(view)
        }
    }

    private fun sendComment() {
        val text = commentField.text.toString().trim()
        if (text.isEmpty()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api.comment(taskId, text) }
                commentField.setText("")
                load()
            } catch (e: Exception) {
                Toast.makeText(this@KanbanTaskActivity,
                    getString(R.string.kanban_error, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderMarkdown(source: String) {
        bodyBlocks.removeAllViews()
        if (source.isEmpty() || source == "null") return
        val inflater = LayoutInflater.from(this)
        for (part in Markdown.parse(source)) {
            when (part) {
                is Markdown.Block.Paragraph -> {
                    val tv = newBlock(inflater)
                    tv.text = Markdown.inline(
                        part.text,
                        codeBackground = getColor(R.color.tool_bg),
                        linkColor = getColor(R.color.accent)
                    )
                    tv.movementMethod = LinkMovementMethod.getInstance()
                    bodyBlocks.addView(tv)
                }
                is Markdown.Block.Heading -> {
                    val tv = newBlock(inflater)
                    tv.text = part.text
                    tv.textSize = when (part.level) {
                        1 -> 20f; 2 -> 18f; else -> 16f
                    }
                    tv.setTypeface(Typeface.DEFAULT_BOLD)
                    tv.setPadding(0, 12, 0, 4)
                    bodyBlocks.addView(tv)
                }
                is Markdown.Block.Bullets -> {
                    val tv = newBlock(inflater)
                    tv.text = Markdown.bullets(part.items, part.ordered)
                    bodyBlocks.addView(tv)
                }
                is Markdown.Block.Quote -> {
                    val tv = newBlock(inflater)
                    tv.text = part.text
                    tv.setPadding(20, 4, 8, 4)
                    tv.setBackgroundResource(R.drawable.bg_quote)
                    tv.setTextColor(getColor(R.color.text_secondary))
                    bodyBlocks.addView(tv)
                }
                is Markdown.Block.Code -> {
                    val tv = newBlock(inflater)
                    tv.typeface = Typeface.MONOSPACE
                    tv.text = part.code
                    tv.textSize = 12f
                    tv.setBackgroundResource(R.drawable.bg_code)
                    tv.setPadding(12, 8, 12, 8)
                    tv.setTextColor(getColor(R.color.code_text))
                    bodyBlocks.addView(tv)
                }
                is Markdown.Block.Table -> {
                    // A body table stays readable as pipe text in monospace.
                    val tv = newBlock(inflater)
                    tv.typeface = Typeface.MONOSPACE
                    tv.text = buildString {
                        append("| ").append(part.headers.joinToString(" | ")).append(" |")
                        append('\n')
                        part.rows.forEach { append("| ").append(it.joinToString(" | ")).append(" |\n") }
                    }
                    tv.textSize = 11f
                    bodyBlocks.addView(tv)
                }
                is Markdown.Block.Rule -> {
                    val divider = View(this)
                    divider.setBackgroundColor(getColor(R.color.divider))
                    bodyBlocks.addView(divider, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { topMargin = 12; bottomMargin = 12 })
                }
            }
        }
    }

    private fun newBlock(inflater: LayoutInflater): TextView =
        inflater.inflate(R.layout.block_text, bodyBlocks, false) as TextView

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TASK_ID = "ru.evgen.hermes.kanban.TASK_ID"
    }
}
