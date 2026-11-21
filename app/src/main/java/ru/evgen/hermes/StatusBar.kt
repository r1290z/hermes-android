package ru.evgen.hermes

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The one-line state strip under the session list.
 *
 * Four facts, each measured rather than guessed:
 *  - **модель** — `/api/model/info` (the model the gateway would run a turn with);
 *  - **контекст** — the newest session's `input_tokens` against that model's
 *    context window, because that is what a new message will actually carry;
 *  - **шлюз** — a live WebSocket to `/api/ws` (ticket → connect), which is the
 *    only honest test of "can this phone start a turn";
 *  - **контейнер** — `/api/health` answering at all, i.e. nginx → container → process.
 *
 * Tap opens the protocol log ([Diag]); without it a phone that "just doesn't get
 * replies" is undebuggable, since the app cannot be run anywhere but the phone.
 */
class StatusBar(
    private val activity: Activity,
    private val view: TextView,
    private val api: ApiClient
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null

    private var model: String = ""
    private var provider: String = ""
    private var window: Long = 0
    private var used: Long = 0
    private var gateway: String = ""      // "" = unknown, "ok", "нет"
    private var gatewayVersion: String = ""
    private var containerOk: Boolean? = null
    private var lastSession: String = ""
    private var lastSessionCount: Int = 0

    fun start() {
        if (loop?.isActive == true) return
        api.warmSocket()
        loop = scope.launch {
            while (true) {
                refresh()
                delay(20_000)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    fun destroy() = scope.cancel()

    private suspend fun refresh() {
        val (health, info, sessions) = withContext(Dispatchers.IO) {
            Triple(api.health(), api.modelInfo(), api.sessions(limit = 1))
        }

        containerOk = health?.optBoolean("ok") == true
        gatewayVersion = health?.optString("version").orEmpty()

        if (info != null) {
            model = info.optString("model").ifEmpty { model }
            provider = info.optString("provider").ifEmpty { provider }
            window = info.optLong("effective_context_length", window)
        }
        val newest = sessions.firstOrNull()
        // A model switch inside the chat is per-session; the newest session row
        // is the honest source for what THAT session now runs — /api/model/info
        // only knows the profile default.
        if (!newest?.model.isNullOrEmpty()) model = newest!!.model
        if (newest != null) {
            used = newest.inputTokens
            if (model.isEmpty()) model = newest.model.orEmpty()
            lastSession = newest.title
            lastSessionCount = newest.messageCount
        }

        gateway = when {
            api.isSocketConnected -> "ok"
            else -> "нет"
        }
        view.text = line()
    }

    private fun line(): String {
        val name = model.ifEmpty { "модель ?" }
        val ctx = if (window > 0) {
            "ctx " + short(used) + "/" + short(window) + " " + (used * 100 / window) + "%"
        } else {
            "ctx " + short(used)
        }
        val gw = "шлюз " + (if (gateway == "ok") "ok" else gateway.ifEmpty { "?" })
        val box = "контейнер " + when (containerOk) {
            true -> "ok"
            false -> "нет"
            null -> "?"
        }
        return "$name · $ctx · $gw · $box"
    }

    /** 28463 → «28.5k», 262144 → «262k». Small on purpose: the strip is 11sp. */
    private fun short(value: Long): String = when {
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format("%.1fk", value / 1_000.0)
        else -> value.toString()
    }

    fun showDetails() {
        val body = buildString {
            appendLine("Модель: " + model.ifEmpty { "—" } + (if (provider.isEmpty()) "" else " ($provider)"))
            appendLine(
                if (window > 0) "Контекст: " + short(used) + " из " + short(window) +
                    " — " + (used * 100 / window) + "%"
                else "Контекст: " + short(used)
            )
            appendLine(
                "Контейнер: " + when (containerOk) {
                    true -> "отвечает"
                    false -> "не отвечает"
                    null -> "неизвестно"
                }
            )
            appendLine("Шлюз: " + (if (gateway == "ok") "сокет подключён" else "сокета нет") +
                (if (gatewayVersion.isEmpty()) "" else ", версия $gatewayVersion"))
            if (lastSession.isNotEmpty()) {
                appendLine("Последний сеанс: $lastSession ($lastSessionCount сообщ.)")
            }
            appendLine()
            appendLine("— протокол —")
            append(Diag.tail(30).ifEmpty { "пусто" })
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Состояние")
            .setMessage(body)
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Копировать") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Hermes", body))
                Toast.makeText(activity, "Скопировано", Toast.LENGTH_SHORT).show()
            }
            .create()
        dialog.show()
    }
}
