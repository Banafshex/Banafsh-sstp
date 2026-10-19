package kittoku.osc.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.preference.PreferenceManager
import kittoku.osc.BuildConfig
import kittoku.osc.R
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getStringPrefValue

internal const val KEY_HIDE_WELCOME = "hide_welcome"
internal const val KEY_LAST_SEEN_VERSION = "last_seen_version"

internal fun showWelcomeDialog(activity: AppCompatActivity) {
    showInfoDialog(activity, activity.getString(R.string.bs_welcome_title), R.string.bs_features_list, withIntro = true, withCheckbox = true)
}

internal fun showAboutDialog(activity: AppCompatActivity) {
    showInfoDialog(activity, activity.getString(R.string.bs_about_title), R.string.bs_features_list, withIntro = true, withCheckbox = false)
}

/** Shown once after an update: only the list of what is new and what the app can do. */
internal fun showWhatsNewDialog(activity: AppCompatActivity) {
    showInfoDialog(
        activity,
        activity.getString(R.string.bs_whats_new_title, BuildConfig.VERSION_NAME),
        R.string.bs_whats_new_list,
        withIntro = false,
        withCheckbox = false
    )
}

private fun showInfoDialog(activity: AppCompatActivity, title: String, listRes: Int, withIntro: Boolean, withCheckbox: Boolean) {
    val density = activity.resources.displayMetrics.density
    val dp = { value: Int -> (value * density).toInt() }

    val intro = TextView(activity).apply {
        text = activity.getString(R.string.bs_welcome_message)
        textSize = 15f
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setLineSpacing(0f, 1.2f)
        Linkify.addLinks(this, Linkify.WEB_URLS)
    }

    val featuresTitle = TextView(activity).apply {
        text = activity.getString(R.string.bs_features_title)
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    val features = TextView(activity).apply {
        text = activity.getString(listRes)
        textSize = 15f
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setLineSpacing(0f, 1.2f)
    }

    val checkBox = AppCompatCheckBox(activity).apply {
        text = activity.getString(R.string.bs_dont_show_again)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    val container = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(8))

        if (withIntro) {
            addView(intro)
            addView(
                featuresTitle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(20)
                    bottomMargin = dp(8)
                }
            )
        }

        addView(features)

        if (withCheckbox) {
            addView(
                checkBox,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
            )
        }
    }

    AlertDialog.Builder(activity).also {
        it.setTitle(title)
        it.setView(ScrollView(activity).apply { addView(container) })
        it.setCancelable(!withCheckbox)
        it.setPositiveButton(R.string.bs_close) { _, _ ->
            if (withCheckbox && checkBox.isChecked) {
                activity.getSharedPreferences("banafsh_ui", Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_HIDE_WELCOME, true).apply()
            }
        }
        it.show()
    }
}

internal fun showLogDialog(activity: AppCompatActivity) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
    val text = getStringPrefValue(OscPrefKey.HOME_STATUS, prefs).ifEmpty { activity.getString(R.string.bs_log_empty) }
    val density = activity.resources.displayMetrics.density

    val body = TextView(activity).apply {
        this.text = text
        textSize = 13f
        setTextIsSelectable(true)
        setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
    }

    AlertDialog.Builder(activity)
        .setTitle(R.string.bs_log_title)
        .setView(ScrollView(activity).apply { addView(body) })
        .setPositiveButton(R.string.bs_copy) { _, _ ->
            val manager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("Banafsh-sstp log", text))
            Toast.makeText(activity, R.string.bs_copied, Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton(R.string.bs_close, null)
        .show()
}
