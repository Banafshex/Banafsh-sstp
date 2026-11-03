package kittoku.osc.activity

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kittoku.osc.R
import kittoku.osc.ServerTester
import kittoku.osc.flagEmoji
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal data class FreeServer(
    val host: String,
    val port: Int,
    val country: String,
    val countryCode: String,
    val speedMbps: Double,
    val ping: Int,
)

private const val FREE_SERVERS_URL =
    "https://raw.githubusercontent.com/Banafshex/Banafsh-sstp/main/servers.json"

private const val PENDING = -2

private val IP_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

/** The "free servers" page: downloads the list, tests every server from the user's own network, lets the user connect. */
class ServerListActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(kittoku.osc.LocaleFix.wrap(newBase))
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_COUNTRY_CODE = "country_code"
    }

    private var servers: List<FreeServer> = emptyList()
    private var results = IntArray(0)
    private var finished = 0
    private var testing = false
    private var vpnOn = false
    private var generation = 0
    private var executor: ExecutorService? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvNote: TextView
    private lateinit var btnTestAll: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var list: LinearLayout
    private lateinit var btnAuto: MaterialButton
    private lateinit var cardAuto: View
    private lateinit var barAuto: ProgressBar
    private lateinit var tvAutoPercent: TextView
    private lateinit var tvAutoStep: TextView

    // Real speed of each server, filled by the auto-pick run (nothing is shown before it is measured).
    private var rowStates = IntArray(0)
    private var rowSpeeds = DoubleArray(0)
    private var picker: AutoPicker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_OpenSSTPClient_NoActionBar)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_list)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.bs_servers_title)

        WindowCompat.getInsetsController(window, toolbar).isAppearanceLightStatusBars = false
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.body)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        tvNote = findViewById(R.id.tvTestNote)
        btnTestAll = findViewById(R.id.btnTestAll)
        progress = findViewById(R.id.progress)
        list = findViewById(R.id.listServers)

        btnTestAll.setOnClickListener { startTest() }
        btnTestAll.isEnabled = false
        btnTestAll.alpha = 0.5f

        btnAuto = findViewById(R.id.btnAuto)
        cardAuto = findViewById(R.id.cardAuto)
        barAuto = findViewById(R.id.barAuto)
        tvAutoPercent = findViewById(R.id.tvAutoPercent)
        tvAutoStep = findViewById(R.id.tvAutoStep)

        btnAuto.setOnClickListener { startAuto() }
        btnAuto.isEnabled = false
        btnAuto.alpha = 0.5f
        findViewById<View>(R.id.btnAutoStop).setOnClickListener { picker?.cancel() }

        load()
    }

    override fun onDestroy() {
        picker?.cancel()
        generation++
        executor?.shutdownNow()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    // ---- Loading ---------------------------------------------------------

    private fun load() {
        progress.visibility = View.VISIBLE
        tvNote.text = getString(R.string.bs_load_running)

        Thread {
            val downloaded = try {
                downloadFreeServers()
            } catch (e: Exception) {
                null
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }

                progress.visibility = View.GONE

                if (downloaded.isNullOrEmpty()) {
                    Toast.makeText(this, R.string.bs_load_failed, Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }

                servers = downloaded
                results = IntArray(servers.size) { PENDING }
                rowStates = IntArray(servers.size)
                rowSpeeds = DoubleArray(servers.size)
                vpnOn = isVpnActive()

                if (vpnOn) {
                    // A test through the tunnel would be misleading, so only the plain list is shown.
                    updateNote()
                    render()
                } else {
                    startTest()
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
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        if (getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)) {
            return true
        }

        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)

        return caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    // ---- Testing ---------------------------------------------------------

    private fun startTest() {
        if (servers.isEmpty() || vpnOn) {
            return
        }

        executor?.shutdownNow()
        results.fill(PENDING)
        finished = 0
        testing = true
        generation++

        val myGeneration = generation
        val pool = Executors.newFixedThreadPool(8)
        executor = pool

        updateNote()
        render()

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
                    }

                    updateNote()
                    render()
                }
            }
        }
    }

    private fun updateNote() {
        val ok = results.count { it > 0 }

        tvNote.text = when {
            vpnOn -> getString(R.string.bs_test_vpn_on)
            testing -> getString(R.string.bs_test_running, finished, servers.size)
            ok == 0 -> getString(R.string.bs_test_none)
            else -> getString(R.string.bs_test_done, ok)
        }

        val autoRunning = picker?.running == true
        val canTest = !vpnOn && !testing && !autoRunning
        btnTestAll.isEnabled = canTest
        btnTestAll.alpha = if (canTest) 1f else 0.5f

        // Stays tappable while the VPN is on, so a tap can explain why it does not start.
        val canAuto = !testing && !autoRunning && (vpnOn || ok > 0)
        btnAuto.isEnabled = canAuto
        btnAuto.alpha = if (canAuto && !vpnOn) 1f else 0.5f
    }

    // ---- Rendering -------------------------------------------------------

    private fun countryName(server: FreeServer): String {
        val code = server.countryCode.trim()

        if (code.length == 2) {
            val name = Locale.Builder().setRegion(code.uppercase(Locale.US)).build().displayCountry

            if (name.isNotBlank() && !name.equals(code, ignoreCase = true)) {
                return name
            }
        }

        return server.country
    }

    private fun shortName(host: String): String {
        return if (IP_REGEX.matches(host)) host else host.substringBefore('.')
    }

    private fun pill(color: Int) = GradientDrawable().also {
        it.cornerRadius = 12f * resources.displayMetrics.density
        it.setColor(color)
    }

    private fun dot(color: Int) = GradientDrawable().also {
        it.shape = GradientDrawable.OVAL
        it.setColor(color)
    }

    private fun render() {
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

        val purple = getColor(R.color.banafsh_purple)
        val greyButton = 0xFFC9C2DD.toInt()

        for (index in order) {
            val server = servers[index]
            val result = results[index]
            val row = layoutInflater.inflate(R.layout.item_free_server, list, false)

            row.findViewById<TextView>(R.id.tvTitle).text =
                "${flagEmoji(server.countryCode)} ${countryName(server)} · ${shortName(server.host)}".trim()

            // The speed the list file announces is measured from another country: never shown.
            val tvSpeed = row.findViewById<TextView>(R.id.tvSpeed)
            when (rowStates[index]) {
                AutoPicker.ROW_DONE -> tvSpeed.text = getString(R.string.bs_real_speed, formatMbps(rowSpeeds[index]))
                AutoPicker.ROW_TESTING -> tvSpeed.setText(R.string.bs_speed_testing)
                AutoPicker.ROW_FAILED -> tvSpeed.setText(R.string.bs_speed_failed)
                else -> tvSpeed.visibility = View.GONE
            }

            val pill = row.findViewById<View>(R.id.pill)
            val pillDot = row.findViewById<View>(R.id.dotState)
            val pillText = row.findViewById<TextView>(R.id.tvPing)
            val button = row.findViewById<MaterialButton>(R.id.btnConnect)

            when {
                vpnOn -> {
                    // Not tested: no light, the button is simply available.
                    pill.visibility = View.GONE
                    button.backgroundTintList = ColorStateList.valueOf(purple)
                }

                result > 0 -> {
                    pill.background = pill(0xFFDDF7E8.toInt())
                    pillDot.background = dot(0xFF2ECC71.toInt())
                    pillText.setTextColor(0xFF0B6B30.toInt())
                    pillText.text = getString(R.string.bs_ping_ms, result) + " ✓"
                    button.backgroundTintList = ColorStateList.valueOf(purple)
                }

                result == ServerTester.FAILED -> {
                    pill.background = pill(0xFFFDE7E7.toInt())
                    pillDot.background = dot(0xFFE5484D.toInt())
                    pillText.setTextColor(0xFF8A1C1C.toInt())
                    pillText.text = getString(R.string.bs_server_failed) + " ✕"
                    button.backgroundTintList = ColorStateList.valueOf(greyButton)
                    row.alpha = 0.6f
                }

                else -> {
                    pill.background = pill(0xFFECE8F7.toInt())
                    pillDot.background = dot(0xFF9E9E9E.toInt())
                    pillText.setTextColor(0xFF5B4A8C.toInt())
                    pillText.text = getString(R.string.bs_pill_testing)
                    button.backgroundTintList = ColorStateList.valueOf(greyButton)
                    button.isEnabled = false
                }
            }

            button.setOnClickListener {
                if (result == ServerTester.FAILED) {
                    AlertDialog.Builder(this)
                        .setMessage(R.string.bs_connect_anyway)
                        .setPositiveButton(R.string.bs_connect) { _, _ -> choose(server) }
                        .setNegativeButton(R.string.bs_cancel, null)
                        .show()
                } else {
                    choose(server)
                }
            }

            list.addView(row)
        }
    }

    private fun formatMbps(value: Double): String {
        return if (value < 0.1) "<0.1" else String.format(Locale.US, "%.1f", value)
    }

    // ---- Auto-pick -------------------------------------------------------

    private val pickerUi = object : AutoPicker.Ui {
        override fun onProgress(percent: Int, step: AutoPicker.Step, current: Int, total: Int) {
            if (isDestroyed) return

            barAuto.progress = percent
            tvAutoPercent.text = "$percent%"

            val text = when (step) {
                AutoPicker.Step.CONNECTING -> R.string.bs_auto_step_connecting
                AutoPicker.Step.MEASURING -> R.string.bs_auto_step_measuring
                AutoPicker.Step.DISCONNECTING -> R.string.bs_auto_step_disconnecting
            }
            tvAutoStep.text = getString(text, current, total)
        }

        override fun onRowState(index: Int, state: Int, mbps: Double) {
            if (isDestroyed || index !in rowStates.indices) return

            rowStates[index] = state
            rowSpeeds[index] = mbps
            render()
        }

        override fun onFinished(bestIndex: Int) {
            if (isDestroyed) return

            cardAuto.visibility = View.GONE
            updateNote()
            render()

            if (bestIndex < 0) {
                Toast.makeText(this@ServerListActivity, R.string.bs_auto_none, Toast.LENGTH_LONG).show()
                return
            }

            val best = servers[bestIndex]
            Toast.makeText(
                this@ServerListActivity,
                getString(R.string.bs_auto_best, shortName(best.host), formatMbps(rowSpeeds[bestIndex]), results[bestIndex]),
                Toast.LENGTH_LONG
            ).show()

            doChoose(best)
        }

        override fun onStopped() {
            if (isDestroyed) return

            cardAuto.visibility = View.GONE
            updateNote()
            render()
        }
    }

    private fun startAuto() {
        if (picker?.running == true || testing) return

        if (vpnOn) {
            Toast.makeText(this, R.string.bs_auto_disconnect_first, Toast.LENGTH_LONG).show()
            return
        }

        if (VpnService.prepare(this) != null) {
            Toast.makeText(this, R.string.bs_auto_need_permission, Toast.LENGTH_LONG).show()
            return
        }

        // The servers that answered, lowest ping first: only these are worth a real speed measurement.
        val candidates = servers.indices
            .filter { results[it] > 0 }
            .sortedBy { results[it] }
            .take(AutoPicker.MAX_CANDIDATES)
            .map { AutoPicker.Candidate(it, servers[it], results[it]) }

        if (candidates.isEmpty()) return

        rowStates.fill(AutoPicker.ROW_IDLE)
        rowSpeeds.fill(0.0)

        val newPicker = AutoPicker(this, pickerUi)
        picker = newPicker

        cardAuto.visibility = View.VISIBLE
        barAuto.progress = 0
        tvAutoPercent.text = "0%"
        tvAutoStep.text = ""

        newPicker.start(candidates)
        updateNote()
        render()
    }

    // The user may tap Connect while the run is going: stop it first, then continue with that server.
    private fun choose(server: FreeServer) {
        val current = picker

        if (current != null && current.running) {
            current.cancel { doChoose(server) }
        } else {
            doChoose(server)
        }
    }

    private fun doChoose(server: FreeServer) {
        generation++
        executor?.shutdownNow()

        setResult(
            RESULT_OK,
            android.content.Intent()
                .putExtra(EXTRA_HOST, server.host)
                .putExtra(EXTRA_PORT, server.port)
                .putExtra(EXTRA_COUNTRY_CODE, server.countryCode)
        )
        finish()
    }
}
