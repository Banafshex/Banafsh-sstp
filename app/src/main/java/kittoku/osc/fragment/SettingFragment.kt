package kittoku.osc.fragment

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kittoku.osc.R
import kittoku.osc.activity.AppPickerActivity
import kittoku.osc.activity.showAboutDialog
import kittoku.osc.activity.showLogDialog
import kittoku.osc.preference.LIST_TYPE_ALLOWED
import kittoku.osc.preference.LIST_TYPE_DISALLOWED
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getSetPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setURIPrefValue
import kittoku.osc.preference.custom.DirectoryPreference

internal class SettingFragment : PreferenceFragmentCompat() {
    private lateinit var prefs: SharedPreferences
    private lateinit var certDirPref: DirectoryPreference
    private lateinit var logDirPref: DirectoryPreference
    private lateinit var selectAppsPref: Preference

    private val certDirLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data?.also {
            requireContext().contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } else null

        setURIPrefValue(uri, OscPrefKey.SSL_CERT_DIR, prefs)
        certDirPref.updateView()
    }

    private val logDirLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data?.also {
            requireContext().contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } else null

        setURIPrefValue(uri, OscPrefKey.LOG_DIR, prefs)
        logDirPref.updateView()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)

        prefs = preferenceManager.sharedPreferences!!

        certDirPref = findPreference(OscPrefKey.SSL_CERT_DIR.name)!!
        logDirPref = findPreference(OscPrefKey.LOG_DIR.name)!!
        selectAppsPref = findPreference(OscPrefKey.ROUTE_SELECTED_APPS.name)!!

        setCertDirListener()
        setLogDirListener()
        setSplitTunneling()
        setAboutListener()
        setTelegramListener()
        setLogListener()
        setLanguageListener()
        setAdvancedToggle()
    }

    private fun setLanguageListener() {
        val pref = findPreference<ListPreference>("LANGUAGE") ?: return
        val saved = preferenceManager.sharedPreferences?.getString("app_language", "fa") ?: "fa"
        pref.value = if (saved == "en") "en" else "fa"

        pref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            val tag = if ((newValue as? String) == "en") "en" else "fa"
            preferenceManager.sharedPreferences?.edit()?.putString("app_language", tag)?.apply()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))

            true
        }
    }

    private val advancedKeys = listOf(
        "ADV_SSL", "ADV_PPP", "ADV_PPP_AUTH", "ADV_PPP_NET", "ADV_DNS", "ADV_ROUTING", "ADV_LOG"
    )

    private fun setAdvancedToggle() {
        val toggle = findPreference<Preference>("ADVANCED_TOGGLE") ?: return
        var expanded = false

        fun apply() {
            advancedKeys.forEach { findPreference<Preference>(it)?.isVisible = expanded }
            toggle.setIcon(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        }

        apply()

        toggle.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            expanded = !expanded
            apply()

            true
        }
    }

    private fun setCertDirListener() {
        certDirPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).also {
                it.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                try {
                    certDirLauncher.launch(it)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(requireContext(), R.string.tv_no_file_picker, Toast.LENGTH_LONG).show()
                }
            }

            true
        }
    }

    private fun setLogDirListener() {
        logDirPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).also {
                it.flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    logDirLauncher.launch(it)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(requireContext(), R.string.tv_no_file_picker, Toast.LENGTH_LONG).show()
                }
            }

            true
        }
    }

    private fun setSplitTunneling() {
        findPreference<ListPreference>(OscPrefKey.ROUTE_APP_LIST_TYPE.name)!!.apply {
            entryValues = arrayOf(LIST_TYPE_DISALLOWED, LIST_TYPE_ALLOWED)
            value = getStringPrefValue(OscPrefKey.ROUTE_APP_LIST_TYPE, prefs)
        }
        selectAppsPref.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), AppPickerActivity::class.java))
            true
        }
    }

    override fun onResume() {
        super.onResume()
        val count = getSetPrefValue(OscPrefKey.ROUTE_SELECTED_APPS, prefs).size
        selectAppsPref.summary = getString(R.string.tv_apps_count, count)
    }

    private fun setAboutListener() {
        findPreference<Preference>("ABOUT_BANAFSH")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                (activity as? AppCompatActivity)?.let { showAboutDialog(it) }

                true
            }
    }

    private fun setTelegramListener() {
        findPreference<Preference>("TELEGRAM_CHANNEL")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Banafsh_sstp")))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(requireContext(), R.string.tv_no_handler, Toast.LENGTH_LONG).show()
                }

                true
            }
    }

    private fun setLogListener() {
        findPreference<Preference>("CONNECTION_LOG")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                (activity as? AppCompatActivity)?.let { showLogDialog(it) }

                true
            }
    }
}
