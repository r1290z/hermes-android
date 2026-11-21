package ru.evgen.hermes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The transcript.
 *
 * One row per message, but an assistant row is a container that gets filled
 * with blocks — paragraph, code card, list, reasoning. That keeps the markdown
 * renderer honest (a code block is a real card with a copy button, not spans
 * inside a giant TextView) while the RecyclerView still recycles whole turns.
 *
 * While a turn is streaming the assistant row renders as plain text: re-parsing
 * markdown on every token would stutter, and the finished turn is re-rendered
 * with formatting the moment `assistant.completed` arrives.
 */
class ChatAdapter(
    private val context: Context,
    private val onLink: (String) -> Unit,
    private val onLoadOlder: () -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        abstract val key: String
        /** Server timestamp in ms, 0 when unknown (a local, not yet stored row). */
        abstract val time: Long

        data class User(
            override val key: String,
            val text: String,
            val image: Uri? = null,
            override val time: Long = 0
        ) : Row()
        /** A compaction summary: one collapsed line, tap to read the full text. */
        data class Compacted(
            override val key: String,
            val text: String,
            var expanded: Boolean = false,
            override val time: Long = 0
        ) : Row()

        /** The "load older" row at the top of a long transcript. A session with
         *  hundreds of messages cannot be loaded in one go without a visible
         *  stall, so the newest page comes first and this walks backwards. */
        data class Older(override val key: String, var loading: Boolean = false) : Row() {
            override val time: Long get() = 0
        }

        data class Assistant(
            override val key: String,
            var text: String,
            var reasoning: String? = null,
            var streaming: Boolean = false,
            override val time: Long = 0
        ) : Row() {
            /** Parsed markdown, kept across rebinds: a long history is re-bound on
             *  every scroll, and re-parsing a 5K answer each time is what made the
             *  list feel slow. Cleared whenever `text` changes. */
            var blocks: List<Markdown.Block>? = null
            var blocksFor: String = ""

            fun parsed(): List<Markdown.Block> {
                if (blocks != null && blocksFor == text) return blocks!!
                val fresh = Markdown.parse(text)
                blocks = fresh
                blocksFor = text
                return fresh
            }
        }
        data class Tool(
            override val key: String,
            val name: String,
            val preview: String,
            val body: String,
            val failed: Boolean,
            override val time: Long = 0
        ) : Row()
        /** Text the user poured into the RUNNING turn (prompt.submit → queued →
         *  prompt.steer_queued). Rendered right after the user bubble; `state`
         *  is "sent" until the steer is admitted, then "started", or "queued"
         *  when the server kept it for the next turn. */
        data class Steer(
            override val key: String,
            val text: String,
            var state: String,
            override val time: Long = 0
        ) : Row()
        /** A pending clarify/approval card. Lives in the transcript until the
         *  user answers or the server retracts it (`request.cancel`). */
        data class Question(
            override val key: String,
            val request: ServerRequest,
            var answered: Boolean = false,
            override val time: Long = 0
        ) : Row()
    }

    /** Adds a steer row right below the last user bubble (the stream row stays
     *  where it is — the steering text belongs to the running turn, not after
     *  it). Returns the inserted position. */
    fun addSteer(text: String): Int {
        val at = rows.indexOfLast { it is Row.User } + 1
        rows.add(at, Row.Steer("steer-" + ++seq, text, "sent"))
        notifyItemInserted(at)
        return at
    }

    /** Updates a steer row's state chip (sent → started / queued). */
    fun setSteerState(text: String, state: String) {
        val i = rows.indexOfLast { it is Row.Steer && it.text == text }
        if (i < 0) return
        (rows[i] as Row.Steer).state = state
        notifyItemChanged(i)
    }

    private val rows = mutableListOf<Row>()
    private var seq = 0

    fun submit(messages: List<ChatMessage>) {
        rows.clear()
        seq = 0
        for (m in messages) rows += toRow(m)
        notifyDataSetChanged()
    }

    /** Prepend one older page above the transcript. `hasMore` says whether the
     *  server still has rows before this page. */
    fun prependOlder(messages: List<ChatMessage>, hasMore: Boolean) {
        val older = messages.map { toRow(it) }
        // Index 0 is the "load older" row when it is showing; the page goes
        // right below it so the button stays on top.
        val at = if (rows.firstOrNull() is Row.Older) 1 else 0
        rows.addAll(at, older)
        setOlder(hasMore)
        notifyDataSetChanged()
    }

    /** Shows or hides the "load older" row at the top of the list. */
    fun setOlder(hasMore: Boolean) {
        val hasRow = rows.firstOrNull() is Row.Older
        when {
            hasMore && !hasRow -> {
                rows.add(0, Row.Older("older"))
                notifyItemInserted(0)
            }
            !hasMore && hasRow -> {
                rows.removeAt(0)
                notifyItemRemoved(0)
            }
        }
    }

    fun markOlderLoading(loading: Boolean) {
        val row = rows.firstOrNull() as? Row.Older ?: return
        row.loading = loading
        notifyItemChanged(0)
    }

    private fun toRow(m: ChatMessage): Row {
        val time = (m.timestamp * 1000).toLong()
        return when {
            m.compacted -> Row.Compacted("c${seq++}", m.content, time = time)
            m.isUser -> Row.User("u${seq++}", m.content, time = time)
            m.isTool -> Row.Tool(
                "t${seq++}", m.toolName ?: "tool", firstLine(m.content), m.content, false, time
            )
            else -> Row.Assistant("a${seq++}", m.content, m.reasoning, time = time)
        }
    }

    /** Appends a token to the streaming turn, creating it if needed. */
    fun appendDelta(text: String): Int {
        val last = rows.lastOrNull()
        if (last is Row.Assistant && last.streaming) {
            last.text += text
            return rows.size - 1
        }
        rows += Row.Assistant("a${seq++}", text, null, streaming = true, time = System.currentTimeMillis())
        return rows.size - 1
    }

    fun appendReasoning(text: String): Int {
        val last = rows.lastOrNull()
        if (last is Row.Assistant) {
            last.reasoning = (last.reasoning ?: "") + text
            return rows.size - 1
        }
        rows += Row.Assistant(
            "a${seq++}", "", reasoning = text, streaming = true, time = System.currentTimeMillis()
        )
        return rows.size - 1
    }

    fun startTool(name: String, preview: String, body: String): Int {
        rows += Row.Tool("t${seq++}", name, preview, body, false, System.currentTimeMillis())
        return rows.size - 1
    }

    fun finishTurn(content: String) {
        val last = rows.lastOrNull()
        if (last is Row.Assistant) {
            // A blank or literal "null" completion carries no text: the streamed
            // deltas are the answer and must survive. This is what used to wipe the
            // reply and leave the word "null" in its place.
            if (content.isNotBlank() && content != "null") last.text = content
            last.streaming = false
        }
        notifyDataSetChanged()
    }

    /** Timestamp of the row at [position], 0 when it has none — the scroll
     *  timeline reads this to show what moment the top of the screen is at. */
    fun timeAt(position: Int): Long = rows.getOrNull(position)?.time ?: 0

    /** What the user currently sees as the streaming answer ("" when the turn has
     *  not produced a row yet). The history fallback compares against this to tell
     *  "stream is healthy" from "stream died". */
    fun currentAssistantText(): String = (rows.lastOrNull() as? Row.Assistant)?.text.orEmpty()

    /** Replaces the streaming turn with the text the server stored — used when the
     *  socket went quiet mid-answer and the REST history is ahead of the screen. */
    fun setAssistantText(content: String) {
        val last = rows.lastOrNull()
        if (last is Row.Assistant) {
            last.text = content
            last.streaming = false
            notifyItemChanged(rows.size - 1)
        } else {
            rows += Row.Assistant("a${seq++}", content, null, streaming = false)
            notifyItemInserted(rows.size - 1)
        }
    }

    fun addLocalUser(text: String, image: Uri? = null) {
        rows += Row.User("u${seq++}", text, image, System.currentTimeMillis())
        notifyItemInserted(rows.size - 1)
    }

    /** Adds (or re-shows after reconnect) a request card. Returns its index. */
    fun addQuestion(request: ServerRequest): Int {
        rows.removeAll { it is Row.Question && it.request.requestId == request.requestId }
        rows += Row.Question("q${seq++}", request)
        notifyItemInserted(rows.size - 1)
        return rows.size - 1
    }

    /** Marks a request card answered/cancelled: swap it for a one-line note so
     *  the transcript keeps the history of what was asked. */
    fun resolveQuestion(requestId: String, note: String) {
        val idx = rows.indexOfFirst { it is Row.Question && it.request.requestId == requestId }
        if (idx < 0) return
        rows[idx] = Row.Assistant("a${seq++}", note)
        notifyItemChanged(idx)
    }

    fun hasQuestion(requestId: String): Boolean =
        rows.any { it is Row.Question && it.request.requestId == requestId }

    fun isEmpty(): Boolean = rows.isEmpty()

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.User -> TYPE_USER
        is Row.Tool -> TYPE_TOOL
        is Row.Question -> TYPE_QUESTION
        is Row.Compacted -> TYPE_COMPACTED
        is Row.Older -> TYPE_OLDER
        is Row.Steer -> TYPE_STEER
        else -> TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
            TYPE_TOOL -> ToolHolder(inflater.inflate(R.layout.item_chat_tool, parent, false))
            TYPE_QUESTION -> QuestionHolder(inflater.inflate(R.layout.item_question, parent, false))
            TYPE_COMPACTED -> CompactedHolder(inflater.inflate(R.layout.block_text, parent, false))
            TYPE_OLDER -> OlderHolder(inflater.inflate(R.layout.item_chat_older, parent, false))
            TYPE_STEER -> SteerHolder(inflater.inflate(R.layout.item_chat_steer, parent, false))
            else -> AssistantHolder(inflater.inflate(R.layout.item_chat_assistant, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.User -> (holder as UserHolder).bind(row)
            is Row.Tool -> (holder as ToolHolder).bind(row)
            is Row.Question -> (holder as QuestionHolder).bind(row)
            is Row.Compacted -> (holder as CompactedHolder).bind(row)
            is Row.Older -> (holder as OlderHolder).bind(row)
            is Row.Steer -> (holder as SteerHolder).bind(row)
            is Row.Assistant -> (holder as AssistantHolder).bind(row)
        }
    }

    private inner class UserHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById<TextView>(R.id.text)
        private val image: android.widget.ImageView = view.findViewById(R.id.image)
        private val time: TextView = view.findViewById(R.id.time)
        fun bind(row: Row.User) {
            text.text = row.text
            if (row.image != null) {
                image.setImageURI(row.image)
                image.visibility = View.VISIBLE
            } else {
                image.visibility = View.GONE
            }
            time.text = when {
                row.time <= 0 -> ""
                isToday(row.time) -> format(row.time, "HH:mm")
                else -> format(row.time, "d MMM, HH:mm")
            }
        }
    }

    /** The grey "→ в поток" line: steering text is part of the running turn, so
     *  it reads as an annotation, not another message. */
    private inner class SteerHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById<TextView>(R.id.text)
        private val note: TextView = view.findViewById<TextView>(R.id.note)
        fun bind(row: Row.Steer) {
            text.text = row.text
            note.text = when (row.state) {
                "started" -> context.getString(R.string.steer_started)
                "queued" -> context.getString(R.string.steer_queued)
                "failed" -> context.getString(R.string.steer_failed)
                else -> context.getString(R.string.steer_sending)
            }
        }
    }

    private inner class OlderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view as TextView
        fun bind(row: Row.Older) {
            label.text = if (row.loading) "Загружаю…" else "Показать более ранние"
            label.textSize = 13f
            label.setTextColor(context.getColor(R.color.accent))
            label.setOnClickListener {
                if (row.loading) return@setOnClickListener
                row.loading = true
                notifyItemChanged(bindingAdapterPosition)
                onLoadOlder()
            }
        }
    }

    private inner class CompactedHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view as TextView
        fun bind(row: Row.Compacted) {
            text.text = if (row.expanded) row.text
            else context.getString(R.string.compacted_summary)
            text.textSize = 12f
            text.setTextColor(context.getColor(R.color.text_secondary))
            text.setOnClickListener {
                row.expanded = !row.expanded
                notifyItemChanged(bindingAdapterPosition)
            }
        }
    }

    private inner class ToolHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val head: View = view.findViewById(R.id.head)
        private val name: TextView = view.findViewById(R.id.toolName)
        private val preview: TextView = view.findViewById(R.id.preview)
        private val body: TextView = view.findViewById(R.id.body)
        private val dot: TextView = view.findViewById(R.id.dot)
        private val time: TextView = view.findViewById(R.id.time)

        fun bind(row: Row.Tool) {
            name.text = row.name
            time.text = if (row.time <= 0) "" else format(row.time, "HH:mm")
            preview.text = row.preview
            // Tool output can be 5K characters of terminal text; it is only shown
            // when the row is expanded, and a full copy in every recycled view is
            // what made scrolling a long history stutter.
            body.text = if (row.body.length > TOOL_BODY_LIMIT) {
                row.body.take(TOOL_BODY_LIMIT) + "\n… (обрезано)"
            } else {
                row.body
            }
            dot.setTextColor(
                if (row.failed) context.getColor(R.color.error_text)
                else context.getColor(R.color.accent)
            )
            body.visibility = View.GONE
            head.setOnClickListener {
                body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
    }

    private inner class AssistantHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val blocks: LinearLayout = view.findViewById(R.id.blocks)
        private val time: TextView = view.findViewById(R.id.time)

        fun bind(row: Row.Assistant) {
            blocks.removeAllViews()
            time.text = when {
                row.time <= 0 -> ""
                isToday(row.time) -> format(row.time, "HH:mm")
                else -> format(row.time, "d MMM, HH:mm")
            }
            val inflater = LayoutInflater.from(context)

            row.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
                val block = inflater.inflate(R.layout.block_reasoning, blocks, false)
                val toggle = block.findViewById<TextView>(R.id.toggle)
                val body = block.findViewById<TextView>(R.id.body)
                body.text = reasoning
                toggle.setOnClickListener {
                    val show = body.visibility != View.VISIBLE
                    body.visibility = if (show) View.VISIBLE else View.GONE
                    toggle.text = context.getString(
                        if (show) R.string.thinking_hide else R.string.thinking
                    )
                }
                blocks.addView(block)
            }

            if (row.streaming) {
                // Cheap path: one TextView, no parsing, so a token flood stays smooth.
                val block = inflater.inflate(R.layout.block_text, blocks, false) as TextView
                block.text = row.text
                blocks.addView(block)
                return
            }

            for (part in row.parsed()) {
                when (part) {
                    is Markdown.Block.Paragraph -> {
                        val tv = inflater.inflate(R.layout.block_text, blocks, false) as TextView
                        tv.text = Markdown.inline(
                            part.text,
                            codeBackground = context.getColor(R.color.tool_bg),
                            linkColor = context.getColor(R.color.accent),
                            onLink = onLink
                        )
                        tv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
                        blocks.addView(tv)
                    }
                    is Markdown.Block.Heading -> {
                        val tv = inflater.inflate(R.layout.block_text, blocks, false) as TextView
                        tv.text = Markdown.inline(part.text, linkColor = context.getColor(R.color.accent), onLink = onLink)
                        tv.textSize = when (part.level) {
                            1 -> 20f
                            2 -> 18f
                            else -> 16f
                        }
                        tv.setTypeface(Typeface.DEFAULT_BOLD)
                        tv.setPadding(0, 12, 0, 4)
                        blocks.addView(tv)
                    }
                    is Markdown.Block.Bullets -> {
                        val tv = inflater.inflate(R.layout.block_text, blocks, false) as TextView
                        tv.text = Markdown.bullets(part.items, part.ordered)
                        blocks.addView(tv)
                    }
                    is Markdown.Block.Quote -> {
                        val tv = inflater.inflate(R.layout.block_text, blocks, false) as TextView
                        tv.text = Markdown.inline(part.text, linkColor = context.getColor(R.color.accent), onLink = onLink)
                        tv.setPadding(20, 4, 8, 4)
                        tv.setBackgroundResource(R.drawable.bg_quote)
                        tv.setTextColor(context.getColor(R.color.text_secondary))
                        blocks.addView(tv)
                    }
                    is Markdown.Block.Table -> blocks.addView(tableView(inflater, part))
                    is Markdown.Block.Code -> blocks.addView(codeCard(inflater, part))
                    is Markdown.Block.Rule -> {
                        val divider = View(context)
                        divider.setBackgroundColor(context.getColor(R.color.divider))
                        blocks.addView(divider, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        ).apply { topMargin = 12; bottomMargin = 12 })
                    }
                }
            }
        }

        private fun codeCard(inflater: LayoutInflater, part: Markdown.Block.Code): View {
            val card = inflater.inflate(R.layout.block_code, blocks, false)
            card.findViewById<TextView>(R.id.language).text =
                if (part.language.isEmpty()) "code" else part.language
            card.findViewById<TextView>(R.id.code).text = part.code
            val copy = card.findViewById<TextView>(R.id.copy)
            copy.setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("code", part.code))
                copy.text = context.getString(R.string.copied)
                copyHandler.removeCallbacks(resetCopy)
                copyHandler.postDelayed(resetCopy, 1500)
            }
            return card
        }

        private val copyHandler = Handler(Looper.getMainLooper())
        private val resetCopy = Runnable {
            blocks.findViewById<TextView>(R.id.copy)?.text = context.getString(R.string.copy)
        }

        /** Wide tables scroll sideways instead of squeezing the cells to nothing. */
        private fun tableView(inflater: LayoutInflater, part: Markdown.Block.Table): View {
            val columns = part.headers.size
            val cell: (String, Boolean) -> TextView = { content, header ->
                TextView(context).apply {
                    text = Markdown.inline(content)
                    textSize = if (header) 13f else 14f
                    setTypeface(null, if (header) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(context.getColor(if (header) R.color.text_primary else R.color.text_secondary))
                    setPadding(10, 6, 10, 6)
                    // A monster cell (a stack trace pasted into a table) must not
                    // blow up the row: cap it and ellipsize.
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    maxLines = 6
                }
            }
            fun row(values: List<String>, header: Boolean): TableRow =
                TableRow(context).apply {
                    for (c in 0 until columns) {
                        addView(cell(values.getOrElse(c) { "" }, header),
                            TableRow.LayoutParams(
                                TableRow.LayoutParams.WRAP_CONTENT,
                                TableRow.LayoutParams.WRAP_CONTENT
                            ))
                    }
                }

            val table = TableLayout(context).apply {
                isStretchAllColumns = true
                addView(row(part.headers, true))
                for (r in part.rows) addView(row(r, false))
            }
            val scroll = HorizontalScrollView(context)
            scroll.addView(table)
            scroll.setPadding(0, 6, 0, 6)
            return scroll
        }
    }

    private fun firstLine(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ""
        return if (line.length > 120) line.take(120) + "…" else line
    }

    /** The clarify/approval card. Clarify supports several questions; the card
     *  shows the first unanswered one and advances after each answer. */
    private inner class QuestionHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.questionTitle)
        private val choices: LinearLayout = view.findViewById(R.id.questionChoices)
        private val skip: View = view.findViewById(R.id.questionSkip)

        fun bind(row: Row.Question) {
            when (val request = row.request) {
                is ServerRequest.Approval -> {
                    title.text = request.title
                    choices.removeAllViews()
                    for (choice in request.choices) {
                        choices.addView(button(labelForChoice(choice)) {
                            row.answered = true
                            // Approval: (null, choice)
                            answerHandler?.invoke(request.requestId, null, choice)
                        })
                    }
                    skip.visibility = View.GONE
                }
                is ServerRequest.Clarify -> {
                    val question = request.questions.firstOrNull() ?: return
                    title.text = question.question
                    choices.removeAllViews()
                    for (choice in question.choices) {
                        choices.addView(button(choice) {
                            row.answered = true
                            answerHandler?.invoke(request.requestId, question.qid, choice)
                        })
                    }
                    if (request.questions.size > 1) {
                        choices.addView(button(context.getString(R.string.skip)) {
                            row.answered = true
                            answerHandler?.invoke(request.requestId, question.qid, null)
                        })
                    }
                    skip.visibility = View.VISIBLE
                    skip.setOnClickListener {
                        row.answered = true
                        answerHandler?.invoke(request.requestId, null, null)
                    }
                }
            }
        }

        private fun button(label: String, onClick: () -> Unit): View =
            android.widget.Button(context).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                setOnClickListener { onClick() }
            }

        private fun labelForChoice(choice: String): String = when (choice) {
            "once" -> context.getString(R.string.approve_once)
            "session" -> context.getString(R.string.approve_session)
            "always" -> context.getString(R.string.approve_always)
            "deny" -> context.getString(R.string.deny)
            else -> choice
        }
    }

    /** Set by ChatActivity. Clarify: (qid, answer|null); approval: (null,
     *  choice); cancel the whole clarify batch: (null, null). */
    var answerHandler: ((String, String?, String?) -> Unit)? = null

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
        private const val TYPE_TOOL = 2
        private const val TYPE_QUESTION = 3
        private const val TYPE_COMPACTED = 4
        private const val TYPE_OLDER = 5
        private const val TYPE_STEER = 6

        /** Characters of tool output kept in the collapsed row. */
        private const val TOOL_BODY_LIMIT = 1500

        private fun format(ms: Long, pattern: String): String =
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))

        private fun isToday(ms: Long): Boolean {
            val cal = Calendar.getInstance()
            val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
            cal.timeInMillis = ms
            return (cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)) == today
        }

        /** The scroll timeline's label: time for today, date + time otherwise. */
        fun timelineLabel(ms: Long): String = when {
            ms <= 0 -> ""
            isToday(ms) -> format(ms, "HH:mm")
            else -> format(ms, "d MMM, HH:mm")
        }
    }
}
