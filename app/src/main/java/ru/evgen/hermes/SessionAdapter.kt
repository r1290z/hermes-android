package ru.evgen.hermes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Pinned first, then "Сегодня / Вчера / На этой неделе / В этом месяце / Раньше".
 *  The webapp's drawer shows one flat list, which is why finding an old session
 *  on a phone meant scrolling past everything ever written. */
class SessionAdapter(
    private val onOpen: (Session) -> Unit,
    private val onLongPress: (Session, View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<Row>()

    sealed class Row {
        data class Resume(val session: Session) : Row()
        data class Header(val text: String) : Row()
        data class Item(val session: Session) : Row()
    }

    fun submit(sessions: List<Session>) {
        rows.clear()
        // The desktop sidebar needs a click to get back to the conversation
        // the user just left; the phone gets it in one tap at the very top.
        sessions.firstOrNull { it.title.isNotEmpty() && it.messageCount > 0 }
            ?.let { rows += Row.Resume(it) }
        val pinned = sessions.filter { it.pinned }.sortedByDescending { it.lastActive }
        val rest = sessions.filter { !it.pinned }.sortedByDescending { it.lastActive }
        if (pinned.isNotEmpty()) {
            rows += Row.Header("Закреплённые")
            pinned.forEach { rows += Row.Item(it) }
        }
        var bucket: String? = null
        for (s in rest) {
            val b = bucketOf(s.lastActive)
            if (b != bucket) {
                bucket = b
                rows += Row.Header(b)
            }
            rows += Row.Item(s)
        }
        notifyDataSetChanged()
    }

    fun rowAt(position: Int): Row? = rows.getOrNull(position)

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Resume -> TYPE_RESUME
        is Row.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_section, parent, false))
            TYPE_RESUME -> ResumeHolder(inflater.inflate(R.layout.item_resume, parent, false))
            else -> ItemHolder(inflater.inflate(R.layout.item_session, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row.text)
            is Row.Resume -> (holder as ResumeHolder).bind(row.session)
            is Row.Item -> (holder as ItemHolder).bind(row.session)
        }
    }

    private inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label = view as TextView
        fun bind(text: String) {
            label.text = text
        }
    }

    /** One tap straight into the last conversation. */
    private inner class ResumeHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label = view as TextView
        fun bind(session: Session) {
            label.text = "Продолжить: ${session.title}"
            itemView.setOnClickListener { onOpen(session) }
        }
    }

    private inner class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.title)
        private val meta: TextView = view.findViewById(R.id.meta)
        private val time: TextView = view.findViewById(R.id.time)
        private val pin: TextView = view.findViewById(R.id.pin)
        private val unreadDot: View = view.findViewById(R.id.unreadDot)
        private val workingDot: PulseDot = view.findViewById(R.id.workingDot)

        fun bind(session: Session) {
            title.text = session.title
            meta.text = metaLine(session)
            time.text = relative(session.lastActive)
            pin.visibility = if (session.pinned) View.VISIBLE else View.GONE
            // Two different states, never both: a session that is working has not
            // "answered and been missed", and the pulse would be lost under a dot.
            unreadDot.visibility = if (session.unread && !session.running) View.VISIBLE else View.GONE
            workingDot.visibility = if (session.running) View.VISIBLE else View.GONE
            if (session.running) workingDot.start() else workingDot.stop()
            itemView.setOnClickListener { onOpen(session) }
            itemView.setOnLongClickListener { v ->
                onLongPress(session, v)
                true
            }
        }
    }

    private fun metaLine(session: Session): String {
        // While a turn runs, "what is happening" beats "what we said last": the
        // row already carries the title, and the preview is stale by definition.
        if (session.running) {
            val what = activityLabel(session.activity)
            return if (session.model.isNotEmpty()) "$what · ${session.model}" else what
        }
        val parts = mutableListOf<String>()
        if (session.messageCount > 0) parts += "${session.messageCount} сообщ."
        if (session.model.isNotEmpty()) parts += session.model
        parts += relative(session.lastActive)
        val preview = session.preview
        return if (preview.isNotEmpty() && preview != session.title) {
            parts.dropLast(1).joinToString(" · ") + "\n" + preview
        } else {
            parts.joinToString(" · ")
        }
    }

    /** The agent's own words for what it is doing, in Russian, falling back to a
     *  generic label rather than showing an untranslated English stamp. */
    private fun activityLabel(raw: String): String {
        val a = raw.lowercase()
        return when {
            a.contains("stream") -> "пишет ответ"
            a.contains("thinking") || a.contains("reasoning") -> "думает"
            a.contains("tool") -> "выполняет инструмент"
            a.contains("compress") -> "сжимает контекст"
            a.contains("waiting") -> "ждёт"
            a.isBlank() -> "работает"
            else -> "работает"
        }
    }

    private fun bucketOf(ts: Long): String {
        if (ts <= 0) return "Раньше"
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        val dayDiff = daysBetween(then, now)
        return when {
            dayDiff == 0 -> "Сегодня"
            dayDiff == 1 -> "Вчера"
            dayDiff in 2..6 -> "На этой неделе"
            sameYear && dayDiff < 31 -> "В этом месяце"
            else -> "Раньше"
        }
    }

    private fun daysBetween(then: Calendar, now: Calendar): Int {
        val a = then.clone() as Calendar
        val b = now.clone() as Calendar
        a.set(Calendar.HOUR_OF_DAY, 0); a.set(Calendar.MINUTE, 0)
        a.set(Calendar.SECOND, 0); a.set(Calendar.MILLISECOND, 0)
        b.set(Calendar.HOUR_OF_DAY, 0); b.set(Calendar.MINUTE, 0)
        b.set(Calendar.SECOND, 0); b.set(Calendar.MILLISECOND, 0)
        val diff = b.timeInMillis - a.timeInMillis
        return (diff / 86_400_000L).toInt()
    }

    private fun relative(ts: Long): String {
        if (ts <= 0) return ""
        val diff = System.currentTimeMillis() - ts
        if (diff < 0) return "только что"
        val minutes = diff / 60_000
        return when {
            minutes < 1 -> "только что"
            minutes < 60 -> "$minutes мин"
            minutes < 1440 -> "${minutes / 60} ч"
            minutes < 1440 * 7 -> "${minutes / 1440} дн"
            else -> SimpleDateFormat("d MMM", Locale("ru")).format(Date(ts))
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
        private const val TYPE_RESUME = 2
    }
}
