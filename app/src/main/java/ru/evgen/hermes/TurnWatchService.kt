package ru.evgen.hermes

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps the process alive while a turn is running, so the answer arrives even
 * with the screen off and the app in the background.
 *
 * Without it the WebSocket lives in an ordinary activity process: Android is free
 * to freeze or kill it as soon as the chat screen is not visible, which is
 * exactly when a long answer is still streaming. The symptom was already seen in
 * the logs — `SocketException: Software caused connection abort` — and looked
 * like a server problem.
 *
 * A foreground service raises the process priority; the notification it must show
 * is the same "Работаю" card the app already posts, so the user sees no
 * difference other than that it keeps working.
 *
 * Lifecycle is driven by [ChatFragment]: started when a turn begins, stopped when
 * it ends or the user is looking at the chat. START_NOT_STICKY on purpose — a
 * resurrected service with no socket behind it would only show a lying card.
 */
class TurnWatchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannels(this)
        Diag.init(this)
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        val title = intent?.getStringExtra(EXTRA_SESSION_TITLE).orEmpty()
        try {
            startForeground(
                Notifications.ID_STATUS,
                Notifications.workingNotification(this, sessionId, title, "Работаю…")
            )
            Diag.log("service", "передний план включён")
        } catch (e: Exception) {
            // Android 12+ can refuse a foreground start from the background; the
            // turn itself is unaffected, so this must never crash the app.
            Diag.log("service", "не удалось: ${e.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Diag.log("service", "передний план выключен")
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_SESSION_TITLE = "session_title"

        /** Safe to call from any state: a refused start is logged, not thrown. */
        fun start(context: Context, sessionId: String?, sessionTitle: String) {
            try {
                val intent = Intent(context, TurnWatchService::class.java).apply {
                    putExtra(EXTRA_SESSION_ID, sessionId)
                    putExtra(EXTRA_SESSION_TITLE, sessionTitle)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Diag.log("service", "старт не удался: ${e.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, TurnWatchService::class.java))
            } catch (e: Exception) {
            }
        }
    }
}
