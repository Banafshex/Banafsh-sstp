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

        // The saved choice is applied at every start, so it does not depend on the phone's language.
        val language = if (prefs.getString("app_language", "fa") == "en") "en" else "fa"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
    }
}
