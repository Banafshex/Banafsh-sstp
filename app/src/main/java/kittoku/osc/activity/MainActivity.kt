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
import kittoku.osc.flagEmoji
import kittoku.osc.extension.firstEditText
import kittoku.osc.extension.sum
import kittoku.osc.fragment.HomeFragment
import kittoku.osc.fragment.SettingFragment
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.PROFILE_KEY_HEADER
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
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

private const val FREE_SERVERS_URL =
    "https://raw.githubusercontent.com/Banafshex/Banafsh-sstp/main/servers.json"

private const val KEY_HIDE_WELCOME = "hide_welcome"

private const val WELCOME_MESSAGE =
    "این برنامه یک VPN مستقل نیست و بر پایه‌ی کانفیگ‌هایی کار می‌کند که کاربر داخل آن وارد یا انتخاب می‌کند.\n\n" +
    "برای استفاده‌ی راحت کاربران ایرانی: با زدن کلید سه‌نقطه (⋮) در بالای صفحه، گزینه‌ی Get Free Config را بزنید و یک سرور انتخاب کنید.\n\n" +
    "سرورهای بخش Get Free Config هر چند ساعت یا چند روز یک‌بار به‌صورت خودکار به‌روزرسانی می‌شوند. این سرورها را کاربران داوطلب، که اغلب در ژاپن هستند، در اختیار همه گذاشته‌اند و ممکن است گاهی با افت سرعت یا قطعی روبه‌رو شوند.\n\n" +
    "لطفاً برای ورود به حساب‌های مهم و بانکی از این سرویس استفاده نکنید.\n\n" +
    "این برنامه توسط ARASH-BANAFSHX بر پایه‌ی Open SSTP Client (ساخته‌ی kittoku) ساخته شده است.\n\n" +
    "کانال تلگرام: https://t.me/Banafsh_sstp"

