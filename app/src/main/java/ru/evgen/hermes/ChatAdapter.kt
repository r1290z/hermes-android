package ru.evgen.hermes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
        data class User(override val key: String, val text: String) : Row()
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
    }

    private val rows = mutableListOf<Row>()
    private var seq = 0

    fun submit(messages: List<ChatMessage>) {
        rows.clear()
        seq = 0
        for (m in messages) {
            when {
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

    fun addLocalUser(text: String) {
        rows += Row.User("u${seq++}", text)
        notifyItemInserted(rows.size - 1)
    }

    fun isEmpty(): Boolean = rows.isEmpty()

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.User -> TYPE_USER
        is Row.Tool -> TYPE_TOOL
        else -> TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
            TYPE_TOOL -> ToolHolder(inflater.inflate(R.layout.item_chat_tool, parent, false))
            else -> AssistantHolder(inflater.inflate(R.layout.item_chat_assistant, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.User -> (holder as UserHolder).bind(row)
            is Row.Tool -> (holder as ToolHolder).bind(row)
            is Row.Assistant -> (holder as AssistantHolder).bind(row)
        }
    }

    private inner class UserHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.text)
        fun bind(row: Row.User) {
            text.text = row.text
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
                        tv.setPadding(24, 4, 0, 4)
                        tv.setTextColor(context.getColor(R.color.text_secondary))
                        blocks.addView(tv)
                    }
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
            card.findViewById<TextView>(R.id.language).text = part.language
            card.findViewById<TextView>(R.id.code).text = part.code
            card.findViewById<TextView>(R.id.copy).setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("code", part.code))
                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            return card
        }
    }

    private fun firstLine(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ""
        return if (line.length > 120) line.take(120) + "…" else line
    }

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
        private const val TYPE_TOOL = 2
    }
}
