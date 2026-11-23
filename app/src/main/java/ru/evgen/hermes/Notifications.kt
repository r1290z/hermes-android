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
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/**
 * Notifications for what happens while the app is not on screen.
 *
 * Four channels, because the system treats them differently and the user does too:
 * a run in progress is quiet and ongoing, a finished answer is a normal message, a
 * failure is worth a sound, and "I need you to decide" has to wake the phone.
 *
 * Android 16+ gets a Live Update: the ongoing card is promoted above the shade,
 * appears on the lock screen and in the status-bar chip. The promotion flag is
 * `FLAG_PROMOTED_ONGOING` — API 36 has no `setRequestPromotedOngoing()`, and it
 * only takes effect when the notification is also ongoing, progress-styled and
 * carries a short critical text.
 *
 * Everything here works without opening the app: «Стоп» interrupts the running
 * turn, «Ответить» sends a message, approvals answer themselves. A notification
 * that only says "come look" is a notification the user has to fight.
 */
object Notifications {

    private const val CH_STATUS = "hermes_status"
    private const val CH_ANSWER = "hermes_answer"
    private const val CH_REQUEST = "hermes_request"
    private const val CH_ERROR = "hermes_error"

    const val ID_STATUS = 1001
    const val ID_SUMMARY = 1000
    const val ID_REQUEST_BASE = 2000
    const val ID_ERROR_BASE = 3000

    /** Answers stack under one header instead of filling the shade one card each. */
    private const val GROUP_ANSWERS = "ru.evgen.hermes.ANSWERS"

    /** RemoteInput key shared by every reply action. */
    const val KEY_REPLY = "ru.evgen.hermes.REPLY"

    /**
     * When the current turn started.
     *
     * Kept here rather than passed in from the chat because the same card is rebuilt
     * from three places (first submit, periodic progress, the foreground service) and
     * a stopwatch that restarts on each of them is a lie about how long it has run.
     */
    @Volatile private var turnStartedAt = 0L

