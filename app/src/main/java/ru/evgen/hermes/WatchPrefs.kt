package ru.evgen.hermes

import android.content.Context

/**
 * Whether the background watch may run.
 *
 * Off by default, deliberately. The watch is the only part of the app that starts a
 * foreground service, and a foreground service that misbehaves takes the whole
 * process down with it — which is exactly what happened: the app began crashing after
 * the watch was introduced, with no trace to read. Making it a switch means a user who
 * never asked for background notifications cannot be hurt by it, and the switch is
 * also the fastest way to prove the watch is or is not the cause.
 */
object WatchPrefs {

    private const val PREFS = "HermesWatch"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, on).apply()
        if (!on) WatchService.stop(context)
    }
}
