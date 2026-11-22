package ru.evgen.hermes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * «Разрешить»/«Отклонить» straight from the notification shade: answers the
 * pending approval without opening the app.
 *
 * The reply needs the still-open gateway socket held by ChatFragment's
 * ApiClient (a fresh ApiClient here would have no socket). If the process died
 * with the activity there is nothing to answer on — the server retracts the
 * request on timeout anyway.
 */
class RequestActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ANSWER) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val choice = intent.getStringExtra(EXTRA_CHOICE) ?: return
        Notifications.cancelRequest(context, requestId.hashCode())
        ChatFragment.liveApi?.answerApproval(requestId, choice)
        ChatFragment.pendingAnswered[requestId] = choice
    }

    companion object {
        const val EXTRA_REQUEST_ID = "ru.evgen.hermes.EXTRA_REQUEST_ID"
        const val EXTRA_CHOICE = "ru.evgen.hermes.EXTRA_CHOICE"
        const val ACTION_ANSWER = "ru.evgen.hermes.ACTION_REQUEST_ANSWER"
    }
}