private data class FreeServer(
    val host: String,
    val port: Int,
    val country: String,
    val countryCode: String,
    val speedMbps: Double,
    val ping: Int,
)

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var homeFragment: Fragment
    private lateinit var settingFragment: PreferenceFragmentCompat

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
                Toast.makeText(this, "فایل خوانده نشد", Toast.LENGTH_SHORT).show()
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

            Toast.makeText(this, "PROFILE EXPORTED", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePreferenceView() {
        listOf(settingFragment).forEach { fragment ->
            if (fragment.isAdded) {
                val preferenceGroups = mutableListOf<PreferenceGroup>(fragment.preferenceScreen)

                while (preferenceGroups.isNotEmpty()) {
                    preferenceGroups.removeAt(0).forEach {
                        if (it is OscPreference) {
                            it.updateView()
                        }

                        if (it is PreferenceGroup) {
                            preferenceGroups.add(it)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_OpenSSTPClient_NoActionBar)
        super.onCreate(savedInstanceState)

        title = "${getString(R.string.app_name)}: ${BuildConfig.VERSION_NAME}"

        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.pager) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        IranApps.ensureSeeded(this, prefs)
        IranApps.refreshIfDue(this, prefs)

        homeFragment = HomeFragment()
        settingFragment = SettingFragment()

        object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2

            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> homeFragment
                    1 -> settingFragment
                    else -> throw NotImplementedError(position.toString())
                }
            }
        }.also {
            binding.pager.adapter = it
        }

        TabLayoutMediator(binding.tabBar, binding.pager) { tab, position ->
            tab.text = when (position) {
                0 -> "HOME"
                1 -> "SETTING"
                else -> throw NotImplementedError(position.toString())
            }
        }.attach()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        if (savedInstanceState == null && !uiPrefs.getBoolean(KEY_HIDE_WELCOME, false)) {
            showInfoDialog("به Banafsh-sstp خوش آمدید", withCheckbox = true)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        MenuInflater(this).inflate(R.menu.home_menu, menu)

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
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
            it.setMessage(sum(
                "Enter the profile's name.\n",
                "If blank, the hostname will be used.\n",
                "If duplicated, the profile will be overwritten."
            ))
            it.setPositiveButton("SAVE") { _, _ ->
                prefs.edit().also { editor ->
                    editor.putString(
                        PROFILE_KEY_HEADER + editText.text.ifEmpty { hostname },
                        serializeProfile(prefs)
                    )

                    editor.apply()
                }

                Toast.makeText(this, "PROFILE SAVED", Toast.LENGTH_SHORT).show()
            }
            it.setNegativeButton("CANCEL") { _, _ -> }
            it.show()
        }
    }

    private fun showExportDialog() {
        val filename = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs) + ".json"

        AlertDialog.Builder(this).also {
            it.setMessage(
                "Password will be also exported as plain text. If you don't want that, blank Password before exporting."
            )
            it.setPositiveButton("PROCEED") { _, _ ->
                exportLauncher.launch(filename)
            }
            it.setNegativeButton("CANCEL") { _, _ -> }
            it.show()
        }
    }

    private fun showReloadDialog() {
        AlertDialog.Builder(this).also {
            it.setMessage("Are you sure to reload the default settings?")
            it.setPositiveButton("YES") { _, _ ->
                importProfile(null, prefs)
                updatePreferenceView()

                Toast.makeText(this, "DEFAULTS RELOADED", Toast.LENGTH_SHORT).show()
            }
            it.setNegativeButton("NO") { _, _ -> }
            it.show()
        }
    }

    // ---- Welcome / About dialog -----------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    internal fun showAboutDialog() {
        showInfoDialog("درباره‌ی Banafsh-sstp", withCheckbox = false)
    }

    private fun showInfoDialog(title: String, withCheckbox: Boolean) {
        val message = TextView(this).apply {
            text = WELCOME_MESSAGE
            textSize = 15f
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setLineSpacing(0f, 1.2f)
            Linkify.addLinks(this, Linkify.WEB_URLS)
        }

        val checkBox = AppCompatCheckBox(this).apply {
            text = "دیگر این پیام را نشانم نده"
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(message)

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

        val scroll = ScrollView(this).apply { addView(container) }

        AlertDialog.Builder(this).also {
            it.setTitle(title)
            it.setView(scroll)
            it.setCancelable(!withCheckbox)
            it.setPositiveButton("بستن") { _, _ ->
                if (withCheckbox && checkBox.isChecked) {
                    uiPrefs.edit().putBoolean(KEY_HIDE_WELCOME, true).apply()
                }
            }
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
            Toast.makeText(this, "فایل یا متن کانفیگ معتبر نیست", Toast.LENGTH_LONG).show()
            return
        }

        val message = "آدرس سرور: $host\n" +
                "پورت: $port\n" +
                "نام کاربری: ${user.ifEmpty { "(خالی)" }}\n" +
                "رمز عبور: ${pass.ifEmpty { "(خالی)" }}\n\n" +
                "با تأیید، این کانفیگ جایگزین کانفیگ فعلی می‌شود و برنامه آماده‌ی استفاده است."

        AlertDialog.Builder(this).also {
            it.setTitle("کانفیگ جدید")
            it.setMessage(message)
            it.setPositiveButton("استفاده از این کانفیگ") { _, _ ->
                importProfile(profile, prefs)
                updatePreferenceView()

                Toast.makeText(this, "کانفیگ ذخیره شد و آماده‌ی استفاده است", Toast.LENGTH_LONG).show()
            }
            it.setNegativeButton("انصراف") { _, _ -> }
            it.show()
        }
    }

    // ---- Get Free Config -------------------------------------------------

    internal fun showFreeConfig() {
        Toast.makeText(this, "در حال دریافت لیست سرورها…", Toast.LENGTH_SHORT).show()

        Thread {
            val servers = try {
                downloadFreeServers()
            } catch (e: Exception) {
                null
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }

                if (servers.isNullOrEmpty()) {
                    Toast.makeText(
                        this,
                        "دریافت لیست سرورها ناموفق بود. اینترنت را بررسی کنید و دوباره امتحان کنید.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    showFreeServerDialog(servers)
                }
            }
        }.start()
    }

    private fun downloadFreeServers(): List<FreeServer> {
        val connection = URL(FREE_SERVERS_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        try {
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(text)
            val result = mutableListOf<FreeServer>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val host = obj.optString("host")
                val port = obj.optInt("port", 0)

                if (host.isEmpty() || port !in 1..65535) {
                    continue
                }

                result.add(
                    FreeServer(
                        host = host,
                        port = port,
                        country = obj.optString("country_name"),
                        countryCode = obj.optString("country"),
                        speedMbps = obj.optDouble("speed_mbps", 0.0),
                        ping = obj.optInt("ping", 0),
                    )
                )
            }

            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun showFreeServerDialog(servers: List<FreeServer>) {
        val labels = servers.map {
            "${flagEmoji(it.countryCode)} ${it.country} | ${it.host.substringBefore('.')} | " +
                    "${String.format(Locale.US, "%.0f", it.speedMbps)} Mbps | ping ${it.ping}"
        }.toTypedArray()

        AlertDialog.Builder(this).also {
            it.setTitle("انتخاب سرور")
            it.setItems(labels) { _, which ->
                showServerConfirm(servers[which])
            }
            it.setNegativeButton("انصراف") { _, _ -> }
            it.show()
        }
    }

    private fun showServerConfirm(server: FreeServer) {
        val message = "آدرس سرور: ${server.host}\n" +
                "پورت: ${server.port}\n" +
                "نام کاربری: vpn\n" +
                "رمز عبور: vpn\n\n" +
                "با تأیید، این سرور جایگزین سرور فعلی می‌شود و برنامه آماده‌ی اتصال است."

        AlertDialog.Builder(this).also {
            it.setTitle("سرور جدید")
            it.setMessage(message)
            it.setPositiveButton("استفاده از این سرور") { _, _ ->
                applyFreeServer(server)
            }
            it.setNegativeButton("انصراف") { _, _ -> }
            it.show()
        }
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
            Toast.makeText(this, "تنظیم سرور ناموفق بود", Toast.LENGTH_SHORT).show()
            return
        }

        uiPrefs.edit()
            .putString("server_host", server.host)
            .putString("server_country", server.countryCode)
            .apply()
        updatePreferenceView()

        Toast.makeText(this, "سرور ${server.host} تنظیم شد؛ در حال اتصال…", Toast.LENGTH_LONG).show()
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

    // ---- Connection log (technical details) -------------------------------

    internal fun showLogDialog() {
        val text = getStringPrefValue(OscPrefKey.HOME_STATUS, prefs).ifEmpty { getString(R.string.bs_log_empty) }

        val body = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.bs_log_title)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(R.string.bs_copy) { _, _ ->
                val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("Banafsh-sstp log", text))
                Toast.makeText(this, R.string.bs_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.bs_close, null)
            .show()
    }

    // ---- Connect / disconnect ---------------------------------------------

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    internal fun connectNow() {
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