    /** Answer notifications in the shade, for the «N ответов» summary. */
    private val openAnswers = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

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
        nm.createNotificationChannel(
            NotificationChannel(CH_REQUEST, "Нужен ответ", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Агент остановился и ждёт твоего решения"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ERROR, "Сбои", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Ход не выполнился: сеть, модель, отказ инструмента"
            }
        )
    }

    /** Forgets the stopwatch. Called when a turn ends, so the next one starts at 0. */
    fun turnFinished() {
        turnStartedAt = 0L
    }

    /** Ongoing "I am working" card. Promoted as a Live Update on Android 16+. */
    fun showWorking(
        context: Context,
        sessionId: String?,
        sessionTitle: String,
        step: String,
        model: String? = null
    ) {
        notify(context, ID_STATUS, workingNotification(context, sessionId, sessionTitle, step, model))
    }

    /**
     * The same card, returned instead of posted.
     *
     * `startForeground` needs the Notification object itself — a service that only
     * posted it through the manager would still be killed for "no attached
     * notification" within five seconds.
     */
    fun workingNotification(
        context: Context,
        sessionId: String?,
        sessionTitle: String,
        step: String,
        model: String? = null
    ): Notification {
        if (turnStartedAt == 0L) turnStartedAt = System.currentTimeMillis()
        val open = openIntent(context, sessionId)
        val stop = stopIntent(context, sessionId)
        val title = sessionTitle.ifEmpty { "Hermes" }
        val text = step.ifEmpty { "Работаю…" }
        // The model answers "on what" without opening the app; the ticking clock
        // answers "how long", which is what makes a stuck turn visible.
        val sub = model?.takeIf { it.isNotBlank() }

        return if (Build.VERSION.SDK_INT >= 36) {
            Notification.Builder(context, CH_STATUS)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(sub)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(turnStartedAt)
                .setUsesChronometer(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(open)
                .setFlag(Notification.FLAG_PROMOTED_ONGOING, true)
                .setShortCriticalText("Работаю")
                .setStyle(
                    Notification.ProgressStyle()
                        .setStyledByProgress(false)
                        .setProgressIndeterminate(true)
                )
                .apply {
                    // Platform Notification takes a platform Action; the compat
                    // builder below takes its own type.
                    stop?.let {
                        addAction(
                            Notification.Action.Builder(
                                null, context.getString(R.string.notif_stop), it
                            ).build()
                        )
                    }
                }
                .build()
        } else {
            NotificationCompat.Builder(context, CH_STATUS)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(sub)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(turnStartedAt)
                .setUsesChronometer(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open)
                .apply { stopAction(context, stop)?.let { addAction(it) } }
                .build()
        }
    }

    /**
     * The run ended and the answer is waiting. Cancels the working card, so the two
     * can never sit in the shade together.
     *
     * Three things here are not decoration:
     *  - one id PER SESSION under a group summary, so three answers do not stack as
     *    three unrelated cards;
     *  - a Telegram session is drawn as a chat message ([Person] + MessagingStyle),
     *    because that is literally what it is;
     *  - «Ответить» sends a message without opening the app.
     */
    fun showAnswer(
        context: Context,
        sessionId: String?,
        sessionTitle: String,
        snippet: String,
        channel: Channel.Kind = Channel.kind(SessionSource.of(sessionId))
    ) {
        NotificationManagerCompat.from(context).cancel(ID_STATUS)
        turnFinished()
        val text = snippet.ifEmpty { "Ответ готов" }
        val title = sessionTitle.ifEmpty { "Hermes" }
        val id = answerId(sessionId)

        val builder = NotificationCompat.Builder(context, CH_ANSWER)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openIntent(context, sessionId))
            .setGroup(GROUP_ANSWERS)

        if (channel == Channel.Kind.TELEGRAM) {
            // MessagingStyle folds the answer into a chat bubble with a sender name —
            // the shape a Telegram user reads without thinking.
            val person = Person.Builder().setName("Hermes · Telegram").build()
            builder.setStyle(
                NotificationCompat.MessagingStyle(person)
                    .setConversationTitle(title)
                    .addMessage(text, System.currentTimeMillis(), person)
            ).setContentText(compact(text))
        } else {
            builder.setContentText(compact(text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSubText(channelLabel(channel))
        }

        replyAction(context, sessionId, requestId = null)?.let { builder.addAction(it) }
        stopAction(context, stopIntent(context, sessionId))?.let { builder.addAction(it) }

        notify(context, id, builder.build())
        openAnswers.add(id)
        postSummary(context)
    }

    /**
     * A turn that failed while the screen was off.
     *
     * This was the worst silence in the app: nothing arrived, and from the phone a
     * crashed turn was indistinguishable from a slow one. The retry action makes the
     * card useful instead of merely honest.
     */
    fun showError(context: Context, sessionId: String?, sessionTitle: String, message: String) {
        NotificationManagerCompat.from(context).cancel(ID_STATUS)
        turnFinished()
        val text = message.ifBlank { "Ход не выполнился" }
        val title = sessionTitle.ifEmpty { "Hermes" }
        val builder = NotificationCompat.Builder(context, CH_ERROR)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(context.getString(R.string.notif_error_title, title))
            .setContentText(compact(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFFB42318.toInt())
            .setContentIntent(openIntent(context, sessionId))
        // «Повторить» pre-fills the reply field with the failed text when the app
        // knows it; an empty field still beats an app that has to be opened first.
        replyAction(context, sessionId, requestId = null, label = R.string.notif_retry)?.let {
            builder.addAction(it)
        }
        notify(context, ID_ERROR_BASE + Math.floorMod((sessionId ?: "").hashCode(), 1000), builder.build())
    }

    fun cancelWorking(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_STATUS)
        turnFinished()
    }

    /** The user is looking at this session: its answer card has no job left. */
    fun cancelAnswer(context: Context, sessionId: String?) {
        if (sessionId == null) return
        val id = answerId(sessionId)
        NotificationManagerCompat.from(context).cancel(id)
        openAnswers.remove(id)
        postSummary(context)
    }

    /**
     * The agent stopped and asks something: clarify or approval. High priority,
     * own channel, heads-up. Approval additionally gets «Разрешить»/«Отклонить»,
     * and a clarify question is answerable from the shade.
     */
    fun showRequest(
        context: Context,
        sessionId: String?,
        sessionTitle: String,
        request: ServerRequest
    ) {
        val id = ID_REQUEST_BASE + request.requestId.hashCode() % 1000
        val (title, text) = when (request) {
            is ServerRequest.Clarify ->
                context.getString(R.string.need_answer) to
                    (request.questions.firstOrNull()?.question ?: "").let {
                        if (it.length > 120) it.take(120) + "…" else it
                    }
            is ServerRequest.Approval ->
                request.title to request.description.let {
                    if (it.length > 120) it.take(120) + "…" else it
                }
        }
        val questionId = (request as? ServerRequest.Clarify)?.questions?.firstOrNull()?.qid
        val builder = NotificationCompat.Builder(context, CH_REQUEST)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(sessionTitle.ifEmpty { title })
            .setContentText(text.ifEmpty { title })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.ifEmpty { title }))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent(context, sessionId))
        if (request is ServerRequest.Approval) {
            builder.addAction(0, context.getString(R.string.approve_once_short),
                actionIntent(context, sessionId, request.requestId, "once"))
            builder.addAction(0, context.getString(R.string.deny),
                actionIntent(context, sessionId, request.requestId, "deny"))
        } else {
            // A clarify question wants a line of text: that is what inline reply is.
            replyAction(context, sessionId, request.requestId, questionId)?.let {
                builder.addAction(it)
            }
        }
        // The quiet "working" card is superseded: the run is paused on a question.
        NotificationManagerCompat.from(context).cancel(ID_STATUS)
        turnFinished()
        notify(context, id, builder.build())
    }

    fun cancelRequest(context: Context, notificationId: Int) =
        NotificationManagerCompat.from(context).cancel(notificationId)

    /**
     * One sample of each kind, for the «…» menu.
     *
     * The shape of a notification cannot be judged from the code that builds it, and
     * waiting for a real turn to see one is a bad way to review a design.
     */
    fun showSamples(context: Context) {
        ensureChannels(context)
        turnFinished()
        showWorking(context, "sample", "Пример: ход работы", "собираю ответ на вопрос", "deepseek-v4.1-flash")

        NotificationCompat.Builder(context, CH_ANSWER)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Пример: готовый ответ")
            .setContentText("Так выглядит ответ, когда ты не в приложении.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Так выглядит ответ, когда ты не в приложении. Кнопки «Ответить» и " +
                        "«Стоп» работают, не открывая чат."
                )
            )
            .setSubText("ПК")
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, null))
            .setGroup("hermes_samples")
            .apply { replyAction(context, "sample", null)?.let { addAction(it) } }
            .apply { stopAction(context, stopIntent(context, "sample"))?.let { addAction(it) } }
            .build()
            .let { notify(context, 9_001, it) }

        // Telegram styling is a different shape entirely, so it gets its own sample.
        val person = Person.Builder().setName("Hermes · Telegram").build()
        NotificationCompat.Builder(context, CH_ANSWER)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Пример: сеанс из Telegram")
            .setStyle(
                NotificationCompat.MessagingStyle(person)
                    .setConversationTitle("Пример: сеанс из Telegram")
                    .addMessage(
                        "Так выглядит ответ на сеанс, начатый в Telegram.",
                        System.currentTimeMillis(), person
                    )
            )
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, null))
            .setGroup("hermes_samples")
            .build()
            .let { notify(context, 9_002, it) }

        showError(context, null, "Пример: сбой", "Модель вернула 502 — ход не выполнился.")
        showRequest(
            context, null, "Пример: нужен ответ",
            ServerRequest.Approval(
                requestId = "sample-request",
                command = "rm -rf /tmp/build",
                description = "Разрешить команду? /tmp/build — каталог сборки",
                choices = listOf("once", "session", "deny")
            )
        )
    }

    // ── Building blocks ───────────────────────────────────────────────────────

    private fun answerId(sessionId: String?): Int = NotifText.answerId(sessionId)

    /** «N ответов» in the shade instead of N unrelated cards. */
    private fun postSummary(context: Context) {
        val count = openAnswers.size
        val nm = NotificationManagerCompat.from(context)
        if (count < 2) {
            // One answer needs no header: a summary would only add a card.
            nm.cancel(ID_SUMMARY)
            return
        }
        val summary = NotificationCompat.Builder(context, CH_ANSWER)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Hermes")
            .setContentText(
                context.resources.getQuantityString(R.plurals.notif_answers, count, count)
            )
            .setGroup(GROUP_ANSWERS)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, null))
            .build()
        notify(context, ID_SUMMARY, summary)
    }

    private fun channelLabel(channel: Channel.Kind): String? = when (channel) {
        Channel.Kind.TELEGRAM -> "Telegram"
        Channel.Kind.DESKTOP -> "ПК"
        Channel.Kind.CLI -> "CLI"
        Channel.Kind.BACKGROUND -> "Фон"
        Channel.Kind.API -> "API"
        Channel.Kind.NONE -> null
    }

    private fun compact(text: String): String = NotifText.compact(text)

    /**
     * Inline reply: the answer, or a clarify answer, typed straight into the shade.
     *
     * The PendingIntent must be MUTABLE — RemoteInput writes the typed text into the
     * intent it carries, and an IMMUTABLE one arrives empty, so the reply would
     * vanish with no error at all.
     */
    private fun replyAction(
        context: Context,
        sessionId: String?,
        requestId: String?,
        questionId: String? = null,
        label: Int = R.string.notif_reply
    ): NotificationCompat.Action? {
        if (sessionId == null && requestId == null) return null
        val remote = RemoteInput.Builder(KEY_REPLY)
            .setLabel(context.getString(label))
            .build()
        val intent = Intent(context, ReplyReceiver::class.java).apply {
            action = ReplyReceiver.ACTION_REPLY
            sessionId?.let { putExtra(MainActivity.EXTRA_SESSION_ID, it) }
            requestId?.let { putExtra(ReplyReceiver.EXTRA_REQUEST_ID, it) }
            questionId?.let { putExtra(ReplyReceiver.EXTRA_QUESTION_ID, it) }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(
            context,
            (sessionId ?: "").hashCode() * 31 + (requestId ?: "").hashCode(),
            intent,
            flags
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_send, context.getString(label), pi)
            .addRemoteInput(remote)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun stopAction(context: Context, intent: PendingIntent?): NotificationCompat.Action? {
        if (intent == null) return null
        return NotificationCompat.Action.Builder(0, context.getString(R.string.notif_stop), intent)
            .build()
    }

    private fun stopIntent(context: Context, sessionId: String?): PendingIntent? {
        if (sessionId == null) return null
        val intent = Intent(context, RequestActionReceiver::class.java).apply {
            action = RequestActionReceiver.ACTION_STOP
            putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, sessionId.hashCode(), intent, flags)
    }

    private fun actionIntent(
        context: Context,
        sessionId: String?,
        requestId: String,
        choice: String
    ): PendingIntent {
        val intent = Intent(context, RequestActionReceiver::class.java).apply {
            action = RequestActionReceiver.ACTION_ANSWER
            putExtra(RequestActionReceiver.EXTRA_REQUEST_ID, requestId)
            putExtra(RequestActionReceiver.EXTRA_CHOICE, choice)
            if (sessionId != null) putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, requestId.hashCode(), intent, flags)
    }

    private fun openIntent(context: Context, sessionId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId != null) putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
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
