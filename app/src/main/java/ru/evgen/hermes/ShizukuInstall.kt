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
                val raw = service.run("pm install -r -t " + shellQuote(path))
                val code = raw.substringBefore('\n').trim().toIntOrNull() ?: -1
                detail = raw.substringAfter('\n', "").trim()
                ok = code == 0 && detail.contains("Success", ignoreCase = true)
                Diag.log("shizuku", "pm install: ${if (ok) "Success" else detail.take(120)}")
            } catch (e: Throwable) {
                detail = e.message ?: e.javaClass.simpleName
                Diag.log("shizuku", "pm install упал: $detail")
            }
            main.post { onResult(ok, detail) }
        }.apply { isDaemon = true }.start()
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

    fun release() {
        if (listenerAttached) {
            runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
            listenerAttached = false
        }
        pendingResult = null
        servicePending = null
    }
}
