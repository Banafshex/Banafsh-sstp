package kittoku.osc

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager


class BanafshApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // The app has one look only: dark.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        // Persian is the default language until the user picks another one.
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        if (!prefs.getBoolean("language_default_set", false)) {
            if (AppCompatDelegate.getApplicationLocales().isEmpty) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fa"))
            }

            prefs.edit().putBoolean("language_default_set", true).apply()
        }
    }
}
