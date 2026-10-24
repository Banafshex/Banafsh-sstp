package kittoku.osc.activity

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.ConfigurationCompat
import kittoku.osc.BuildConfig
import kittoku.osc.R
import kittoku.osc.ReleaseInfo
import kittoku.osc.UpdateChecker
import kittoku.osc.UpdateInstaller
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean


/** Checks GitHub for a newer release and downloads / installs it inside the app. */
internal object UpdateUi {
    const val KEY_DISMISSED = "update_dismissed_version"
    private const val KEY_LAST_CHECK = "update_last_check"
    private const val KEY_FOUND = "update_found"
    private const val DAY = 24L * 60 * 60 * 1000

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun language(activity: AppCompatActivity): String {
        return ConfigurationCompat.getLocales(activity.resources.configuration)[0]?.language ?: "en"
    }

    private fun loadFound(prefs: SharedPreferences): ReleaseInfo? {
        val text = prefs.getString(KEY_FOUND, null) ?: return null

        return try {
            val json = JSONObject(text)
            val info = ReleaseInfo(
                json.getString("v"),
                json.optString("n"),
                json.getString("u"),
                json.optLong("s"),
                json.optString("p")
            )

            if (UpdateChecker.isNewer(info.version, BuildConfig.VERSION_NAME)) info else null
        } catch (e: Exception) {
            null
        }
    }

    /** The newer release that the Home card should show (not the one the user already put off). */
    fun pendingCard(prefs: SharedPreferences): ReleaseInfo? {
        return loadFound(prefs)?.takeIf { prefs.getString(KEY_DISMISSED, "") != it.version }
    }

    fun checkIfDue(prefs: SharedPreferences, onChanged: () -> Unit) {
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) < DAY) return

        runCheck(prefs) { _, _ -> onChanged() }
    }

    private fun runCheck(prefs: SharedPreferences, onDone: (ReleaseInfo?, Boolean) -> Unit) {
        executor.execute {
            val result = UpdateChecker.fetchLatest()
            var newer: ReleaseInfo? = null

            if (result.ok) {
                val editor = prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                val info = result.info

                if (info != null && UpdateChecker.isNewer(info.version, BuildConfig.VERSION_NAME)) {
                    newer = info
                    editor.putString(
                        KEY_FOUND,
                        JSONObject()
                            .put("v", info.version)
                            .put("n", info.notes)
                            .put("u", info.apkUrl)
                            .put("s", info.apkSize)
                            .put("p", info.pageUrl)
                            .toString()
                    )
                } else {
                    editor.remove(KEY_FOUND)
                }

                editor.apply()
            }

            main.post { onDone(newer, result.ok) }
        }
    }

    /** Menu action: always checks right now and tells the user the result. */
    fun checkNow(activity: AppCompatActivity, prefs: SharedPreferences) {
        Toast.makeText(activity, R.string.bs_update_checking, Toast.LENGTH_SHORT).show()

        runCheck(prefs) { info, ok ->
            if (activity.isFinishing || activity.isDestroyed) return@runCheck

            when {
                info != null -> showUpdateDialog(activity, prefs, info) { }
                ok -> Toast.makeText(activity, R.string.bs_update_uptodate, Toast.LENGTH_LONG).show()
                else -> Toast.makeText(activity, R.string.bs_update_check_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun showUpdateDialog(activity: AppCompatActivity, prefs: SharedPreferences, info: ReleaseInfo, onLater: () -> Unit) {
        val notes = UpdateChecker.pickNotes(info.notes, language(activity)).take(1200)

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.bs_update_title, info.version))
            .setMessage(notes.ifEmpty { activity.getString(R.string.bs_update_no_notes) })
            .setPositiveButton(R.string.bs_update_now) { _, _ -> startDownload(activity, info) }
            .setNegativeButton(R.string.bs_update_later) { _, _ ->
                prefs.edit().putString(KEY_DISMISSED, info.version).apply()
                onLater()
            }
            .show()
    }

    private fun startDownload(activity: AppCompatActivity, info: ReleaseInfo) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            showInstallPermissionDialog(activity)
            return
        }

        val density = activity.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }
        val cancelled = AtomicBoolean(false)

        val label = TextView(activity).apply {
            text = activity.getString(R.string.bs_update_downloading, 0)
            textSize = 15f
        }

        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(8))
            addView(label)
            addView(
                bar,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(14) }
            )
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.bs_update_title, info.version))
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(R.string.bs_cancel) { _, _ -> cancelled.set(true) }
            .show()

        executor.execute {
            var lastPercent = -1

            val file = UpdateChecker.download(
                activity.applicationContext,
                info,
                { done, total ->
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0

                    if (percent != lastPercent) {
                        lastPercent = percent
                        main.post {
                            bar.progress = percent
                            label.text = activity.getString(R.string.bs_update_downloading, percent)
                        }
                    }
                },
                { cancelled.get() }
            )

            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post

                if (cancelled.get()) {
                    dialog.dismiss()
                    return@post
                }

                if (file == null) {
                    dialog.dismiss()
                    showDownloadFailed(activity)
                    return@post
                }

                label.setText(R.string.bs_update_installing)
                bar.isIndeterminate = true

                if (UpdateInstaller.install(activity.applicationContext, file)) {
                    // Android may show its own confirmation on top; this dialog is no longer needed.
                    main.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, 2500)
                } else {
                    dialog.dismiss()
                    Toast.makeText(activity, R.string.bs_update_install_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showInstallPermissionDialog(activity: AppCompatActivity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.bs_update_perm_title)
            .setMessage(R.string.bs_update_perm_body)
            .setPositiveButton(R.string.bs_update_perm_continue) { _, _ ->
                if (Build.VERSION.SDK_INT >= 26) {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                }
            }
            .setNegativeButton(R.string.bs_cancel, null)
            .show()
    }

    private fun showDownloadFailed(activity: AppCompatActivity) {
        AlertDialog.Builder(activity)
            .setMessage(R.string.bs_update_failed)
            .setPositiveButton(R.string.bs_update_open_page) { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_PAGE)))
                } catch (_: Exception) { }
            }
            .setNegativeButton(R.string.bs_cancel, null)
            .show()
    }
}
