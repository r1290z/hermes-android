package ru.evgen.hermes

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Watches the gateway while the app is in the background, so an answer that was not
 * started by this phone still reaches the user.
 *
 * Until now the app only notified about turns it had begun itself: the chat's own
 * poll dies with the screen. A turn started on the desktop, by a cron job or by
 * another device produced nothing at all — the phone was simply silent, which reads
 * as "the answer never came".
 *
 * Why a foreground service: Android freezes ordinary background processes, and a
 * polling loop inside one stops within seconds. The card it must show is honest —
 * "Hermes следит" — and it disappears by itself when the watch is over.
 *
 * The watch is deliberately short-lived. It starts when the app goes to the
 * background, polls every 20 s, and stops after [IDLE_LIMIT_MS] without activity, or
 * as soon as the app comes back to the front. A permanent card in the shade would be
 * a notification about notifications.
 */
class WatchService : Service() {

    private var worker: Thread? = null

    /** What each session looked like when the watch started, so only growth counts. */
    private val baseline = HashMap<String, Int>()
    private var lastActivity = 0L
    private var polls = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        Notifications.ensureChannels(this)
        Diag.init(this)
        // The contract with the system is unforgiving: once startForegroundService()
        // returns, this service MUST call startForeground() or the process is killed
        // with "did not then call Service.startForeground()". So the notification is
        // posted first and the typed call is only an upgrade; a refusal is logged and
        // the service stops cleanly instead of taking the app down with it.
        val posted = runCatching {
            startForeground(ID_WATCH, watchNotification(this))
            true
        }.getOrElse { e ->
            Diag.log("watch", "передний план не включён: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        if (!posted) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker == null) {
            lastActivity = System.currentTimeMillis()
            worker = Thread { loop() }.apply { isDaemon = true; start() }
            Diag.log("watch", "наблюдение начато")
        }
        return START_STICKY
    }

    private fun loop() {
        val api = ApiClient.get(this)
        Diag.log("watch", "поток наблюдения запущен")
        // Baseline first: without it the first poll would announce every session
        // that had any history at all.
        runCatching { api.sessionsForWatch() }
            .getOrNull()
            ?.forEach { baseline[it.id] = it.messageCount }

        while (true) {
            Thread.sleep(POLL_MS)
            if (!api.isLoggedIn()) break
            // The app is on screen: the user sees the answer, a toast-card would be
            // noise on top of the transcript.
            if (AppForeground.visible) {
                lastActivity = System.currentTimeMillis()
                continue
            }
            val rows = runCatching { api.sessionsForWatch() }.getOrNull()
            if (rows == null) {
                Diag.log("watch", "опрос не удался")
                continue
            }
            polls++
            // One line every ten polls (~3.5 min): enough to prove the loop is alive
            // in the phone's log, quiet enough not to bury the interesting lines.
            if (polls % 10 == 1) Diag.log("watch", "опрос №$polls, сеансов ${rows.size}")
            for (s in rows) {
                val before = baseline[s.id]
                if (before == null) {
                    // Not in the baseline, so it appeared after the watch began — a
                    // session created elsewhere. Its first answer is news, exactly
                    // like an answer in an old session; skipping it (the previous
                    // behaviour) meant a session started on the desktop never
                    // notified at all, which is the case this watch exists for.
                    baseline[s.id] = s.messageCount
                    if (s.messageCount == 0) continue
                } else {
                    if (s.messageCount <= before) continue
                    baseline[s.id] = s.messageCount
                }
                lastActivity = System.currentTimeMillis()
                // A turn this phone is streaming already notifies through the chat's
                // own completion path; announcing it here too would double it.
                if (ChatFragment.streamingSessionId == s.id) continue
                val snippet = runCatching { api.latestAssistantText(s.id) }.getOrNull().orEmpty()
                if (snippet.isBlank()) continue
                SessionSource.remember(s.id, s.source)
                Diag.log("watch", "новый ответ в ${s.id.take(12)}…")
                Notifications.showAnswer(this, s.id, s.title, snippet.take(400))
            }
            if (System.currentTimeMillis() - lastActivity > IDLE_LIMIT_MS) {
                Diag.log("watch", "тишина ${IDLE_LIMIT_MS / 60000} мин — выключаюсь")
                break
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        Diag.log("watch", "наблюдение окончено")
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    companion object {
        private const val POLL_MS = 20_000L
        private const val IDLE_LIMIT_MS = 15 * 60_000L
        const val ID_WATCH = 1100
        const val ACTION_STOP = "ru.evgen.hermes.WATCH_STOP"

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            try {
                val intent = Intent(context, WatchService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Diag.log("watch", "старт не удался: ${e.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, WatchService::class.java))
            } catch (e: Exception) {
            }
        }

        private fun watchNotification(context: Context): android.app.Notification {
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    android.app.PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = android.app.PendingIntent.getActivity(context, 77, open, flags)
            return NotificationCompat.Builder(context, Notifications.CH_WATCH)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Hermes следит")
                .setContentText("Ответ придёт, даже если он начат не здесь")
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(pi)
                .build()
        }
    }
}

/** Whether the app is on screen right now. Set by [MainActivity]; read by the watch. */
object AppForeground {
    @Volatile var visible: Boolean = false
}
