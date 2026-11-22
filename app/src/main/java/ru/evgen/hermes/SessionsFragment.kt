package ru.evgen.hermes

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The session list, moved one-to-one out of MainActivity so that swipe
 * navigation can page between fragments. Everything list-related lives here:
 * RecyclerView, search, profile chips, row swipes, the status line and the
 * "who is working" poll. The "…" menu and update checks stay in the Activity.
 */
class SessionsFragment : Fragment(R.layout.fragment_sessions) {

    /** What the fragment still needs from its host (menu, update check). */
    interface Host {
        fun onRefreshLongPress()
        fun onMoreClicked()

        /** A session row (or «новый сеанс») was tapped: open it in the chat page. */
        fun onOpenChat(id: String?, title: String?)
    }

    private lateinit var api: ApiClient
    private lateinit var list: RecyclerView
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var adapter: SessionAdapter

    private val all = mutableListOf<Session>()
    private var profiles: List<ApiClient.ProfileInfo> = emptyList()

    /** Project filter shared with page 0 (ProjectsFragment). */
    private lateinit var projectFilter: ProjectFilterModel
    private var projectBanner: android.widget.LinearLayout? = null
    private var projectLabel: TextView? = null

    /** Own scope, cancelled in onDestroyView — view-bound work must not
     *  outlive the view. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var statusBar: StatusBar
    private var loading = false
    /** Live refresh of running / unread state while this screen is visible. */
    private var pollJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        api = ApiClient.get(requireContext())
        if (!api.isLoggedIn()) {
            redirectToLogin()
            return
        }

        list = view.findViewById(R.id.list)
        search = view.findViewById(R.id.search)
        status = view.findViewById(R.id.status)

        adapter = SessionAdapter(
            onOpen = { openChat(it) },
            onLongPress = { session, _ -> showActions(session) }
        )
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.itemAnimator = null
        attachSwipes()

        view.findViewById<View>(R.id.newSession).setOnClickListener { openChat(null) }
        view.findViewById<View>(R.id.btnKanban).setOnClickListener {
            startActivity(Intent(requireContext(), ru.evgen.hermes.kanban.KanbanBoardActivity::class.java))
        }
        view.findViewById<ImageButton>(R.id.btnRefresh).apply {
            setOnClickListener { load(force = true) }
            setOnLongClickListener {
                (activity as? Host)?.onRefreshLongPress()
                true
            }
        }
        view.findViewById<ImageButton>(R.id.btnLogout).setOnClickListener {
            (activity as? Host)?.onMoreClicked()
        }

        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else false
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        statusBar = StatusBar(requireActivity(), view.findViewById(R.id.statusBar), api)
        view.findViewById<View>(R.id.statusBar).setOnClickListener { statusBar.showDetails() }

        projectFilter = ViewModelProvider(requireActivity())[ProjectFilterModel::class.java]
        projectBanner = view.findViewById(R.id.projectFilter)
        projectLabel = view.findViewById(R.id.projectFilterLabel)
        view.findViewById<View>(R.id.projectFilterReset).setOnClickListener {
            applyProject(null)
            // The highlight on page 0 must drop too.
            (activity as? MainActivity)?.syncProjectSelection()
        }
        renderProjectBanner()

