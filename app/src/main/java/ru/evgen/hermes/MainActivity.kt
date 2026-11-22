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
    private var profiles: List<ApiClient.ProfileInfo> = emptyList()

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
        loadProfiles()
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
            if (it.unread) {
                // Optimistic clear: the dot must not survive the tap; if the
                // fenced PATCH fails the next listing puts it back.
                it.unread = false
                scope.launch { withContext(Dispatchers.IO) { api.markRead(it.id) } }
            }
        }
        startActivity(intent)
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
        val strip = findViewById<android.widget.LinearLayout>(R.id.profileChips) ?: return
        strip.removeAllViews()
        val active = api.profile
        fun chip(label: String, tag: String?, activeNow: Boolean) {
            val chip = android.widget.TextView(this).apply {
                text = label
                textSize = 13f
                setPadding(36, 12, 36, 12)
                setBackgroundResource(if (activeNow) R.drawable.bg_chip_active else R.drawable.bg_chip)
                setTextColor(if (activeNow) android.graphics.Color.WHITE else getColor(R.color.text_primary))
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
            // Version in the title: after a silent install the only way to know
            // which build is actually running was the diagnostics log.
            .setTitle(getString(R.string.more) + " · " + versionName() + " (" + currentVersion() + ")")
            .setItems(arrayOf<CharSequence>(
                getString(R.string.archive),
                "Проверить обновление",
                "Shizuku: $shizuku",
                "Фоновая работа",
                "Проверка установки",
                "Лог и падения",
                getString(R.string.logout)
            )) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, ArchiveActivity::class.java))
                    1 -> checkForUpdates(manual = true)
                    2 -> explainShizuku()
                    3 -> checkBackgroundWork()
                    4 -> checkInstallVerification()
                    5 -> showLog()
                    else -> askLogout()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** What Shizuku is for and what to do when it is not ready — the status line
     *  alone says nothing to someone who has not set it up. */
    private fun explainShizuku() {
        val state = ShizukuInstall.state(this)
        val text = when (state) {
            ShizukuInstall.State.READY ->
                "Shizuku запущен, доступ выдан. Обновления скачиваются и ставятся молча, " +
                    "без запроса «разрешить установку из этого источника»."
            ShizukuInstall.State.NO_PERMISSION ->
                "Shizuku запущен, но приложению ещё не выдан доступ. Нажми «Дать доступ» — " +
                    "Shizuku покажет свой диалог, разреши в нём. Или в самом Shizuku открой " +
                    "раздел «Приложения с доступом» и включи Hermes."
            ShizukuInstall.State.NOT_RUNNING ->
                "Shizuku установлен, но служба не запущена. Открой Shizuku и запусти её " +
                    "(кнопка «Start»), потом вернись сюда и нажми «Дать доступ»."
            ShizukuInstall.State.NOT_INSTALLED ->
                "Shizuku не установлен — обновления ставятся обычным установщиком, " +
                    "с запросом разрешения. Если хочешь обновляться в одно нажатие, " +
                    "поставь Shizuku."
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Shizuku")
            .setMessage(text)
            .setNegativeButton("Закрыть", null)
            // The bridge is checkable on its own: this runs one harmless shell
            // command through Shizuku and shows the raw answer, so a failed
            // update stops being a mystery toast.
            .setNeutralButton("Проверить") { _, _ -> checkShizuku() }
        // Asking for the grant has to be reachable on its own. It used to happen
        // only inside an update, so with no newer build available there was no way
        // to hand the app the permission at all.
        if (state == ShizukuInstall.State.NO_PERMISSION) {
            builder.setPositiveButton("Дать доступ") { _, _ -> requestShizuku() }
        }
        builder.show()
    }

    /** Runs the Shizuku self-test and shows exactly what came back. */
    private fun checkShizuku() {
        if (ShizukuInstall.state(this) != ShizukuInstall.State.READY) {
            Toast.makeText(this, "Сначала запусти Shizuku и дай доступ", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Проверяю…", Toast.LENGTH_SHORT).show()
        ShizukuInstall.selfTest(this) { out ->
            AlertDialog.Builder(this)
                .setTitle("Shizuku: проверка")
                .setMessage(out.ifBlank { "пустой ответ" })
                .setPositiveButton("Копировать") { _, _ -> copyToClipboard(out) }
                .setNegativeButton("Закрыть", null)
                .show()
        }
    }

    /**
     * Doze is the most likely reason the socket dies while the screen is off:
     * Android freezes the app, the WebSocket times out, and answers stop arriving
     * until the app is opened again. Whitelisting is the fix, and it needs the
     * shell uid — exactly what Shizuku gives us.
     *
     * State is read first and shown raw: claiming "разрешено" from an exit code
     * would be a guess, and this is a thing users will check when something else
     * breaks.
     */
    private fun checkBackgroundWork() {
        if (ShizukuInstall.state(this) != ShizukuInstall.State.READY) {
            AlertDialog.Builder(this)
                .setTitle("Фоновая работа")
                .setMessage(
                    "Чтобы вывести приложение из режима энергосбережения (Doze), нужен Shizuku " +
                        "с выданным доступом.\n\nСейчас: " + ShizukuInstall.describe(this)
                )
                .setNegativeButton("Закрыть", null)
                .show()
            return
        }
        val probe = "dumpsys deviceidle whitelist | grep -i " + packageName +
            "; dumpsys deviceidle iswhitelisted " + packageName
        ShizukuInstall.runCommand(this, probe) { out ->
            // `+pkg` present means allowed; `-pkg` means explicitly removed.
            val allowed = out.contains("+" + packageName) || out.contains("user," + packageName) ||
                out.contains("system," + packageName) || out.contains("=true")
            val builder = AlertDialog.Builder(this)
                .setTitle("Фоновая работа")
                .setMessage(
                    (if (allowed) "Приложение уже вне Doze: система не будет его усыплять.\n\n"
                    else "Приложение в Doze: в фоне система может усыпить его, и сокет шлюза " +
                        "оборвётся — ответы перестанут приходить, пока не откроешь приложение.\n\n") +
                        "Ответ системы:\n" + out.ifBlank { "(пусто)" }
                )
                .setNegativeButton("Закрыть", null)
            if (!allowed) {
                builder.setPositiveButton("Разрешить в фоне") { _, _ -> whitelistDoze() }
            }
            builder.show()
        }
    }

    private fun whitelistDoze() {
        val cmd = "dumpsys deviceidle whitelist +" + packageName +
            "; dumpsys deviceidle iswhitelisted " + packageName
        Toast.makeText(this, "Разрешаю…", Toast.LENGTH_SHORT).show()
        ShizukuInstall.runCommand(this, cmd) { out ->
            AlertDialog.Builder(this)
                .setTitle("Фоновая работа")
                .setMessage("Ответ системы:\n" + out.ifBlank { "(пусто)" })
                .setPositiveButton("Копировать") { _, _ -> copyToClipboard(out) }
                .setNegativeButton("Закрыть", null)
                .show()
        }
    }

    /**
     * Play Protect's install-time verifier is what puts "Установить всё равно?"
     * in front of every update: our APK is signed with our own key, so Google has
     * never seen it and the verifier always has something to say.
     *
     * The system's own settings are shown first (not our guess), then can be
     * switched — `verifier_verify_adb_installs` is the one the update flow uses,
     * `package_verifier_enable` covers installs from the file manager too.
     */
    private fun checkInstallVerification() {
        if (ShizukuInstall.state(this) != ShizukuInstall.State.READY) {
            AlertDialog.Builder(this)
                .setTitle("Проверка установки")
                .setMessage(
                    "Нужен Shizuku с выданным доступом — только у него есть право менять эти " +
                        "настройки.\n\nСейчас: " + ShizukuInstall.describe(this)
                )
                .setNegativeButton("Закрыть", null)
                .show()
            return
        }
        ShizukuInstall.verifierState(this) { state ->
            AlertDialog.Builder(this)
                .setTitle("Проверка установки")
                .setMessage(
                    "Система отвечает:\n$state\n\n" +
                        "0 — проверка выключена, 1 — включена.\n\n" +
                        "Обновления ставятся через shell, поэтому проверку shell-установок " +
                        "приложение выключает само перед каждой установкой. Остальные значения " +
                        "можно переключить здесь."
                )
                .setPositiveButton("Выключить везде") { _, _ -> setVerifier("all", false) }
                .setNeutralButton("Включить обратно") { _, _ -> setVerifier("adb", true) }
                .setNegativeButton("Закрыть", null)
                .show()
        }
    }

    private fun setVerifier(scope: String, on: Boolean) {
        ShizukuInstall.setVerifier(this, scope, on) { value ->
            Toast.makeText(this, "Теперь: $value", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

        cm.setPrimaryClip(android.content.ClipData.newPlainText("hermes", text))
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    /**
     * What the app knows about itself: its own event log (connection, turns,
     * Shizuku, update steps) plus, when Shizuku is available, the Android crash
     * buffer for this package.
     *
     * It exists because a crash report used to mean the user retyping a stack
     * trace off the screen. Everything is copyable in one tap.
     */
    private fun showLog() {
        val head = "Hermes " + versionName() + " (" + currentVersion() + ")\n" +
            "Shizuku: " + ShizukuInstall.describe(this) + "\n\n"
        val own = Diag.tail(120).ifBlank { "(журнал пуст — приложение ещё ничего не делало)" }

        if (ShizukuInstall.state(this) != ShizukuInstall.State.READY) {
            AlertDialog.Builder(this)
                .setTitle("Лог")
                .setMessage(head + own)
                .setPositiveButton("Копировать") { _, _ -> copyToClipboard(head + own) }
                .setNegativeButton("Закрыть", null)
                .show()
            return
        }

        // -t bounds the output: the crash buffer keeps days of unrelated noise.
        val cmd = "logcat -b crash -d -t 200 2>/dev/null | tail -60"
        ShizukuInstall.runCommand(this, cmd) { crashes ->
            val text = head + own + "\n\n— падения (logcat -b crash) —\n" + crashes.ifBlank { "(пусто — падений не было)" }
            AlertDialog.Builder(this)
                .setTitle("Лог")
                .setMessage(text)
                .setPositiveButton("Копировать") { _, _ -> copyToClipboard(text) }
                .setNegativeButton("Закрыть", null)
                .show()
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun requestShizuku() {
        ShizukuInstall.ensurePermission(this) { granted ->
            val message = if (granted) {
                "Доступ выдан — обновления пойдут без диалогов системы"
            } else {
                "Доступ не выдан. Разрешить можно в самом Shizuku: «Приложения с доступом» → Hermes"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
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
            val (info, code) = withContext(Dispatchers.IO) { UpdateChecker.fetch(api) }
            when {
                info != null -> UpdateChecker.offer(this@MainActivity, info, currentVersion())
                !manual -> Unit
                // Say which failure it was. "Не удалось проверить" covered an
                // expired session, a 302 to /login and a dead network alike.
                code == 302 || code == 401 || code == 403 -> AlertDialog.Builder(this@MainActivity)
                    .setTitle("Проверка обновлений")
                    .setMessage("Сервер не пустил: код $code, сеанс истёк.\n\nВыйди и войди заново — тогда проверка заработает.")
                    .setPositiveButton("Выйти") { _, _ -> askLogout() }
                    .setNegativeButton("Закрыть", null)
                    .show()
                code <= 0 -> Toast.makeText(
                    this@MainActivity, "Нет связи с сервером (${api.base})", Toast.LENGTH_LONG
                ).show()
                else -> AlertDialog.Builder(this@MainActivity)
                    .setTitle("Проверка обновлений")
                    .setMessage("Сервер ответил кодом $code, разобрать version.json не удалось.")
                    .setNegativeButton("Закрыть", null)
                    .show()
            }
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
