package com.chester65r.chestervpncompanion

import android.content.Context
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Small, private app-local preferences store.
 * No account identifiers, SIM values, phone numbers, or network traffic are stored.
 */
object LocalAppStore {
    private const val PREFS_NAME = "chester_companion_local"
    private const val KEY_DARK_THEME = "dark_theme"
    private const val KEY_ACTIVITY_LOG = "activity_log"
    private const val MAX_EVENTS = 50

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDarkTheme(context: Context): Boolean =
        preferences(context).getBoolean(KEY_DARK_THEME, true)

    fun setDarkTheme(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_DARK_THEME, enabled).apply()
    }

    fun loadEvents(context: Context): List<String> =
        preferences(context).getString(KEY_ACTIVITY_LOG, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .take(MAX_EVENTS)
            .toList()

    fun addEvent(context: Context, event: String): List<String> {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val safeEvent = event.replace('\n', ' ').replace('\r', ' ').take(120)
        val updated = (listOf("$timestamp  $safeEvent") + loadEvents(context)).take(MAX_EVENTS)
        preferences(context).edit()
            .putString(KEY_ACTIVITY_LOG, updated.joinToString("\n"))
            .apply()
        return updated
    }

    fun clearEvents(context: Context) {
        preferences(context).edit().remove(KEY_ACTIVITY_LOG).apply()
    }
}
