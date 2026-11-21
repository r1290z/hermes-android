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

    /** Reads `/version.json` through the app's own HTTP client, so the request
     *  carries the login like every other call. */
    fun fetch(base: String): Info? {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder()
            .url("$base/version.json?ts=" + System.currentTimeMillis())
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else Info.parse(response.body?.string())
            }
        } catch (e: Exception) {
            null
        }
    }

    fun offer(activity: Activity, info: Info, currentVersionCode: Int) {
        if (info.versionCode <= currentVersionCode) return
        val notes = if (info.notes.isBlank()) "" else "\n\n${info.notes}"
        activity.runOnUiThread {
            android.app.AlertDialog.Builder(activity)
                .setTitle("Обновление ${info.versionName}")
                .setMessage("Установлено: $currentVersionCode, доступно: ${info.versionCode}$notes")
                .setPositiveButton("Скачать") { _, _ -> download(activity, info) }
                .setNegativeButton("Позже", null)
                .show()
        }
    }

    private fun download(activity: Activity, info: Info) {
        val url = if (info.apkUrl.startsWith("http")) info.apkUrl
        else activity.let { "https://hermes.example.com/" + info.apkUrl.removePrefix("./").removePrefix("/") }
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType("application/vnd.android.package-archive")
                setTitle("Hermes ${info.versionName}")
                setDescription("Обновление приложения")
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "hermes-${info.versionCode}.apk"
                )
            }
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            watchCompletion(activity, id)
            toast(activity, "Скачиваем обновление…")
        } catch (e: Exception) {
            toast(activity, "Не удалось начать загрузку")
        }
    }

    /** System download broadcasts reach a runtime receiver without any manifest
     *  entry; the app only needs to be alive, and it is — the user just tapped. */
    private fun watchCompletion(activity: Activity, downloadId: Long) {
        unregister(activity)
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                unregister(ctx)
                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val uri = dm.getUriForDownloadedFile(downloadId) ?: return
                install(activity, uri)
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
}
