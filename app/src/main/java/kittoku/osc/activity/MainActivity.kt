package kittoku.osc.activity

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kittoku.osc.ServerTester
import kittoku.osc.ThemeMode
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val FREE_SERVERS_URL =
    "https://raw.githubusercontent.com/Banafshex/Banafsh-sstp/main/servers.json"

private const val PENDING = -2

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
        // Home refreshes itself from the preferences; Settings reads them when it is opened.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeMode.apply(PreferenceManager.getDefaultSharedPreferences(this).getString("THEME_MODE", "system"))
        setTheme(R.style.Theme_OpenSSTPClient_NoActionBar)
        super.onCreate(savedInstanceState)

        title = "${getString(R.string.app_name)}: ${BuildConfig.VERSION_NAME}"

        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = false
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

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.container, HomeFragment()).commit()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        if (savedInstanceState == null && !uiPrefs.getBoolean(KEY_HIDE_WELCOME, false)) {
            showWelcomeDialog(this)
        }
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

    private fun isVpnActive(): Boolean {
        if (getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)) {
            return true
        }

        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)

        return caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private fun showFreeServerDialog(servers: List<FreeServer>) {
        val view = layoutInflater.inflate(R.layout.dialog_free_servers, null)
        val note = view.findViewById<TextView>(R.id.tvTestNote)
        val list = view.findViewById<LinearLayout>(R.id.listServers)

        val dp = resources.displayMetrics.density
        val colorIdle = 0xFF9E9E9E.toInt()
        val colorOk = 0xFF2ECC71.toInt()
        val colorBad = 0xFFE5484D.toInt()

        // -2 = not tested yet / testing, FAILED = unreachable, otherwise the measured ms
        val results = IntArray(servers.size) { PENDING }
        var finished = 0
        var testing = false
        var generation = 0
        var executor: ExecutorService? = null
        val handler = Handler(Looper.getMainLooper())

        lateinit var dialog: AlertDialog

        fun dotDrawable(color: Int) = GradientDrawable().also {
            it.shape = GradientDrawable.OVAL
            it.setColor(color)
        }

        fun render() {
            list.removeAllViews()

            val order = servers.indices.sortedWith(
                compareBy<Int> {
                    when {
                        results[it] > 0 -> 0
                        results[it] == PENDING -> 1
                        else -> 2
                    }
                }.thenBy { if (results[it] > 0) results[it] else it }
            )

            for (index in order) {
                val server = servers[index]
                val row = layoutInflater.inflate(R.layout.item_free_server, list, false)
                val result = results[index]

                row.findViewById<TextView>(R.id.tvCountry).text =
                    "${flagEmoji(server.countryCode)} ${server.country}".trim()
                row.findViewById<TextView>(R.id.tvHost).text = server.host.substringBefore('.')

                val ping = row.findViewById<TextView>(R.id.tvPing)
                val dot = row.findViewById<View>(R.id.dotState)

                when {
                    result > 0 -> {
                        dot.background = dotDrawable(colorOk)
                        ping.text = getString(R.string.bs_ping_ms, result)
                    }

                    result == ServerTester.FAILED -> {
                        dot.background = dotDrawable(colorBad)
                        ping.text = getString(R.string.bs_server_failed)
                        row.alpha = 0.45f
                    }

                    else -> {
                        dot.background = dotDrawable(colorIdle)
                        ping.text = if (testing) "…" else ""
                    }
                }

                row.setOnClickListener {
                    generation++
                    executor?.shutdownNow()
                    dialog.dismiss()
                    showServerConfirm(server)
                }

                list.addView(row)
            }
        }

        fun updateNote(vpnOn: Boolean) {
            val ok = results.count { it > 0 }

            note.text = when {
                vpnOn -> getString(R.string.bs_test_vpn_on)
                testing -> getString(R.string.bs_test_running, finished, servers.size)
                ok == 0 -> getString(R.string.bs_test_none)
                else -> getString(R.string.bs_test_done, ok)
            }
        }

        fun startTest() {
            executor?.shutdownNow()
            results.fill(PENDING)
            finished = 0
            testing = true
            generation++

            val myGeneration = generation
            val pool = Executors.newFixedThreadPool(8)
            executor = pool

            updateNote(false)
            render()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = false

            servers.forEachIndexed { index, server ->
                pool.execute {
                    val ms = ServerTester.test(server.host, server.port)

                    handler.post {
                        if (myGeneration != generation) {
                            return@post
                        }

                        results[index] = ms
                        finished++

                        if (finished >= servers.size) {
                            testing = false
                            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = true
                        }

                        updateNote(false)
                        render()
                    }
                }
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.bs_free_title)
            .setView(view)
            .setPositiveButton(R.string.bs_test_again, null)
            .setNegativeButton(R.string.bs_cancel, null)
            .create()

        dialog.setOnShowListener {
            val retest = dialog.getButton(DialogInterface.BUTTON_POSITIVE)

            retest.setOnClickListener {
                if (isVpnActive()) {
                    updateNote(true)
                } else {
                    startTest()
                }
            }

            if (isVpnActive()) {
                // A test through the tunnel would be misleading, so show the plain list only.
                retest.isEnabled = true
                updateNote(true)
                render()
            } else {
                startTest()
            }
        }

        dialog.setOnDismissListener {
            generation++
            executor?.shutdownNow()
        }

        dialog.show()
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
