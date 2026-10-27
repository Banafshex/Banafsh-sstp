package kittoku.osc.activity

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.util.Linkify
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.forEach
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import kittoku.osc.BuildConfig
import kittoku.osc.R
import kittoku.osc.databinding.ActivityMainBinding
import kittoku.osc.IranApps
import kittoku.osc.IranRanges
import kittoku.osc.NewsChecker
import kittoku.osc.NewsNotifier
import kittoku.osc.SharedServer
import kittoku.osc.ThemeMode
import kittoku.osc.flagEmoji
import kittoku.osc.extension.firstEditText
import kittoku.osc.extension.sum
import kittoku.osc.fragment.HomeFragment
import kittoku.osc.fragment.SettingFragment
import kittoku.osc.preference.LIST_TYPE_DISALLOWED
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.PROFILE_KEY_HEADER
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setSetPrefValue
import kittoku.osc.preference.custom.OscPreference
import kittoku.osc.preference.deserializeProfile
import kittoku.osc.preference.importProfile
import kittoku.osc.preference.serializeProfile
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.SstpVpnService
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private const val KEY_RECONNECT_DEFAULT_V1 = "reconnect_default_v1"

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences

    private val uiPrefs: SharedPreferences by lazy {
        getSharedPreferences("banafsh_ui", MODE_PRIVATE)
    }

    private val dialogResource: Int by lazy { EditTextPreference(this).dialogLayoutResource }

    private val profileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            return@registerForActivityResult
        }

        updatePreferenceView()
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.also {
            val text = try {
                contentResolver.openInputStream(it)?.use { stream ->
                    stream.reader(Charsets.UTF_8).readText()
                }
            } catch (e: Exception) {
                null
            }

            if (text == null) {
                Toast.makeText(this, R.string.bs_file_read_failed, Toast.LENGTH_SHORT).show()
            } else {
                showImportConfirm(text)
            }
        }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.also {
            contentResolver.openOutputStream(it)?.also { stream ->
                BufferedOutputStream(stream).use {
                    it.write(serializeProfile(prefs).toByteArray(Charsets.UTF_8))
                }
            }

            Toast.makeText(this, R.string.bs_profile_exported, Toast.LENGTH_SHORT).show()
        }
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data

        if (result.resultCode != RESULT_OK || data == null) {
            return@registerForActivityResult
        }

        val server = SharedServer(
            host = data.getStringExtra(QrScanActivity.EXTRA_HOST).orEmpty(),
            port = data.getIntExtra(QrScanActivity.EXTRA_PORT, 443),
            user = data.getStringExtra(QrScanActivity.EXTRA_USER).orEmpty(),
            pass = data.getStringExtra(QrScanActivity.EXTRA_PASS).orEmpty(),
            countryCode = data.getStringExtra(QrScanActivity.EXTRA_COUNTRY).orEmpty(),
            verify = data.getBooleanExtra(QrScanActivity.EXTRA_VERIFY, true)
        )

        if (server.host.isNotEmpty()) {
            showReceivedServerConfirm(server)
        }
    }

    private fun updatePreferenceView() {
        // Home refreshes itself from the preferences; Settings reads them when it is opened.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeMode.apply(PreferenceManager.getDefaultSharedPreferences(this).getString("THEME_MODE", "system"))
        setTheme(R.style.Theme_OpenSSTPClient_NoActionBar)
        super.onCreate(savedInstanceState)

        title = getString(R.string.bs_title)

        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        // The picture runs under the status bar; its icons must stay readable in light and dark mode.
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = !isNight
        ViewCompat.setOnApplyWindowInsetsListener(binding.container) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        IranApps.ensureSeeded(this, prefs)
        IranApps.refreshIfDue(this, prefs)
        IranRanges.ensureSeeded(this)
        IranRanges.refreshIfDue(this)

        // The old "only these apps use the VPN" mode is gone. Keep earlier picks only if they meant
        // "these apps connect directly"; otherwise clear them so they do not change meaning.
        if (!uiPrefs.getBoolean("apps_rule_migrated_v2", false)) {
            val keep = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ENABLE_APP_BASED_RULE, prefs) &&
                    getStringPrefValue(OscPrefKey.ROUTE_APP_LIST_TYPE, prefs) == LIST_TYPE_DISALLOWED

            if (!keep) setSetPrefValue(emptySet(), OscPrefKey.ROUTE_SELECTED_APPS, prefs)
            setBooleanPrefValue(false, OscPrefKey.ROUTE_DO_ENABLE_APP_BASED_RULE, prefs)
            uiPrefs.edit().putBoolean("apps_rule_migrated_v2", true).apply()
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.container, HomeFragment()).commit()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        // Reconnection is now on by default; switch it on once for installs that saved the old default.
        if (!uiPrefs.getBoolean(KEY_RECONNECT_DEFAULT_V1, false)) {
            setBooleanPrefValue(true, OscPrefKey.RECONNECTION_ENABLED, prefs)
            uiPrefs.edit().putBoolean(KEY_RECONNECT_DEFAULT_V1, true).apply()
        }

        if (savedInstanceState == null) {
            val lastSeen = uiPrefs.getString(KEY_LAST_SEEN_VERSION, null)

            var infoShown = true

            if (!uiPrefs.getBoolean(KEY_HIDE_WELCOME, false)) {
                showWelcomeDialog(this)
            } else if (lastSeen != BuildConfig.VERSION_NAME) {
                showWhatsNewDialog(this)
            } else {
                infoShown = false
            }

            uiPrefs.edit().putString(KEY_LAST_SEEN_VERSION, BuildConfig.VERSION_NAME).apply()

            // News and update pop-ups (also opened from their notifications).
            if (!infoShown) {
                Popups.showPending(this, prefs)
            }

            NewsChecker.fetchAsync(prefs, UpdateUi.language(this)) { Popups.showPending(this, prefs) }
            UpdateUi.checkIfDue(prefs) { Popups.showPending(this, prefs) }
        }

        NewsNotifier.ensureChannel(this)
        NewsNotifier.schedule(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Popups.showPending(this, prefs)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        MenuInflater(this).inflate(R.menu.home_menu, menu)

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.open_settings -> startActivity(
                Intent(this, BlankActivity::class.java).putExtra(EXTRA_KEY_TYPE, BLANK_ACTIVITY_TYPE_SETTINGS)
            )

            R.id.check_update -> UpdateUi.checkNow(this, prefs)

            R.id.send_server -> startActivity(Intent(this, QrSendActivity::class.java))

            R.id.receive_server -> scanLauncher.launch(Intent(this, QrScanActivity::class.java))

            R.id.load_profile -> {
                profileLauncher.launch(Intent(this, BlankActivity::class.java).putExtra(
                    EXTRA_KEY_TYPE,
                    BLANK_ACTIVITY_TYPE_PROFILES
                ))
            }

            R.id.save_profile -> showSaveDialog()
            R.id.import_profile -> importLauncher.launch(arrayOf("*/*"))
            R.id.export_profile -> showExportDialog()
            R.id.reload_defaults -> showReloadDialog()
        }

        return true
    }

    private fun showSaveDialog() {
        val inflated = layoutInflater.inflate(dialogResource, null)
        val editText = inflated.firstEditText()
        val hostname = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)

        editText.inputType = InputType.TYPE_CLASS_TEXT
        editText.hint = hostname
        editText.requestFocus()

        AlertDialog.Builder(this).also {
            it.setView(inflated)
            it.setMessage(R.string.bs_save_msg)
            it.setPositiveButton(R.string.bs_save) { _, _ ->
                prefs.edit().also { editor ->
                    editor.putString(
                        PROFILE_KEY_HEADER + editText.text.ifEmpty { hostname },
                        serializeProfile(prefs)
                    )

                    editor.apply()
                }

                Toast.makeText(this, R.string.bs_profile_saved, Toast.LENGTH_SHORT).show()
            }
            it.setNegativeButton(R.string.bs_cancel) { _, _ -> }
            it.show()
        }
    }

    private fun showExportDialog() {
        val filename = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs) + ".json"

        AlertDialog.Builder(this).also {
            it.setMessage(R.string.bs_export_msg)
            it.setPositiveButton(R.string.bs_proceed) { _, _ ->
                exportLauncher.launch(filename)
            }
            it.setNegativeButton(R.string.bs_cancel) { _, _ -> }
            it.show()
        }
    }

    private fun showReloadDialog() {
        AlertDialog.Builder(this).also {
            it.setMessage(R.string.bs_reload_msg)
            it.setPositiveButton(R.string.bs_yes) { _, _ ->
                importProfile(null, prefs)
                updatePreferenceView()

                Toast.makeText(this, R.string.bs_defaults_reloaded, Toast.LENGTH_SHORT).show()
            }
            it.setNegativeButton(R.string.bs_no) { _, _ -> }
            it.show()
        }
    }

    // ---- Import with confirmation ----------------------------------------

    private fun showImportConfirm(jsonText: String) {
        val profile = deserializeProfile(jsonText)

        var host = ""
        var port = 443
        var user = ""
        var pass = ""

        try {
            val json = JSONObject(jsonText)
            val strings = json.optJSONObject("stringSetting")
            val ints = json.optJSONObject("intSetting")

            host = strings?.optString("HOME_HOSTNAME").orEmpty()
            user = strings?.optString("HOME_USERNAME").orEmpty()
            pass = strings?.optString("HOME_PASSWORD").orEmpty()
            port = ints?.optInt("SSL_PORT", 443) ?: 443
        } catch (e: Exception) {
            host = ""
        }

        if (profile == null || host.isEmpty()) {
            Toast.makeText(this, R.string.bs_config_invalid, Toast.LENGTH_LONG).show()
            return
        }

        val message = getString(
            R.string.bs_config_msg,
            host,
            port,
            user.ifEmpty { getString(R.string.bs_empty) },
            pass.ifEmpty { getString(R.string.bs_empty) }
        )

        AlertDialog.Builder(this).also {
            it.setTitle(R.string.bs_config_title)
            it.setMessage(message)
            it.setPositiveButton(R.string.bs_config_use) { _, _ ->
                importProfile(profile, prefs)
                updatePreferenceView()

                Toast.makeText(this, R.string.bs_config_saved, Toast.LENGTH_LONG).show()
            }
            it.setNegativeButton(R.string.bs_cancel) { _, _ -> }
            it.show()
        }
    }

    // ---- Receive a server by QR --------------------------------------------

    private fun showReceivedServerConfirm(server: SharedServer) {
        val label = "${flagEmoji(server.countryCode)} ${server.host}:${server.port}".trim()

        AlertDialog.Builder(this)
            .setTitle(R.string.bs_qr_confirm_title)
            .setMessage(getString(R.string.bs_qr_confirm_msg, label))
            .setPositiveButton(R.string.bs_qr_confirm_save) { _, _ -> applyReceivedServer(server) }
            .setNegativeButton(R.string.bs_cancel, null)
            .show()
    }

    private fun applyReceivedServer(server: SharedServer) {
        if (!applyServerSettings(server.host, server.port, server.user, server.pass, !server.verify)) {
            Toast.makeText(this, R.string.bs_server_set_failed, Toast.LENGTH_SHORT).show()
            return
        }

        uiPrefs.edit()
            .putString("server_host", server.host)
            .putString("server_country", server.countryCode)
            .apply()
        updatePreferenceView()

        AlertDialog.Builder(this)
            .setMessage(R.string.bs_qr_connect_msg)
            .setPositiveButton(R.string.bs_yes) { _, _ ->
                if (getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)) {
                    // Drop the current connection first so that the new server is really used.
                    disconnectNow()
                    window.decorView.postDelayed({ connectNow() }, 1800)
                } else {
                    connectNow()
                }
            }
            .setNegativeButton(R.string.bs_no, null)
            .show()
    }

    // ---- Get Free Config -------------------------------------------------

    private val freeServerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data

        if (result.resultCode != RESULT_OK || data == null) {
            return@registerForActivityResult
        }

        val host = data.getStringExtra(ServerListActivity.EXTRA_HOST).orEmpty()
        val port = data.getIntExtra(ServerListActivity.EXTRA_PORT, 443)
        val countryCode = data.getStringExtra(ServerListActivity.EXTRA_COUNTRY_CODE).orEmpty()

        if (host.isNotEmpty()) {
            applyFreeServer(FreeServer(host, port, "", countryCode, 0.0, 0))
        }
    }

    internal fun showFreeConfig() {
        freeServerLauncher.launch(Intent(this, ServerListActivity::class.java))
    }

    private fun JSONObject.section(name: String): JSONObject {
        return optJSONObject(name) ?: JSONObject().also { put(name, it) }
    }

    private fun applyServerSettings(host: String, port: Int, user: String, pass: String, disableVerify: Boolean): Boolean {
        val profile = try {
            val json = JSONObject(serializeProfile(prefs))

            json.section("stringSetting").also {
                it.put("HOME_HOSTNAME", host)
                it.put("HOME_USERNAME", user)
                it.put("HOME_PASSWORD", pass)
            }
            json.section("intSetting").put("SSL_PORT", port)
            if (disableVerify) {
                json.section("booleanSetting").put("SSL_DO_VERIFY", false)
            }

            deserializeProfile(json.toString())
        } catch (e: Exception) {
            null
        }

        if (profile == null) {
            return false
        }

        importProfile(profile, prefs)
        return true
    }

    private fun applyFreeServer(server: FreeServer) {
        if (!applyServerSettings(server.host, server.port, "vpn", "vpn", true)) {
            Toast.makeText(this, R.string.bs_server_set_failed, Toast.LENGTH_SHORT).show()
            return
        }

        uiPrefs.edit()
            .putString("server_host", server.host)
            .putString("server_country", server.countryCode)
            .apply()
        updatePreferenceView()

        Toast.makeText(this, getString(R.string.bs_server_set_connecting, server.host), Toast.LENGTH_LONG).show()
        connectNow()
    }

    // ---- Server dialog ---------------------------------------------------

    internal fun showServerDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_server, null)
        val etHost = view.findViewById<EditText>(R.id.etHost)
        val etPort = view.findViewById<EditText>(R.id.etPort)
        val etUser = view.findViewById<EditText>(R.id.etUser)
        val etPass = view.findViewById<EditText>(R.id.etPass)

        etHost.setText(getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs))
        etPort.setText(getIntPrefValue(OscPrefKey.SSL_PORT, prefs).toString())
        etUser.setText(getStringPrefValue(OscPrefKey.HOME_USERNAME, prefs))
        etPass.setText(getStringPrefValue(OscPrefKey.HOME_PASSWORD, prefs))

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.bs_server_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.bs_save, null)
            .setNegativeButton(R.string.bs_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val host = etHost.text.toString().trim()
                val port = etPort.text.toString().trim().toIntOrNull()

                when {
                    host.isEmpty() -> etHost.error = getString(R.string.bs_err_address)
                    port == null || port !in 1..65535 -> etPort.error = getString(R.string.bs_err_port)
                    else -> {
                        if (applyServerSettings(host, port, etUser.text.toString(), etPass.text.toString(), false)) {
                            Toast.makeText(this, R.string.bs_saved, Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }
                    }
                }
            }
        }

        dialog.show()
    }

    // ---- Connect / disconnect ---------------------------------------------

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    internal fun connectNow() {
        if (getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs).isEmpty()) {
            Toast.makeText(this, R.string.bs_connect_pick_server, Toast.LENGTH_LONG).show()
            return
        }

        val intent = VpnService.prepare(this)

        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, SstpVpnService::class.java).setAction(ACTION_VPN_CONNECT)
        )
    }

    internal fun disconnectNow() {
        startService(Intent(this, SstpVpnService::class.java).setAction(ACTION_VPN_DISCONNECT))
    }

    internal fun toggleConnection() {
        if (getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)) {
            disconnectNow()
        } else {
            connectNow()
        }
    }
}
