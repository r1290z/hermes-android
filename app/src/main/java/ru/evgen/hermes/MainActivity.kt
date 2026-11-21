package ru.evgen.hermes

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home screen: the session list.
 *
 * Everything here is native and talks to the API through [ApiClient] — there is
 * no WebView anywhere in the app any more. The list exists because a phone needs
 * to find a session in one screen, which the desktop sidebar cannot do.
 */
class MainActivity : Activity() {

    private lateinit var api: ApiClient
    private lateinit var list: RecyclerView
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var adapter: SessionAdapter

    private val all = mutableListOf<Session>()

    /** Own scope: plain Activity is not a LifecycleOwner. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var statusBar: StatusBar
    private val ui = Handler(Looper.getMainLooper())
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        api = ApiClient.get(this)
        if (!api.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        search = findViewById(R.id.search)
        status = findViewById(R.id.status)

        adapter = SessionAdapter(
            onOpen = { openChat(it) },
            onLongPress = { session, _ -> showActions(session) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null
        attachSwipes()

        findViewById<View>(R.id.newSession).setOnClickListener { openChat(null) }
        findViewById<View>(R.id.btnKanban).setOnClickListener {
            startActivity(Intent(this, ru.evgen.hermes.kanban.KanbanBoardActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnRefresh).apply {
            setOnClickListener { load(force = true) }
            setOnLongClickListener {
                checkForUpdates(manual = true)
                true
            }
        }
        findViewById<ImageButton>(R.id.btnLogout).setOnClickListener { showMore() }

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

        Notifications.ensureChannels(this)
        askNotificationPermission()

        statusBar = StatusBar(this, findViewById(R.id.statusBar), api)
        findViewById<View>(R.id.statusBar).setOnClickListener { statusBar.showDetails() }

        load(force = true)
        checkForUpdates(manual = false)
    }

    override fun onResume() {
        super.onResume()
        if (!api.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        // Coming back from a chat: the title or the last-active time changed.
        if (!loading && all.isNotEmpty()) load(force = true)
        if (::statusBar.isInitialized) statusBar.start()
    }

    override fun onPause() {
        super.onPause()
        // The strip polls every 20 s; no reason to keep the radio busy behind
        // another screen.
        if (::statusBar.isInitialized) statusBar.stop()
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
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                finish()
                return@launch
            }
            all.clear()
            all.addAll(sessions)
            applyFilter()
        }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        val shown = if (q.isEmpty()) all else all.filter { it.haystack.contains(q) }
        adapter.submit(shown)
        if (shown.isEmpty()) {
            status.visibility = View.VISIBLE
            status.text = if (q.isNotEmpty()) getString(R.string.nothing_found)
            else getString(R.string.no_sessions)
        } else {
            status.visibility = View.GONE
        }
    }

    private fun openChat(session: Session?) {
        val intent = Intent(this, ChatActivity::class.java)
        session?.let {
            intent.putExtra(ChatActivity.EXTRA_SESSION_ID, it.id)
            intent.putExtra(ChatActivity.EXTRA_SESSION_TITLE, it.title)
        }
        startActivity(intent)
    }

    // ── Swipes ────────────────────────────────────────────────────────────────

    /** Right swipe pins, left swipe deletes — the two things a thumb does most
     *  on this screen. Long-press menu stays as the full set of actions. */
    private fun attachSwipes() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.START or ItemTouchHelper.END
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
                if (direction == ItemTouchHelper.END) {
                    scope.launch {
                        withContext(Dispatchers.IO) { api.setPinned(session.id, !session.pinned) }
                        load(force = true)
                    }
                } else {
                    // The row is already visually gone; put it back until the
                    // user confirms, otherwise "Отмена" leaves a hole.
                    adapter.notifyItemChanged(viewHolder.bindingAdapterPosition)
                    askDelete(session)
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
        AlertDialog.Builder(this)
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
                            ArchiveStore.add(this@MainActivity, session.id, session.title)
                            all.removeAll { it.id == session.id }
                            applyFilter()
                            Toast.makeText(this@MainActivity, R.string.archived_toast, Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> askDelete(session)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askRename(session: Session) {
        val field = EditText(this).apply {
            setText(session.title)
            setSingleLine(true)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(session.title)
            .setPositiveButton(R.string.delete) { _, _ ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { api.deleteSession(session.id) }
                    if (ok) {
                        all.removeAll { it.id == session.id }
                        applyFilter()
                        Toast.makeText(this@MainActivity, R.string.deleted, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The "…" button: archive, update, Shizuku state, then the destructive end. */
    private fun showMore() {
        val shizuku = ShizukuInstall.describe(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.more)
            .setItems(arrayOf<CharSequence>(
                getString(R.string.archive),
                "Проверить обновление",
                "Shizuku: $shizuku",
                getString(R.string.logout)
            )) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, ArchiveActivity::class.java))
                    1 -> checkForUpdates(manual = true)
                    2 -> explainShizuku()
                    else -> askLogout()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** What Shizuku is for and what to do when it is not ready — the status line
     *  alone says nothing to someone who has not set it up. */
    private fun explainShizuku() {
        val text = when (ShizukuInstall.state(this)) {
            ShizukuInstall.State.READY ->
                "Shizuku запущен, доступ выдан. Обновления скачиваются и ставятся молча, " +
                    "без запроса «разрешить установку из этого источника»."
            ShizukuInstall.State.NO_PERMISSION ->
                "Shizuku запущен, но приложению ещё не выдан доступ. Нажми " +
                    "«Проверить обновление» — приложение попросит разрешение в Shizuku, " +
                    "и дальше обновления пойдут без диалогов системы."
            ShizukuInstall.State.NOT_RUNNING ->
                "Shizuku установлен, но служба не запущена. Открой Shizuku и запусти её " +
                    "(кнопка «Start»), потом нажми «Проверить обновление»."
            ShizukuInstall.State.NOT_INSTALLED ->
                "Shizuku не установлен — обновления ставятся обычным установщиком, " +
                    "с запросом разрешения. Если хочешь обновляться в одно нажатие, " +
                    "поставь Shizuku."
        }
        AlertDialog.Builder(this)
            .setTitle("Shizuku")
            .setMessage(text)
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun askLogout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.logout)
            .setMessage(api.base)
            .setPositiveButton(R.string.logout) { _, _ ->
                api.forget()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── Updates and notifications ────────────────────────────────────────────

    private fun checkForUpdates(manual: Boolean) {
        scope.launch {
            val info = withContext(Dispatchers.IO) { UpdateChecker.fetch(api.base) }
            if (info == null) {
                if (manual) Toast.makeText(this@MainActivity, "Не удалось проверить обновления", Toast.LENGTH_SHORT).show()
                return@launch
            }
            UpdateChecker.offer(this@MainActivity, info, currentVersion())
        }
    }

    private fun currentVersion(): Int = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionCode
    } catch (e: Exception) {
        0
    }

    private fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST
        )
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    override fun onDestroy() {
        if (::statusBar.isInitialized) statusBar.destroy()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 1002
        private const val IME_ACTION_SEARCH = EditorInfo.IME_ACTION_SEARCH

        /** Kept for the notification tap intent: opening a session from the
         *  shade has to land in the chat, not on the list. */
        const val EXTRA_SESSION_ID = "ru.evgen.hermes.SESSION_ID"
    }
}
