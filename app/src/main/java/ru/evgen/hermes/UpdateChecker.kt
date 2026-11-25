package ru.evgen.hermes

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.CookieManager
import android.widget.Toast
import org.json.JSONObject
import java.io.File

/**
 * Self-update without a store.
 *
 * The server publishes `/version.json` next to the webapp:
 * `{versionCode, versionName, apk, sha256, notes}`. The app reads it over HTTP
 * (same client as everything else), compares `versionCode` with its own, and on
 * a newer build offers to download and install.
 *
 * `versionCode` must increase and the APK must be signed with the same key —
 * otherwise Android refuses the install over the existing app and the user
 * would lose their login by uninstalling first.
 */
object UpdateChecker {

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String
    ) {
        companion object {
            fun parse(raw: String?): Info? {
                val o = runCatching { JSONObject(raw ?: "") }.getOrNull() ?: return null
                val code = o.optInt("versionCode", 0)
                if (code <= 0) return null
                return Info(
                    versionCode = code,
                    versionName = o.optString("versionName"),
                    apkUrl = o.optString("apk"),
                    sha256 = o.optString("sha256"),
                    notes = o.optString("notes")
                )
            }
        }
    }

    private var receiver: BroadcastReceiver? = null

    /**
     * Reads `/version.json` **through the logged-in client**.
     *
     * It used to build its own bare OkHttpClient — no cookie jar — so the request
     * arrived unauthenticated, the server answered 302 to /login and the app said
     * "Не удалось проверить обновления" forever. The status code comes back with
     * the result so the message can say which of the two it was.
     */
    fun fetch(api: ApiClient): Pair<Info?, Int> {
        val (code, body) = api.getWithStatus("/version.json?ts=" + System.currentTimeMillis())
        return Info.parse(body) to code
    }

    fun offer(activity: Activity, info: Info, currentVersionCode: Int) {
        if (info.versionCode <= currentVersionCode) return
        val notes = if (info.notes.isBlank()) "" else "\n\n${info.notes}"
        val shizukuState = ShizukuInstall.describe(activity)
        val viaShizuku = ShizukuInstall.isReady()
        activity.runOnUiThread {
            android.app.AlertDialog.Builder(activity)
                .setTitle("Обновление ${info.versionName}")
                .setMessage(
                    "Установлено: $currentVersionCode, доступно: ${info.versionCode}$notes" +
                        "\n\nShizuku: $shizukuState" +
                        if (viaShizuku) " — поставится без запросов системы."
                        else " — поставится обычным установщиком."
                )
                .setPositiveButton("Скачать") { _, _ -> download(activity, info) }
                .setNegativeButton("Позже", null)
                .show()
        }
    }

    private fun download(activity: Activity, info: Info) {
        // A relative apkUrl is resolved against the server the app is logged into,
        // never against a build-time host: the update channel belongs to whoever
        // runs the server.
        val url = if (info.apkUrl.startsWith("http")) info.apkUrl
        else ApiClient.get(activity).base.trimEnd('/') + "/" + info.apkUrl.removePrefix("./").removePrefix("/")
        // Unique name per attempt. DownloadManager appends "-1" when the target
        // already exists, and then the path we hand to `pm install` points at a
        // file that was never written — the install fails and quietly falls back
        // to the installer, which looks like "Shizuku does not work".
        val fileName = "hermes-${info.versionCode}-${System.currentTimeMillis()}.apk"
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val path = File(dir, fileName).absolutePath
        try {
            // Old attempts pile up otherwise: 2.4 MB each, never opened again.
            dir.listFiles { f -> f.name.startsWith("hermes-") && f.name.endsWith(".apk") }
                ?.forEach { runCatching { it.delete() } }

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType("application/vnd.android.package-archive")
                setTitle("Hermes ${info.versionName}")
                setDescription("Обновление приложения")
                // Both cookie sources: WebView's (what DownloadManager shares) and
                // ours from the OkHttp jar — the APK is behind the login, and
                // without it the "download" is a saved login page.
                val jar = ApiClient.get(activity).cookieHeader(url)
                val web = CookieManager.getInstance().getCookie(url)
                val cookie = listOfNotNull(jar, web).distinct().joinToString("; ")
                if (cookie.isNotBlank()) addRequestHeader("Cookie", cookie)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            watchCompletion(activity, id, info, path)
            toast(activity, "Скачиваем обновление…")
        } catch (e: Exception) {
            toast(activity, "Не удалось начать загрузку")
        }
    }

    /** System download broadcasts reach a runtime receiver without any manifest
     *  entry; the app only needs to be alive, and it is — the user just tapped. */
    private fun watchCompletion(activity: Activity, downloadId: Long, info: Info, path: String) {
        unregister(activity)
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                unregister(ctx)
                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val uri = dm.getUriForDownloadedFile(downloadId) ?: return
                // A 302 to /login still produces a "successful" download: an HTML
                // page saved as .apk. Installing it fails with a parse error that
                // reads like a Shizuku problem. Check the bytes first.
                val file = java.io.File(path)
                val looksApk = try {
                    file.inputStream().use { s ->
                        val head = ByteArray(2)
                        s.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                    }
                } catch (e: Exception) {
                    false
                }
                if (!file.exists() || file.length() < 500_000 || !looksApk) {
                    Diag.log("update", "скачанное не APK: ${file.length()} байт")
                    toast(activity, "Скачался не APK — проверь вход на сервере")
                    return
                }
                installFlow(activity, info, uri, path)
            }
        }
        receiver = r
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(r, filter)
        }
    }

    /**
     * Shizuku first, plain installer second.
     *
     * Through Shizuku the update is one silent `pm install` — no "allow from this
     * source", no installer screen. Every refusal (not installed, service down,
     * permission denied, pm failed) falls through to the normal path instead of
     * dead-ending: a missing Shizuku must never block an update.
     */
    private fun installFlow(activity: Activity, info: Info, apkUri: Uri, path: String) {
        if (!ShizukuInstall.isInstalled(activity)) {
            install(activity, apkUri)
            return
        }
        ShizukuInstall.ensurePermission(activity) { granted ->
            if (!granted) {
                toast(activity, "Shizuku не разрешён — обычная установка")
                install(activity, apkUri)
                return@ensurePermission
            }
            toast(activity, "Устанавливаю через Shizuku…")
            ShizukuInstall.install(activity, path) { ok, detail ->
                if (ok) {
                    // The new APK is on disk, but this process is still the old
                    // code. Offering a restart is the difference between "updated"
                    // and "updated and actually running the new build".
                    Diag.log("update", "установлено: ${detail.replace("\n", " | ").take(160)}")
                    android.app.AlertDialog.Builder(activity)
                        .setTitle("Обновление установлено")
                        .setMessage(detail.ifBlank { "pm: Success" } + "\n\nПерезапустить приложение, " +
                            "чтобы заработала новая версия?")
                        .setPositiveButton("Перезапустить") { _, _ -> restart(activity) }
                        .setNegativeButton("Позже", null)
                        .show()
                } else {
                    Diag.log("update", "Shizuku не смог: ${detail.take(120)}")
                    // Say WHY, not just "не смог": pm's own words are the only
                    // way to tell a missing grant from a refused APK.
                    android.app.AlertDialog.Builder(activity)
                        .setTitle("Shizuku не установил")
                        .setMessage(
                            "pm ответил:\n\n" + detail.take(600) +
                                "\n\nУстанавливаю обычным установщиком."
                        )
                        .setPositiveButton("Копировать") { _, _ ->
                            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("hermes", detail))
                        }
                        .setNegativeButton("Закрыть", null)
                        .show()
                    install(activity, apkUri)
                }
            }
        }
    }

    private fun unregister(context: Context) {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    /** Android 8+ gates sideloading per source: without this grant the install
     *  intent opens nothing at all, which looks like a broken button. */
    private fun install(activity: Activity, apkUri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            toast(activity, "Разреши установку из этого источника")
            runCatching {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            }
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { toast(activity, "Не удалось открыть установщик") }
    }

    private fun toast(context: Context, text: String) =
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    /**
     * Relaunches the app after an install.
     *
     * A plain `startActivity` from inside the activity being killed loses the
     * race, so the intent is handed to AlarmManager first and the process is
     * killed afterwards — that ordering is the whole trick.
     */
    private fun restart(activity: Activity) {
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = android.app.PendingIntent.getActivity(
            activity, 227, intent,
            android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = activity.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarm.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 150, pending)
        activity.finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
