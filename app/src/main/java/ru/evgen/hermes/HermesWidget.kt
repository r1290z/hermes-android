package ru.evgen.hermes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * A 4×1 window on the home screen: is anything running, what came last, how much is
 * unread — and one tap into the app.
 *
 * It deliberately does no networking. A widget that polls on its own wakes the radio
 * while the phone is in a pocket, and Android would throttle it anyway; instead the
 * app writes what it already knows into SharedPreferences and asks the widget to
 * redraw. A widget is a mirror of the last known state, not a second client.
 */
class HermesWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray
    ) {
        for (id in ids) manager.updateAppWidget(id, views(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refresh(context)
    }

    companion object {
        const val ACTION_REFRESH = "ru.evgen.hermes.WIDGET_REFRESH"

        /** Redraws every placed widget. Cheap and safe to call often. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, HermesWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) manager.updateAppWidget(id, views(context))
        }

        /**
         * What the widget shows. Kept in prefs so the widget has an answer even when
         * the app process is gone — an empty widget on a cold boot looks broken.
         */
        fun save(context: Context, running: Int, unread: Int, model: String, snippet: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_RUNNING, running)
                .putInt(KEY_UNREAD, unread)
                .putString(KEY_MODEL, model)
                .putString(KEY_SNIPPET, snippet)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
            refresh(context)
        }

        private fun views(context: Context): RemoteViews {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val running = p.getInt(KEY_RUNNING, 0)
            val unread = p.getInt(KEY_UNREAD, 0)
            val model = p.getString(KEY_MODEL, "").orEmpty()
            val snippet = p.getString(KEY_SNIPPET, "").orEmpty()
            val at = p.getLong(KEY_AT, 0L)

            val views = RemoteViews(context.packageName, R.layout.widget_hermes)
            val headline = when {
                running > 0 -> context.getString(R.string.widget_running, running)
                unread > 0 -> context.getString(R.string.widget_unread, unread)
                else -> context.getString(R.string.widget_idle)
            }
            views.setTextViewText(R.id.widgetTitle, headline)
            // Second line: the last answer if there is one, else the model — the two
            // facts worth a glance, in that order of urgency.
            val second = when {
                snippet.isNotBlank() -> snippet
                model.isNotBlank() -> model
                at > 0 -> context.getString(R.string.widget_stale, clock(at))
                else -> context.getString(R.string.widget_hint)
            }
            views.setTextViewText(R.id.widgetBody, second)
            views.setViewVisibility(
                R.id.widgetDot, if (running > 0) android.view.View.VISIBLE else android.view.View.GONE
            )

            views.setOnClickPendingIntent(R.id.widgetRoot, open(context, null))
            views.setOnClickPendingIntent(R.id.widgetNew, open(context, NEW_SESSION))
            return views
        }

        private const val NEW_SESSION = "ru.evgen.hermes.WIDGET_NEW"

        private fun open(context: Context, what: String?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (what == NEW_SESSION) putExtra(MainActivity.EXTRA_NEW_SESSION, true)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                    PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getActivity(context, (what ?: "open").hashCode(), intent, flags)
        }

        private fun clock(at: Long): String {
            val min = (System.currentTimeMillis() - at) / 60_000
            return when {
                min < 1 -> "только что"
                min < 60 -> "$min мин назад"
                min < 1440 -> "${min / 60} ч назад"
                else -> "${min / 1440} дн назад"
            }
        }

        private const val PREFS = "HermesWidget"
        private const val KEY_RUNNING = "running"
        private const val KEY_UNREAD = "unread"
        private const val KEY_MODEL = "model"
        private const val KEY_SNIPPET = "snippet"
        private const val KEY_AT = "at"
    }
}
