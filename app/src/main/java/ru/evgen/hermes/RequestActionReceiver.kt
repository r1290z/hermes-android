package ru.evgen.hermes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Notification actions that do not open the app: «Разрешить»/«Отклонить» on an
 * approval, and «Стоп» on a running turn.
 *
 * Both need the gateway socket. The live one belongs to [ChatFragment]; a fresh
 * [ApiClient] here would have to reconnect first, which is worth doing rather than
 * doing nothing — the user pressed a button and expects it to act.
 *
 * Work runs off the main thread: a broadcast receiver has seconds before it is
 * killed, and an RPC round-trip does not fit in that budget on a bad connection.
 */
class RequestActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> answer(context, intent)
            ACTION_STOP -> stop(context, intent)
        }
    }

    private fun answer(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val choice = intent.getStringExtra(EXTRA_CHOICE) ?: return
        Notifications.cancelRequest(context, Notifications.ID_REQUEST_BASE + requestId.hashCode() % 1000)
        val api = ChatFragment.liveApi ?: ApiClient.get(context)
        api.answerApproval(requestId, choice)
        ChatFragment.pendingAnswered[requestId] = choice
    }

    /** «Стоп»: the same `session.interrupt` the chat's stop button sends. */
    private fun stop(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(MainActivity.EXTRA_SESSION_ID) ?: return
        val pending = goAsync()
        Thread {
            try {
                val api = ChatFragment.liveApi ?: ApiClient.get(context)
                api.stopTurn(sessionId)
                Diag.log("notify", "стоп из уведомления: ${sessionId.take(12)}…")
            } catch (e: Exception) {
                Diag.log("notify", "стоп не удался: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "ru.evgen.hermes.EXTRA_REQUEST_ID"
        const val EXTRA_CHOICE = "ru.evgen.hermes.EXTRA_CHOICE"
        const val ACTION_ANSWER = "ru.evgen.hermes.ACTION_REQUEST_ANSWER"
        const val ACTION_STOP = "ru.evgen.hermes.ACTION_TURN_STOP"
    }
}
