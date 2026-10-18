package kittoku.osc

import android.content.Context
import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL

internal object IranApps {
    const val KEY_LIST = "iran_bypass_list"
    const val KEY_ENABLED = "iran_bypass_enabled"

    private const val REMOTE_URL = "https://raw.githubusercontent.com/Banafshex/Banafsh-sstp/main/iran_apps.txt"
    private const val MONTH_MS = 30L * 24 * 60 * 60 * 1000

    fun parse(text: String): Set<String> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() && !line.startsWith("#") && line.contains('.') &&
                        line.all { it.isLetterOrDigit() || it == '.' || it == '_' }
            }
            .toSet()
    }

    // The built-in list is used until a newer one has been downloaded.
    fun ensureSeeded(context: Context, prefs: SharedPreferences) {
        if (prefs.getStringSet(KEY_LIST, null).isNullOrEmpty()) {
            val text = context.resources.openRawResource(R.raw.iran_apps).bufferedReader(Charsets.UTF_8).use { it.readText() }
            prefs.edit().putStringSet(KEY_LIST, HashSet(parse(text))).apply()
        }
    }

    // About once a month the list is refreshed from the repository.
    fun refreshIfDue(context: Context, prefs: SharedPreferences) {
        val ui = context.getSharedPreferences("banafsh_ui", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - ui.getLong("iran_list_checked", 0L) < MONTH_MS) {
            return
        }

        Thread {
            try {
                val connection = URL(REMOTE_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                try {
                    val parsed = parse(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })

                    if (parsed.size >= 20) {
                        prefs.edit().putStringSet(KEY_LIST, HashSet(parsed)).apply()
                        ui.edit().putLong("iran_list_checked", System.currentTimeMillis()).apply()
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                // offline or file missing: keep the current list
            }
        }.start()
    }
}