        load(force = true)
        loadProfiles()
    }

    override fun onResume() {
        super.onResume()
        if (!::api.isInitialized) return
        if (!api.isLoggedIn()) {
            redirectToLogin()
            return
        }
        // Coming back from a chat: the title or the last-active time changed.
        // The marks for that session are applied inside load().
        if (!loading && all.isNotEmpty()) load(force = true)
        if (::statusBar.isInitialized) statusBar.start()
        startActivityPoll()
    }

    override fun onPause() {
        super.onPause()
        // Nothing on this screen is visible any more: stop both polls.
        stopActivityPoll()
        if (::statusBar.isInitialized) statusBar.stop()
    }

    private fun redirectToLogin() {
        startActivity(Intent(requireContext(), LoginActivity::class.java))
        activity?.finish()
    }

    // ── Sessions ─────────────────────────────────────────────────────────────

    private fun load(force: Boolean) {
        if (loading) return
        loading = true
        if (all.isEmpty()) {
            status.visibility = View.VISIBLE
            status.text = getString(R.string.loading)
        }
        scope.launch {
            val sessions = withContext(Dispatchers.IO) { api.sessions() }
            loading = false
            if (sessions.isEmpty() && !api.isLoggedIn()) {
                redirectToLogin()
                return@launch
            }
            // Marks are applied here, not on the server: its `unread` column is
            // never set true on this gateway, so the count-based marker is what
            // actually produces a dot (see ReadMarks).
            val ctx = requireContext()
            val openedSince = ReadMarks.takePending(ctx)
            val runningLocally = Running.active(ctx)
            for (s in sessions) {
                if (s.id == openedSince) {
                    ReadMarks.markRead(ctx, s.id, s.messageCount)
                }
                s.unread = ReadMarks.isUnread(ctx, s)
                if (runningLocally.contains(s.id)) s.running = true
            }
            all.clear()
            all.addAll(sessions)
            applyFilter()
        }
    }

    /**
     * Keeps the list live while it is on screen: which session is working, and
     * which ones answered unseen.
     *
     * `/api/status` is 2 KB and answers "is anything running at all"; the session
     * page is 115 KB, so it is only fetched while something IS running. Polling the
     * page unconditionally would move tens of megabytes an hour for a pulse.
     */
    private fun startActivityPoll() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                if (loading) continue
                val busy = withContext(Dispatchers.IO) { api.gatewayBusy() }
                val localRunning = Running.active(requireContext())
                if (!busy && localRunning.isEmpty()) {
                    if (all.any { it.running }) {
                        all.forEach { it.running = false }
                        applyFilter()
                    }
                    continue
                }
                val fresh = withContext(Dispatchers.IO) { api.sessions(limit = 20, order = "recent") }
                if (fresh.isEmpty()) continue
                val byId = fresh.associateBy { it.id }
                // Reconcile the local registry against the server. The local entry
                // exists to make the pulse appear instantly on this phone's own
                // turns — it must not outlive the turn. It used to: if the chat
                // screen was closed mid-turn, `Done` never arrived, the entry sat
                // there for its whole TTL and the row kept pulsing long after the
                // answer was read. A server row that says "not running" for 20 s
                // is the authority; the grace period covers the moment right after
                // submit, when the server has not stamped anything yet.
                val now = System.currentTimeMillis()
                for (id in localRunning) {
                    val row = byId[id] ?: continue
                    val localAge = now - (Running.since(requireContext(), id) ?: now)
                    if (!row.running && localAge > 20_000) Running.finished(requireContext(), id)
                }
                val stillLocal = Running.active(requireContext())
                var changed = false
                for (s in all) {
                    val f = byId[s.id]
                    // A row outside the fetched page (or absent from it) is not
                    // running unless this phone started it — "sticky true" was how
                    // a finished turn could pulse forever.
                    val runningNow = if (f != null) (f.running || stillLocal.contains(s.id))
                    else stillLocal.contains(s.id)
                    if (f != null && f.messageCount != s.messageCount) {
                        s.messageCount = f.messageCount
                        // New messages arrived while the list was open: recompute
                        // the dot instead of waiting for the next full reload.
                        s.unread = ReadMarks.isUnread(requireContext(), s)
                        changed = true
                    }
                    if (s.running != runningNow) {
                        s.running = runningNow
                        s.activity = if (runningNow) (f?.activity ?: "") else ""
                        changed = true
                    }
                }
                if (changed) applyFilter()
            }
        }
    }

    private fun stopActivityPoll() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        val selected = if (::projectFilter.isInitialized) projectFilter.selected else null
        val knownPaths = if (::projectFilter.isInitialized) projectFilter.knownPaths else emptyList()
        val shown = all.filter { s ->
            (q.isEmpty() || s.haystack.contains(q)) &&
                ProjectFilterModel.matches(s, selected, knownPaths)
        }
        adapter.submit(shown)
        if (shown.isEmpty()) {
            status.visibility = View.VISIBLE
            status.text = if (q.isNotEmpty()) getString(R.string.nothing_found)
            else getString(R.string.no_sessions)
        } else {
            status.visibility = View.GONE
        }
    }

    /** Public entry for the host: sets (or clears) the project filter and
     *  refreshes the list. Called from MainActivity.openProject. */
    fun applyProject(project: Project?) {
        if (!::projectFilter.isInitialized) return
        if (projectFilter.selected == project) {
            renderProjectBanner()
            return
        }
        projectFilter.selected = project
        renderProjectBanner()
        applyFilter()
    }

    private fun renderProjectBanner() {
        val banner = projectBanner ?: return
        val project = if (::projectFilter.isInitialized) projectFilter.selected else null
        if (project == null) {
            banner.visibility = View.GONE
        } else {
            projectLabel?.text = getString(R.string.projects) + ": " + project.label
            banner.visibility = View.VISIBLE
        }
    }

    private fun openChat(session: Session?) {
        session?.let {
            ReadMarks.markRead(requireContext(), it.id, it.messageCount)
            if (it.unread) {
                // Optimistic clear: the dot must not survive the tap.
                it.unread = false
                // The server's own flag is still PATCHed — other clients read it —
                // but the dot here is driven by ReadMarks, because on this gateway
                // `unread` never comes back true.
                scope.launch { withContext(Dispatchers.IO) { api.markRead(it.id) } }
            }
            (activity as? Host)?.onOpenChat(it.id, it.title)
        } ?: (activity as? Host)?.onOpenChat(null, null)
    }

    // ── Profiles ─────────────────────────────────────────────────────────────

    /** Profile chips above the list (kanban-style): «Все» + one per profile.
     *  The choice scopes every listing/create call and persists. */
    private fun loadProfiles() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { api.profiles() }
            if (list.isEmpty()) return@launch
            profiles = list
            val saved = api.profile
            // Drop a persisted profile the server no longer has.
            if (saved != null && list.none { it.name == saved }) api.profile = null
            renderProfileChips()
        }
    }

    private fun renderProfileChips() {
        val strip = view?.findViewById<android.widget.LinearLayout>(R.id.profileChips) ?: return
        strip.removeAllViews()
        val active = api.profile
        fun chip(label: String, tag: String?, activeNow: Boolean) {
            val chip = android.widget.TextView(requireContext()).apply {
                text = label
                textSize = 13f
                setPadding(36, 12, 36, 12)
                setBackgroundResource(if (activeNow) R.drawable.bg_chip_active else R.drawable.bg_chip)
                setTextColor(if (activeNow) android.graphics.Color.WHITE else requireContext().getColor(R.color.text_primary))
                setOnClickListener {
                    api.profile = tag
                    renderProfileChips()
                    load(force = true)
                }
            }
            strip.addView(chip)
            (chip.layoutParams as? android.widget.LinearLayout.LayoutParams)?.marginEnd = 16
        }
        chip(getString(R.string.profiles_all), null, active == null)
        for (p in profiles) {
            chip(p.displayName.ifEmpty { p.name }, p.name, active == p.name)
        }
        strip.parent.let { (it as? View)?.visibility = View.VISIBLE }
    }

    // ── Swipes ────────────────────────────────────────────────────────────────

    /** Right swipe pins (the only direction enabled — left goes to the pager
     *  as page navigation; delete stays in the long-press menu). */
    private fun attachSwipes() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.END
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                // Headers and the "Продолжить" row are not swipeable.
                if (adapter.rowAt(viewHolder.bindingAdapterPosition) !is SessionAdapter.Row.Item) {
                    return 0
                }
                return super.getMovementFlags(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val row = adapter.rowAt(viewHolder.bindingAdapterPosition)
                val session = (row as? SessionAdapter.Row.Item)?.session ?: run {
                    adapter.notifyItemChanged(viewHolder.bindingAdapterPosition)
                    return
                }
                scope.launch {
                    withContext(Dispatchers.IO) { api.setPinned(session.id, !session.pinned) }
                    load(force = true)
                }
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(list)
    }

    private fun showActions(session: Session) {
        val pinLabel = getString(if (session.pinned) R.string.unpin else R.string.pin)
        val items = arrayOf<CharSequence>(
            pinLabel, getString(R.string.rename), getString(R.string.archive_action), getString(R.string.delete)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(session.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> scope.launch {
                        withContext(Dispatchers.IO) { api.setPinned(session.id, !session.pinned) }
                        load(force = true)
                    }
                    1 -> askRename(session)
                    2 -> scope.launch {
                        val ok = withContext(Dispatchers.IO) { api.setArchived(session.id, true) }
                        if (ok) {
                            ArchiveStore.add(requireContext(), session.id, session.title)
                            all.removeAll { it.id == session.id }
                            applyFilter()
                            Toast.makeText(requireContext(), R.string.archived_toast, Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> askDelete(session)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askRename(session: Session) {
        val field = EditText(requireContext()).apply {
            setText(session.title)
            setSingleLine(true)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rename)
            .setView(field)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = field.text.toString().trim()
                if (title.isEmpty()) return@setPositiveButton
                scope.launch {
                    withContext(Dispatchers.IO) { api.renameSession(session.id, title) }
                    load(force = true)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askDelete(session: Session) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(session.title)
            .setPositiveButton(R.string.delete) { _, _ ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { api.deleteSession(session.id) }
                    if (ok) {
                        all.removeAll { it.id == session.id }
                        applyFilter()
                        Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    override fun onDestroyView() {
        if (::statusBar.isInitialized) statusBar.destroy()
        scope.cancel()
        super.onDestroyView()
    }

    companion object {
        /** Cheap flag first, session page only while something runs. */
        private const val POLL_MS = 6_000L
        private const val IME_ACTION_SEARCH = EditorInfo.IME_ACTION_SEARCH
    }
}
