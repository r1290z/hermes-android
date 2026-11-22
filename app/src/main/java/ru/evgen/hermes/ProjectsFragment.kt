package ru.evgen.hermes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Page 0 of the swipe shell: the project list, one row per `projects.tree`
 * node plus the first row «Все сеансы» (filter = null).
 *
 * A tap does not open anything of its own — it selects the project filter in
 * the shared [ProjectFilterModel] and pages the host's ViewPager2 to the
 * sessions page (see [MainActivity.openProject]).
 */
class ProjectsFragment : Fragment(R.layout.fragment_projects) {

    private lateinit var api: ApiClient
    private lateinit var status: TextView
    private lateinit var adapter: ProjectsAdapter
    private lateinit var filter: ProjectFilterModel

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        api = ApiClient.get(requireContext())
        filter = ViewModelProvider(requireActivity())[ProjectFilterModel::class.java]
        status = view.findViewById(R.id.status)
        adapter = ProjectsAdapter { project ->
            (activity as? MainActivity)?.openProject(project)
        }
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        load()
    }

    /** Also runs from onResume: the selected row highlight has to follow the
     *  filter even when it was cleared on the sessions page. */
    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) adapter.setSelected(filter.selected)
    }

    /** The sessions page reset the filter from its banner — drop the highlight. */
    fun syncSelection() {
        if (::adapter.isInitialized) adapter.setSelected(filter.selected)
    }

    private fun load() {
        status.visibility = View.VISIBLE
        status.text = getString(R.string.loading)
        scope.launch {
            val projects = withContext(Dispatchers.IO) { api.projects() }
            filter.knownPaths = projects.mapNotNull { it.path }
            adapter.submit(projects)
            if (projects.isEmpty()) {
                status.visibility = View.VISIBLE
                status.text = getString(R.string.projects_empty)
            } else {
                status.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        scope.cancel()
        super.onDestroyView()
    }
}

/** Rows: 0 = «Все сеансы» (null filter), 1..n = projects. The selected row
 *  gets the pressed background so the choice is visible when swiping back. */
private class ProjectsAdapter(
    private val onTap: (Project?) -> Unit
) : RecyclerView.Adapter<ProjectsAdapter.Holder>() {

    private val rows = mutableListOf<Project>()
    private var selected: Project? = null

    fun submit(projects: List<Project>) {
        rows.clear()
        rows.addAll(projects)
        notifyDataSetChanged()
    }

    fun setSelected(project: Project?) {
        selected = project
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_project, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(rows, position, selected, onTap)
    }

    override fun getItemCount() = rows.size + 1

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.title)
        private val path: TextView = view.findViewById(R.id.path)
        private val count: TextView = view.findViewById(R.id.count)

        fun bind(rows: List<Project>, position: Int, selected: Project?, onTap: (Project?) -> Unit) {
            if (position == 0) {
                title.text = itemView.context.getString(R.string.projects_all_sessions)
                path.text = ""
                count.text = ""
                itemView.setBackgroundResource(R.drawable.bg_row)
            } else {
                val project = rows[position - 1]
                title.text = project.label
                // Home has no path; saying so beats a blank second line.
                path.text = if (project.isNoProject) {
                    itemView.context.getString(R.string.projects_home_hint)
                } else {
                    project.path ?: ""
                }
                count.text = project.sessionCount.toString()
                if (project == selected) itemView.setBackgroundColor(
                    itemView.context.getColor(R.color.pressed)
                ) else itemView.setBackgroundResource(R.drawable.bg_row)
                itemView.setOnClickListener { onTap(project) }
                return
            }
            itemView.setOnClickListener { onTap(null) }
        }
    }
}
