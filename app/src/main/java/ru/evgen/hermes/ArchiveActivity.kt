package ru.evgen.hermes

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Archived sessions: the ones the main list hides (`archived: true`).
 *
 * One action per row — "Вернуть" flips `archived` back to false and the row
 * disappears from here and reappears in the main list.
 */
class ArchiveActivity : Activity() {

    private lateinit var api: ApiClient
    private lateinit var status: TextView
    private lateinit var adapter: ArchiveAdapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = ApiClient(this)
        if (!api.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_archive)
        status = findViewById(R.id.status)
        adapter = ArchiveAdapter { session, pos -> restore(session, pos) }
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        load()
    }

    private fun load() {
        scope.launch {
            val sessions = withContext(Dispatchers.IO) { api.archivedSessions(this@ArchiveActivity) }
            // Prune registry entries the server no longer has (deleted upstream).
            val live = sessions.map { it.id }.toSet()
            ArchiveStore.ids(this@ArchiveActivity).filter { it !in live }.forEach {
                ArchiveStore.remove(this@ArchiveActivity, it)
            }
            status.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
            status.text = getString(R.string.archive_empty)
            adapter.submit(sessions)
        }
    }

    private fun restore(session: Session, position: Int) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { api.setArchived(session.id, false) }
            if (ok) {
                ArchiveStore.remove(this@ArchiveActivity, session.id)
                adapter.removeAt(position)
                if (adapter.itemCount == 0) {
                    status.visibility = View.VISIBLE
                    status.text = getString(R.string.archive_empty)
                }
                Toast.makeText(this@ArchiveActivity, R.string.restored, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ArchiveActivity, api.error ?: "Не удалось", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

private class ArchiveAdapter(
    private val onRestore: (Session, Int) -> Unit
) : RecyclerView.Adapter<ArchiveAdapter.Holder>() {

    private val rows = mutableListOf<Session>()

    fun submit(sessions: List<Session>) {
        rows.clear()
        rows.addAll(sessions.sortedByDescending { it.lastActive })
        notifyDataSetChanged()
    }

    fun removeAt(position: Int) {
        rows.removeAt(position)
        notifyItemRemoved(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_archive, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(rows[position])
    }

    override fun getItemCount() = rows.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.title)
        private val meta: TextView = view.findViewById(R.id.meta)

        fun bind(session: Session) {
            title.text = session.title
            meta.text = buildString {
                if (session.messageCount > 0) append("${session.messageCount} сообщ.")
                if (session.model.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(session.model)
                }
            }
            itemView.findViewById<View>(R.id.btnRestore).setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRestore(session, pos)
            }
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRestore(rows[pos], pos)
            }
        }
    }
}
