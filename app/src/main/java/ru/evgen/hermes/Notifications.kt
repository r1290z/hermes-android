package ru.evgen.hermes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Notifications for things that happen while the app is not on screen.
 *
 * Three channels, because the system treats them differently and the user does
 * too: a run in progress is quiet and ongoing, a finished answer is a normal
 * message, and "I need you to decide" has to wake the phone.
 *
 * Android 16+ gets a Live Update: the ongoing card is promoted above the shade,
 * appears on the lock screen and in the status-bar chip. The promotion flag is
 * `FLAG_PROMOTED_ONGOING` — API 36 has no `setRequestPromotedOngoing()`, and it
 * only takes effect when the notification is also ongoing, progress-styled and
 * carries a short critical text.
 */
object Notifications {

    private const val CH_STATUS = "hermes_status"
    private const val CH_ANSWER = "hermes_answer"

    const val ID_STATUS = 1001
    const val ID_ANSWER = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Ход работы", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Агент работает прямо сейчас"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ANSWER, "Готовые ответы", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Агент закончил и ждёт, когда ты прочитаешь"
            }
        )
    }

    /** Ongoing "I am working" card. Promoted as a Live Update on Android 16+. */
    fun showWorking(context: Context, sessionId: String?, sessionTitle: String, step: String) {
        val open = openIntent(context, sessionId)
        val title = sessionTitle.ifEmpty { "Hermes" }
        val text = step.ifEmpty { "Работаю…" }

        val notification: Notification = if (Build.VERSION.SDK_INT >= 36) {
            Notification.Builder(context, CH_STATUS)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(open)
                .setFlag(Notification.FLAG_PROMOTED_ONGOING, true)
                .setShortCriticalText("Работаю")
                .setStyle(
                    Notification.ProgressStyle()
                        .setStyledByProgress(false)
                        .setProgressIndeterminate(true)
                )
                .build()
        } else {
            NotificationCompat.Builder(context, CH_STATUS)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open)
                .build()
        }
        notify(context, ID_STATUS, notification)
    }

    /** The run ended and the answer is waiting. Cancels the working card, so the
     *  two can never sit in the shade together. */
    fun showAnswer(context: Context, sessionId: String?, sessionTitle: String, snippet: String) {
        NotificationManagerCompat.from(context).cancel(ID_STATUS)
        val text = snippet.ifEmpty { "Ответ готов" }
        val builder = NotificationCompat.Builder(context, CH_ANSWER)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(sessionTitle.ifEmpty { "Hermes" })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openIntent(context, sessionId))
        notify(context, ID_ANSWER, builder.build())
    }

    fun cancelWorking(context: Context) = NotificationManagerCompat.from(context).cancel(ID_STATUS)

    private fun openIntent(context: Context, sessionId: String?): PendingIntent {
        val intent = Intent(context, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId != null) putExtra(ChatActivity.EXTRA_SESSION_ID, sessionId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(context, (sessionId ?: "app").hashCode(), intent, flags)
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
