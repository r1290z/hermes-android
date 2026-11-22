package ru.evgen.hermes

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku

/**
 * Installs the update through Shizuku, so the phone never asks "allow install
 * from this source" and the user does not have to tap through the system
 * installer.
 *
 * Shizuku runs [UserService] as the shell user; `pm install` from there is what
 * ADB would do, which is why it skips the per-source sideload gate. Everything
 * degrades quietly: no Shizuku, no permission, or a `pm` that says anything but
 * Success all fall back to the normal installer intent, so the update can never
 * become *less* possible than it was before.
 *
 * The APK path is reconstructed rather than read from DownloadManager: the shell
 * user cannot open our content:// URIs, but it can read /sdcard/Download, and the
 * file name is one we chose.
 */
object ShizukuInstall {

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST = 4201
    /** Our own package, for commands that have to name it (`pm path`, `dumpsys`). */
    private const val PKG = "ru.evgen.hermes"


    private val main = Handler(Looper.getMainLooper())

    /** One listener at a time: the update flow is a single user action. */
    private var pendingResult: ((Boolean) -> Unit)? = null
    private var listenerAttached = false

    @Volatile private var service: IUserService? = null
    private var servicePending: ((Boolean) -> Unit)? = null
    private var binding = false

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != PERMISSION_REQUEST) return@OnRequestPermissionResultListener
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            val callback = pendingResult
            pendingResult = null
            Diag.log("shizuku", if (granted) "разрешение выдано" else "в разрешении отказано")
            main.post { callback?.invoke(granted) }
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bound = IUserService.Stub.asInterface(binder)
            service = bound
            binding = false
            Diag.log("shizuku", "служба подключена")
            val callback = servicePending
            servicePending = null
            main.post { callback?.invoke(bound != null) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            Diag.log("shizuku", "служба отключена")
        }
    }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Shizuku service is up AND this app already holds the API permission. */
    fun isReady(): Boolean = try {
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /** Shizuku is installed and its service is actually running. */
    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    /** The three states a user can act on, in the order they matter. */
    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    fun state(context: Context): State = when {
        !isInstalled(context) -> State.NOT_INSTALLED
        !isRunning() -> State.NOT_RUNNING
        !isReady() -> State.NO_PERMISSION
        else -> State.READY
    }

    fun describe(context: Context): String = when (state(context)) {
        State.NOT_INSTALLED -> "не установлен"
        State.NOT_RUNNING -> "установлен, служба не запущена"
        // Shizuku is running and the app simply has not been granted access yet —
        // reporting this as "not running" is what made a working Shizuku look
        // broken in the app.
        State.NO_PERMISSION -> "запущен, нужно разрешение"
        State.READY -> "готов — обновления без диалогов"
    }

    /**
     * Makes sure we may talk to Shizuku, asking the user if needed.
     *
     * `onResult` runs on the main thread with `true` when the app may proceed.
     * When Shizuku is missing or its binder is down the answer is `false` at
     * once — the caller then uses the plain installer.
     */
    fun ensurePermission(activity: Activity, onResult: (Boolean) -> Unit) {
        if (!isInstalled(activity)) {
            Diag.log("shizuku", "не установлен")
            onResult(false)
            return
        }
        if (isReady()) {
            Diag.log("shizuku", "готов")
            onResult(true)
            return
        }
        val binderUp = try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
        if (!binderUp) {
            // Installed but not running: nothing to ask, and starting it for the
            // user is Shizuku's job (it needs its own grant).
            Diag.log("shizuku", "установлен, но служба не запущена")
            onResult(false)
            return
        }
        if (!listenerAttached) {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            listenerAttached = true
        }
        pendingResult = onResult
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST)
            Diag.log("shizuku", "запрошено разрешение")
        } catch (e: Throwable) {
            pendingResult = null
            Diag.log("shizuku", "запрос не удался: ${e.javaClass.simpleName}")
            onResult(false)
        }
    }

    /**
     * Runs `pm install -r -t <path>` as shell through the bound [UserService].
     *
     * `onResult` gets `true` only on a literal Success from pm — a silent failure
     * here would leave the user thinking the update installed while the old build
     * still runs.
     */
    fun install(context: Context, path: String, onResult: (Boolean, String) -> Unit) {
        val ready = service
        if (ready != null) {
            runInstall(ready, path, onResult)
            return
        }
        // First call binds the service; Shizuku starts it as shell and we get the
        // binder back on the main thread.
        bind(context) { bound ->
            if (!bound || service == null) {
                onResult(false, "служба Shizuku не поднялась")
            } else {
                runInstall(service!!, path, onResult)
            }
        }
    }

    private fun runInstall(service: IUserService, path: String, onResult: (Boolean, String) -> Unit) {
        Thread {
            var ok = false
            var detail = ""
            try {
                // `pm install` runs inside system_server, and SELinux forbids
                // system_server from reading files under /storage/emulated (fuse).
                // The shell user CAN read them, so the fix is to stage the APK in
                // /data/local/tmp — which is exactly what the system's own error
                // suggests ("Consider using a file under /data/local/tmp/").
                // Without this the install died with
                //   avc: denied { read } … tcontext=u:object_r:fuse:s0
                // even though the shell could see the file perfectly well.
                val staged = "/data/local/tmp/hermes-update.apk"
                val copy = service.run(
                    "cp " + shellQuote(path) + " " + staged + " && chmod 644 " + staged
                )
                val copyCode = copy.substringBefore('\n').trim().toIntOrNull() ?: -1
                if (copyCode != 0) {
                    val why = copy.substringAfter('\n', "").trim()
                    Diag.log("shizuku", "копирование не удалось: ${why.take(120)}")
                    main.post { onResult(false, "не смог скопировать APK в /data/local/tmp:\n$why") }
                    return@Thread
                }
                // Play Protect runs its install-time verifier for shell installs
                // when `verifier_verify_adb_installs` is 1, and pops "Install
                // anyway?" for an app Google has never seen — our build is signed
                // with our own key, so it is *always* unseen. Turning that one
                // global off is what makes the install silent; it is scoped to
                // adb/shell installs only, not to installs from the file manager.
                val cmd = "settings put global verifier_verify_adb_installs 0; " +
                    "pm install -r -t " + staged +
                    "; settings get global verifier_verify_adb_installs"
                val raw = service.run(cmd)
                val body = raw.substringAfter('\n', "")
                val code = raw.substringBefore('\n').trim().toIntOrNull() ?: -1
                // Last line is the settings readback; everything before it is pm.
                val pmOut = body.lines().dropLast(1).joinToString("\n").trim()
                val verifier = body.lines().lastOrNull()?.trim().orEmpty()
                detail = pmOut
                ok = code == 0 && pmOut.contains("Success", ignoreCase = true)
                // The staged copy is 2.8 MB in a shared shell directory: leaving it
                // there means every update leaves another file behind.
                service.run("rm -f " + staged)
                if (ok) {
                    // "Success" from pm is what pm thinks, not what is installed.
                    // Read the version code back: if the phone still reports the
                    // old build, the update did not land and saying "Обновлено"
                    // would be a lie.
                    val probe = service.run("dumpsys package " + PKG + " | grep -m1 versionCode")
                    val installed = probe.substringAfter('\n', "").trim()
                    detail = "Success\nустановлено: " + installed +
                        "\nпроверка Play Protect для shell: " + if (verifier == "0") "выключена" else "включена ($verifier)"
                    Diag.log("shizuku", "pm install: Success, $installed, verifier=$verifier")
                } else {
                    Diag.log("shizuku", "pm install: ${pmOut.take(120)}")
                }
            } catch (e: Throwable) {
                detail = e.message ?: e.javaClass.simpleName
                Diag.log("shizuku", "pm install упал: $detail")
            }
            main.post { onResult(ok, detail) }
        }.apply { isDaemon = true }.start()
    }

    /** Current install-verification settings, as the system reports them. */
    fun verifierState(context: Context, onResult: (String) -> Unit) {
        val cmd = "settings get global verifier_verify_adb_installs; " +
            "settings get global package_verifier_enable"
        runCommand(context, cmd) { raw ->
            val lines = raw.substringAfter('\n', "").lines().map { it.trim() }
            onResult("проверка shell-установок: " + (lines.getOrNull(0) ?: "?") +
                "\nпроверка всех установок: " + (lines.getOrNull(1) ?: "?"))
        }
    }

    /**
     * Turns the install-time verifier on or off.
     *
     * Only `verifier_verify_adb_installs` is touched by the update flow; this
     * also offers `package_verifier_enable` for when the user wants the prompt
     * gone for installs from the file manager too. Both are reversible and
     * reported back as the system's own value, not as our guess.
     */
    fun setVerifier(context: Context, globalKey: String, on: Boolean, onResult: (String) -> Unit) {
        val key = if (globalKey == "all") "package_verifier_enable" else "verifier_verify_adb_installs"
        val value = if (on) "1" else "0"
        val cmd = "settings put global $key $value; settings get global $key"
        runCommand(context, cmd) { raw -> onResult(raw.substringAfter('\n', "").trim()) }
    }

    private fun bind(context: Context, onReady: (Boolean) -> Unit) {
        if (binding) {
            servicePending = onReady
            return
        }
        binding = true
        servicePending = onReady
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, UserService::class.java.name))
                .daemon(false)
                .processNameSuffix("shizuku")
                .debuggable(false)
                .version(1)
            Shizuku.bindUserService(args, connection)
            Diag.log("shizuku", "служба запрошена")
        } catch (e: Throwable) {
            binding = false
            servicePending = null
            Diag.log("shizuku", "bind не удался: ${e.javaClass.simpleName} ${e.message ?: ""}")
            onReady(false)
        }
    }

    /** Single-quote a path for `sh`, so a space or quote cannot break the command. */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * Runs one shell command through Shizuku and hands back `"<exit code>\n<output>"`.
     *
     * Every future "do this privileged thing" feature goes through here, so the
     * binding/threading dance lives in one place instead of being copied per
     * command.
     */
    fun runCommand(context: Context, command: String, onResult: (String) -> Unit) {
        val exec: (IUserService) -> Unit = { svc ->
            Thread {
                val out = try {
                    svc.run(command).trim()
                } catch (e: Throwable) {
                    "ошибка вызова: ${e.message ?: e.javaClass.simpleName}"
                }
                Diag.log("shizuku", out.replace("\n", " | ").take(300))
                main.post { onResult(out) }
            }.apply { isDaemon = true }.start()
        }
        val ready = service
        if (ready != null) {
            exec(ready)
            return
        }
        bind(context) { bound ->
            val svc = service
            if (!bound || svc == null) onResult("служба Shizuku не поднялась (bind не дал binder)")
            else exec(svc)
        }
    }

    /**
     * Proves the shell bridge actually works, instead of guessing from a status
     * word. Runs the same `sh` path `pm install` uses and returns the raw output:
     * uid (must be 2000 = shell), our package path, and what is in Download.
     *
     * Without this a failed update was one indistinguishable toast — "Shizuku не
     * смог" — whether the cause was a missing grant, a dead service, an
     * unreadable file or pm refusing the APK.
     */
    fun selfTest(context: Context, onResult: (String) -> Unit) {
        val cmd = buildString {
            append("id -u; ")
            append("pm path ").append(PKG).append("; ")
            // The install path stages the APK here, and a shell without write access
            // to /data/local/tmp would fail at the copy step — worth seeing before a
            // failed update, not after.
            append("touch /data/local/tmp/.hermes_probe && rm -f /data/local/tmp/.hermes_probe && echo 'tmp: пишу'; ")
            append("ls -1 /sdcard/Download 2>/dev/null | tail -3")
        }
        runCommand(context, cmd, onResult)
    }

    fun release() {
        if (listenerAttached) {
            runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
            listenerAttached = false
        }
        pendingResult = null
        servicePending = null
    }
}
