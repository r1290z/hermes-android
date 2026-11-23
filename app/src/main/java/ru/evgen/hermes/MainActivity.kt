package ru.evgen.hermes

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.adapter.FragmentStateAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Host activity: owns the fragment container (activity_shell) where the session
 * list now lives as [SessionsFragment] — the preparation for swipe navigation.
 * What stays here is everything that is not the list: the "…" menu, update
 * checks, Shizuku, diagnostics log, expenses/projects launchers and logout.
 *
 * FragmentActivity is required to host androidx fragments; the manifest keeps
 * singleTask, because notifications tap into this activity.
 */
class MainActivity : FragmentActivity(), SessionsFragment.Host, ChatFragment.Host {

    private lateinit var api: ApiClient
    private lateinit var pager: ViewPager2
    private lateinit var dots: List<View>

    /** Own scope for host-level work (update checks). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        api = ApiClient.get(this)
        if (!api.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_shell)
        pager = findViewById(R.id.pager)
        dots = listOf(findViewById(R.id.dot0), findViewById(R.id.dot1), findViewById(R.id.dot2))
        pager.adapter = PageAdapter()
        pager.isUserInputEnabled = true
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = renderDots(position)
        })
        renderDots(pager.currentItem)

        Notifications.ensureChannels(this)
        askNotificationPermission()

        // A notification tap: open the session it is about on the chat page.
        openFromIntent(intent)

        checkForUpdates(manual = false)
    }

    /**
     * Notification taps arrive here both on create and via [onNewIntent]
     * (singleTask). Without an id this does nothing — the pager opens on page 0.
     */
    private fun openFromIntent(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_SESSION_ID) ?: return
        val title = intent.getStringExtra(ChatFragment.EXTRA_SESSION_TITLE)
        showChat(id, title)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromIntent(intent)
    }

    override fun onRefreshLongPress() {
        checkForUpdates(manual = true)
    }

    override fun onMoreClicked() {
        showMore()
    }

    // ── Swipe shell ───────────────────────────────────────────────────────────

    /** Pages: 0 = projects, 1 = sessions, 2 = chat. */
    private inner class PageAdapter : FragmentStateAdapter(this@MainActivity) {
        override fun getItemCount() = 3
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> ProjectsFragment()
            1 -> SessionsFragment()
            else -> ChatFragment()
        }
    }

    /** A session row tapped on page 1 (or a notification tap): the chat page
     *  switches its session in place, without recreating the fragment. */
    private fun showChat(id: String?, title: String?) {
        val chat = chatFragment()
        if (id == null) {
            // «Новый сеанс»: nothing to open — the page has to be cleared. Without
            // this branch the button only paged over to the chat, which still had
            // the previous session in it, and the composer wrote into that one.
            // The pending handoff must go too, or a chat fragment created later
            // (page not built yet, or recreated) would resurrect the old session
            // from it and the button would look broken again.
            pendingSessionId = null
            pendingSessionTitle = null
            chat?.startNewSession()
        } else if (chat != null) {
            chat.openSession(id, title)
        } else {
            // Not created yet (first tap right after launch). The session is PARKED
            // here and PULLED by ChatFragment when its view exists — do not hand it
            // over through createFragment.
            //
            // The bug this fixes: ViewPager2 calls createFragment() on its own
            // schedule. When the fragment object had already been made (its view
            // still pending), createFragment never ran again, the args stayed null,
            // and the first tap opened an empty «Новый сеанс». Second tap worked
            // because by then the fragment had a view and openSession() ran direct.
            pendingSessionId = id
            pendingSessionTitle = title
        }
        pager.currentItem = 2
    }

    override fun onOpenChat(id: String?, title: String?) = showChat(id, title)

    /** The chat's back button: to the sessions page, not out of the app. */
    override fun onChatBack() {
        pager.currentItem = 1
    }

    /** The session the chat should open with when its fragment is created —
     *  set when a notification arrives before page 2 was ever shown. */
    private var pendingSessionId: String? = null
    private var pendingSessionTitle: String? = null

    /** The live chat page, or null. A fragment whose view is gone cannot be told to
     *  open anything — touching its views would throw, so it does not count. */
    private fun chatFragment(): ChatFragment? =
        supportFragmentManager.fragments
            .filterIsInstance<ChatFragment>()
            .firstOrNull { it.view != null }

    /**
     * The session the chat page must open, taken once.
     *
     * Pull, not push: the page asks when it is ready to show something. Any other
     * ordering loses the request — a fragment created before this field is set never
     * re-reads it, and a fragment whose view is not built yet cannot accept it.
     */
    override fun takePendingChat(): Pair<String, String?>? {
        val id = pendingSessionId ?: return null
        val title = pendingSessionTitle
        pendingSessionId = null
        pendingSessionTitle = null
        return id to title
    }

    /** Called from page 0: pick the project filter and show the sessions. */
    fun openProject(project: Project?) {
        sessionsFragment()?.applyProject(project)
        // The filter lives in the shared ViewModel: it survives the fragment
        // not being created yet (first tap right after launch) and recreation.
        ViewModelProvider(this)[ProjectFilterModel::class.java].selected = project
        pager.currentItem = 1
    }

    /** Page 0 highlights the selected row; when the filter was reset from the
     *  sessions page banner, the highlight must drop. */
    fun syncProjectSelection() {
        supportFragmentManager.fragments.filterIsInstance<ProjectsFragment>()
            .forEach { it.syncSelection() }
    }

    private fun sessionsFragment(): SessionsFragment? =
        supportFragmentManager.fragments.filterIsInstance<SessionsFragment>().firstOrNull()

    private fun renderDots(position: Int) {
        for (i in dots.indices) {
            dots[i].background?.setTint(
                getColor(if (i == position) R.color.accent else R.color.text_secondary)
            )
        }
    }

    /** Back from page 1 goes to page 0 (projects), not out of the app. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::pager.isInitialized && pager.currentItem != 0) {
            pager.currentItem = pager.currentItem - 1
        } else {
            super.onBackPressed()
        }
    }

    // ── The "…" menu ──────────────────────────────────────────────────────────

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
                getString(R.string.expenses),
                "Лог и падения",
                getString(R.string.logout)
            )) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, ArchiveActivity::class.java))
                    1 -> checkForUpdates(manual = true)
                    2 -> explainShizuku()
                    3 -> checkBackgroundWork()
                    4 -> checkInstallVerification()
                    5 -> startActivity(Intent(this, ExpensesActivity::class.java))
                    6 -> showLog()
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
        // The file keeps several launches, so the interesting run — the one that
        // ended in a crash — is still there after the process died.
        val footer = "\n\nЖурнал пишется в файл и переживает перезапуск."

        if (ShizukuInstall.state(this) != ShizukuInstall.State.READY) {
            AlertDialog.Builder(this)
                .setTitle("Лог")
                .setMessage(head + own + footer)
                .setPositiveButton("Копировать") { _, _ -> copyToClipboard(head + own + footer) }
                .setNegativeButton("Закрыть", null)
                .show()
            return
        }

        // -t bounds the output: the crash buffer keeps days of unrelated noise.
        val cmd = "logcat -b crash -d -t 200 2>/dev/null | tail -60"
        ShizukuInstall.runCommand(this, cmd) { crashes ->
            val text = head + own + footer + "\n\n— падения (logcat -b crash) —\n" +
                crashes.ifBlank { "(пусто — падений не было)" }
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 1002

        /** Kept for the notification tap intent: opening a session from the
         *  shade has to land in the chat, not on the list. */
        const val EXTRA_SESSION_ID = "ru.evgen.hermes.SESSION_ID"
    }
}
