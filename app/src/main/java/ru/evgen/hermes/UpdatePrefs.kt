package ru.evgen.hermes

import android.content.Context

/**
 * Whether the app may look for a newer build of itself.
 *
 * Off by default, deliberately. A client that downloads and installs executables on
 * its own is a self-updater, and app repositories treat one that is silently active
 * as a policy violation: the APK it pulls comes from the user's own server, not from
 * the repository, so nobody screened that build. Making it a switch, off until asked,
 * is the difference between an updater and a surprise.
 *
 * The Shizuku path installs without a system prompt, so it stays behind the same
 * switch: no consent, no download.
 */
object UpdatePrefs {

    private const val PREFS = "HermesUpdates"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, on).apply()
    }
}
