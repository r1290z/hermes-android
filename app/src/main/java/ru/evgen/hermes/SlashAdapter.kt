package ru.evgen.hermes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * The slash palette above the composer.
 *
 * Rows come from the gateway's catalog, so a skill installed tomorrow appears here
 * without a new build. The description is shown verbatim: it already says what
 * arguments the command takes, and rewriting it would only drift from the server.
 */
class SlashAdapter(
    private val onPick: (SlashItem) -> Unit
) : RecyclerView.Adapter<SlashAdapter.Holder>() {

    private val items = ArrayList<SlashItem>()

    fun submit(fresh: List<SlashItem>) {
        items.clear()
        items.addAll(fresh)
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_slash, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val cmd: TextView = view.findViewById(R.id.cmd)
        private val meta: TextView = view.findViewById(R.id.meta)

        fun bind(item: SlashItem) {
            // The catalog returns names without the slash; a skill keeps its own
            // display form, so it is shown exactly as the server sent it.
            cmd.text = if (item.text.startsWith("/")) item.text else "/" + item.text
            meta.text = item.meta
            meta.visibility = if (item.meta.isBlank()) View.GONE else View.VISIBLE
            itemView.setOnClickListener { onPick(item) }
        }
    }
}
