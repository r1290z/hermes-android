package ru.evgen.hermes

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The child agents working for one session.
 *
 * A delegation can fan out several children, and from the phone that used to be
 * invisible: the transcript showed one "выполняет инструмент" row whatever happened
 * underneath. This screen answers the two questions a waiting user actually has —
 * what are they doing, and how do I stop the one that went wrong — without opening
 * the desktop.
 *
 * It refreshes itself while open and stops refreshing when it is not: a poll that
 * outlives the screen is a battery bug.
 */
class AgentsActivity : Activity() {

    private lateinit var api: ApiClient
    private lateinit var sessionId: String
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var status: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        if (sessionId.isEmpty()) {
            finish()
            return
        }
        api = ApiClient.get(this)
        setContentView(buildUi())
        refresh()
        pollJob = scope.launch {
            while (true) {
                delay(4000)
                refresh()
            }
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.app_bg))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        bar.addView(TextView(this).apply {
            text = getString(R.string.agents_title)
            textSize = 18f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(TextView(this).apply {
            text = getString(R.string.back)
            textSize = 14f
            setTextColor(getColor(R.color.accent))
            setPadding(dp(12), dp(4), 0, dp(4))
            setOnClickListener { finish() }
        })
        root.addView(bar)

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        root.addView(status)

        val scroll = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(24))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        empty = TextView(this).apply {
            text = getString(R.string.agents_empty)
            textSize = 14f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(16), dp(24), dp(16), 0)
        }
        root.addView(empty)
        return root
    }

    private fun refresh() {
        scope.launch {
            val agents = withContext(Dispatchers.IO) { api.subagents(sessionId) }
            if (isFinishing) return@launch
            render(agents)
        }
    }

    private fun render(agents: List<Subagent>) {
        list.removeAllViews()
        empty.visibility = if (agents.isEmpty()) View.VISIBLE else View.GONE
        val live = agents.count { !it.finished }
        status.text = when {
            agents.isEmpty() -> ""
            live == 0 -> getString(R.string.agents_all_done, agents.size)
            else -> getString(R.string.agents_running, live, agents.size)
        }
        for (agent in agents) list.addView(card(agent))
    }

    private fun card(agent: Subagent): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_code)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        // Goal first: it is the only line that says what the child is for.
        box.addView(TextView(this).apply {
            text = agent.goal.ifEmpty { agent.id.take(12) + "…" }
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            maxLines = 3
        })
        val facts = buildList {
            add(AgentStatus.label(agent.status))
            if (agent.model.isNotEmpty()) add(agent.model)
            if (agent.toolCount > 0) add(getString(R.string.agents_tools, agent.toolCount))
            if (agent.lastTool.isNotEmpty()) add(agent.lastTool)
            if (agent.elapsed > 0) add(clock(agent.elapsed))
        }.joinToString(" · ")
        box.addView(TextView(this).apply {
            text = facts
            textSize = 12f
            setTextColor(getColor(if (agent.finished) R.color.text_secondary else R.color.accent))
            setPadding(0, dp(3), 0, dp(8))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (!agent.finished) {
            actions.addView(action(getString(R.string.agents_peek)) { peek(agent) })
            if (agent.acceptingSteer) {
                actions.addView(action(getString(R.string.agents_steer)) { steer(agent) })
            }
            actions.addView(action(getString(R.string.notif_stop)) { stop(agent) })
        } else {
            actions.addView(action(getString(R.string.agents_peek)) { peek(agent) })
        }
        box.addView(actions)
        return box
    }

    private fun action(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(getColor(R.color.accent))
        setPadding(0, dp(4), dp(18), dp(4))
        setOnClickListener { onClick() }
    }

    /** The child's own output, so "what is it doing" needs no session switch. */
    private fun peek(agent: Subagent) {
        scope.launch {
            val text = withContext(Dispatchers.IO) { api.subagentTail(sessionId, agent.id) }
            if (isFinishing) return@launch
            AlertDialog.Builder(this@AgentsActivity)
                .setTitle(agent.goal.ifEmpty { agent.id.take(16) })
                .setMessage(text ?: getString(R.string.agents_no_output))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun steer(agent: Subagent) {
        val field = EditText(this).apply {
            hint = getString(R.string.agents_steer_hint)
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.agents_steer)
            .setView(field)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = field.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { api.steerSubagent(sessionId, agent.id, text) }
                    if (!isFinishing) {
                        Toast.makeText(
                            this@AgentsActivity,
                            if (ok) R.string.agents_steer_sent else R.string.agents_steer_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun stop(agent: Subagent) {
        AlertDialog.Builder(this)
            .setTitle(R.string.notif_stop)
            .setMessage(agent.goal.ifEmpty { agent.id.take(16) })
            .setPositiveButton(R.string.notif_stop) { _, _ ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { api.stopSubagent(sessionId, agent.id) }
                    if (!isFinishing) {
                        Toast.makeText(
                            this@AgentsActivity,
                            if (ok) R.string.agents_stopped else R.string.agents_stop_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                        refresh()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clock(seconds: Long): String =
        if (seconds < 60) "${seconds}с" else "${seconds / 60}м ${seconds % 60}с"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SESSION_ID = "ru.evgen.hermes.AGENTS_SESSION_ID"
    }
}
