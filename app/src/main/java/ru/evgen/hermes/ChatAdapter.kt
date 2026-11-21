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
    private val onLink: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        abstract val key: String
        data class User(override val key: String, val text: String, val image: Uri? = null) : Row()
        /** A compaction summary: one collapsed line, tap to read the full text. */
        data class Compacted(override val key: String, val text: String, var expanded: Boolean = false) : Row()
        data class Assistant(
            override val key: String,
            var text: String,
            var reasoning: String? = null,
            var streaming: Boolean = false
        ) : Row()
        data class Tool(
            override val key: String,
            val name: String,
            val preview: String,
            val body: String,
            val failed: Boolean
        ) : Row()
        /** A pending clarify/approval card. Lives in the transcript until the
         *  user answers or the server retracts it (`request.cancel`). */
        data class Question(
            override val key: String,
            val request: ServerRequest,
            var answered: Boolean = false
        ) : Row()
    }

    private val rows = mutableListOf<Row>()
    private var seq = 0

    fun submit(messages: List<ChatMessage>) {
        rows.clear()
        seq = 0
        for (m in messages) {
            when {
                m.compacted -> rows += Row.Compacted("c${seq++}", m.content)
                m.isUser -> rows += Row.User("u${seq++}", m.content)
                m.isTool -> rows += Row.Tool(
                    "t${seq++}", m.toolName ?: "tool",
                    firstLine(m.content), m.content, false
                )
                else -> rows += Row.Assistant("a${seq++}", m.content, m.reasoning)
            }
        }
        notifyDataSetChanged()
    }

    /** Appends a token to the streaming turn, creating it if needed. */
    fun appendDelta(text: String): Int {
        val last = rows.lastOrNull()
        if (last is Row.Assistant && last.streaming) {
            last.text += text
            return rows.size - 1
        }
        rows += Row.Assistant("a${seq++}", text, null, streaming = true)
        return rows.size - 1
    }

    fun appendReasoning(text: String): Int {
        val last = rows.lastOrNull()
        if (last is Row.Assistant) {
            last.reasoning = (last.reasoning ?: "") + text
            return rows.size - 1
        }
        rows += Row.Assistant("a${seq++}", "", reasoning = text, streaming = true)
        return rows.size - 1
    }

    fun startTool(name: String, preview: String, body: String): Int {
        rows += Row.Tool("t${seq++}", name, preview, body, false)
        return rows.size - 1
    }

    fun finishTurn(content: String) {
        val last = rows.lastOrNull()
        if (last is Row.Assistant) {
            if (content.isNotBlank()) last.text = content
            last.streaming = false
        }
        notifyDataSetChanged()
    }

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
        rows += Row.User("u${seq++}", text, image)
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
        else -> TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
            TYPE_TOOL -> ToolHolder(inflater.inflate(R.layout.item_chat_tool, parent, false))
            TYPE_QUESTION -> QuestionHolder(inflater.inflate(R.layout.item_question, parent, false))
            TYPE_COMPACTED -> CompactedHolder(inflater.inflate(R.layout.block_text, parent, false))
            else -> AssistantHolder(inflater.inflate(R.layout.item_chat_assistant, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.User -> (holder as UserHolder).bind(row)
            is Row.Tool -> (holder as ToolHolder).bind(row)
            is Row.Question -> (holder as QuestionHolder).bind(row)
            is Row.Compacted -> (holder as CompactedHolder).bind(row)
            is Row.Assistant -> (holder as AssistantHolder).bind(row)
        }
    }

    private inner class UserHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById<TextView>(R.id.text)
        private val image: android.widget.ImageView = view.findViewById(R.id.image)
        fun bind(row: Row.User) {
            text.text = row.text
            if (row.image != null) {
                image.setImageURI(row.image)
                image.visibility = View.VISIBLE
            } else {
                image.visibility = View.GONE
            }
        }
    }

    /** One collapsed line for a compaction summary; a tap unfolds the real text. */
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

        fun bind(row: Row.Tool) {
            name.text = row.name
            preview.text = row.preview
            body.text = row.body
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

        fun bind(row: Row.Assistant) {
            blocks.removeAllViews()
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

            for (part in Markdown.parse(row.text)) {
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
    }
}
