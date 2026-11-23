package ru.evgen.hermes

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * «Ответить» in the notification shade.
 *
 * Two targets, one action: a clarify question is answered through the request RPC,
 * anything else is a new message into the session. The difference matters — sending
 * a clarify answer as a prompt would leave the agent still waiting for its question
 * and add a stray user message on top.
 *
 * The typed text arrives through [RemoteInput], which is why the PendingIntent that
 * started this receiver had to be MUTABLE (see Notifications.replyAction).
 *
 * The reply is slow (it goes to the gateway), and a broadcast receiver gets seconds,
 * so the work is handed to a thread and the receiver reports completion through
 * [goAsync] — finishing early would have Android kill the process mid-send.
 */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val text = results.getCharSequence(Notifications.KEY_REPLY)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        val sessionId = intent.getStringExtra(MainActivity.EXTRA_SESSION_ID)
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val questionId = intent.getStringExtra(EXTRA_QUESTION_ID)
        val pending = goAsync()
        val appContext = context.applicationContext

        Thread {
            try {
                val api = ChatFragment.liveApi ?: ApiClient.get(appContext)
                if (requestId != null && questionId != null) {
                    api.answerClarify(requestId, questionId, text)
                    Diag.log("notify", "ответ на вопрос из уведомления")
                    Notifications.cancelRequest(
                        appContext,
                        Notifications.ID_REQUEST_BASE + requestId.hashCode() % 1000
                    )
                } else if (sessionId != null) {
                    // prompt.submit: starts a turn when idle, queues when one runs.
                    api.submitQueued(sessionId, text)
                    Diag.log("notify", "сообщение из уведомления: ${text.take(24)}")
                    Notifications.cancelAnswer(appContext, sessionId)
                }
            } catch (e: Exception) {
                Diag.log("notify", "ответ не ушёл: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_REPLY = "ru.evgen.hermes.ACTION_REPLY"
        const val EXTRA_REQUEST_ID = "ru.evgen.hermes.EXTRA_REPLY_REQUEST_ID"
        const val EXTRA_QUESTION_ID = "ru.evgen.hermes.EXTRA_REPLY_QUESTION_ID"
    }
}
