package kittoku.osc.activity

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kittoku.osc.R
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.IkeProbe
import kittoku.osc.Protocol
import kittoku.osc.ProbeBatch
import kittoku.osc.ProtocolSwitch
import kittoku.osc.ServerTester
import kittoku.osc.flagEmoji
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import java.util.Locale
import kotlin.concurrent.thread

private enum class Quality { GOOD, MEDIUM, BAD, UNKNOWN }

// Share of the announced server speed that each user gets (Mbps per session).
private const val GOOD_SHARE = 8.0
private const val MEDIUM_SHARE = 3.0

private fun shareOf(server: FreeServer): Double = server.speedMbps / (server.sessions + 1)

// Order of the tiers in the list: good first, then medium, then unknown, bad last.
private fun qualityRank(server: FreeServer): Int = when (qualityOf(server)) {
    Quality.GOOD -> 0
    Quality.MEDIUM -> 1
    Quality.UNKNOWN -> 2
    Quality.BAD -> 3
}

// A server with 0 or 1 session may simply be new, or one nobody can use: no verdict for it.
private fun qualityOf(server: FreeServer): Quality = when {
    server.sessions <= 1 -> Quality.UNKNOWN
    shareOf(server) >= GOOD_SHARE -> Quality.GOOD
    shareOf(server) >= MEDIUM_SHARE -> Quality.MEDIUM
    else -> Quality.BAD
}

private const val PENDING = -2
private const val BATCH_TIMEOUT_MS = 12_000L

// A country can have hundreds of servers: only the best ones are drawn at first.
private const val ROWS_LIMIT = 50

private val IP_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

/**
 * The "free servers" page: downloads the list, shows the countries; a country opens its servers,
 * which are tested from the user's own network and shown with ping and a quality label.
 */
class ServerListActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(kittoku.osc.LocaleFix.wrap(newBase))
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_IP = "ip"
        const val EXTRA_COUNTRY_CODE = "country_code"
        const val EXTRA_PING = "ping"
    }

    private var servers: List<FreeServer> = emptyList()
    private var results = IntArray(0)
    private var currentCountry: String? = null   // null: the country list is shown
    private var visible: List<Int> = emptyList() // indices of the servers of the open country
    private var showAll = false
    private var nextCatalog: List<FreeServer>? = null
    private var loader: Thread? = null
    private val protocol by lazy { ProtocolSwitch.get(this) }
    private var finished = 0
    private var batch: ProbeBatch? = null
    private val testing get() = batch != null
    private var vpnOn = false
    private val deadline = Runnable { finishTests() }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvNote: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnTestAll: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_OpenSSTPClient_NoActionBar)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_list)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.bs_servers_title)

        findViewById<View>(R.id.serverRoot).applyTvSafeArea()

        tvNote = findViewById(R.id.tvTestNote)
        tvHint = findViewById(R.id.tvHint)
        btnTestAll = findViewById(R.id.btnTestAll)
        progress = findViewById(R.id.progress)
        list = findViewById(R.id.listServers)

        btnTestAll.setOnClickListener { startTest() }
        btnTestAll.setText(R.string.bs_test_again)
        btnTestAll.visibility = View.GONE

        // Back from a country returns to the country list first.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentCountry != null) {
                    showCountries()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        load()
    }

    override fun onDestroy() {
        loader?.interrupt()
        stopTests()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (currentCountry != null) showCountries() else finish()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    // ---- Loading ---------------------------------------------------------

    private fun load() {
        progress.visibility = View.VISIBLE
        tvNote.text = getString(R.string.bs_load_running)

        val catalog = ServerCatalog(this, protocol)
        loader = thread(name = "server-catalog") {
            val local = catalog.local()
            if (local.servers.isNotEmpty()) handler.post { offerCatalog(local.servers) }
            if (local.fresh) return@thread
            val refreshed = catalog.refresh()
            handler.post {
                if (isFinishing || isDestroyed) return@post
                if (refreshed.isNotEmpty()) {
                    offerCatalog(refreshed)
                } else if (servers.isEmpty()) {
                    Toast.makeText(this, R.string.bs_load_failed, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, R.string.bs_load_stale, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun offerCatalog(fresh: List<FreeServer>) {
        if (isFinishing || isDestroyed) return
        progress.visibility = View.GONE
        nextCatalog = fresh
        // Do not replace the endpoints underneath probes or a focused server. Back applies the refresh.
        if (currentCountry == null) showCountries()
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

    private fun showCountries() {
        stopTests()
        val previous = currentCountry ?: list.findFocus()?.tag
        nextCatalog?.let {
            servers = it
            results = IntArray(it.size) { PENDING }
            nextCatalog = null
        }
        currentCountry = null
        visible = emptyList()

        title = getString(R.string.bs_servers_title)
        tvHint.visibility = View.GONE
        btnTestAll.visibility = View.GONE

        updateNote()
        render(previous ?: "first")
    }

    private fun showServers(code: String) {
        stopTests()
        showAll = false
        currentCountry = code
        visible = servers.indices.filter { servers[it].countryCode == code }

        val first = servers[visible.first()]
        title = "${flagEmoji(code)} ${countryName(first)}".trim()
        tvHint.visibility = View.VISIBLE
        btnTestAll.visibility = View.VISIBLE

        vpnOn = isVpnActive()
        if (vpnOn) {
            // A test through the tunnel would be misleading, so only the plain list is shown.
            updateNote()
            render("first")
        } else {
            startTest()
        }
    }

    private fun stopTests() {
        handler.removeCallbacks(deadline)
        batch?.close()
        batch = null
    }

    private fun finishTests() {
        stopTests()
        updateNote()
        render() // rank replies once, preserving the focused server
    }

    private fun startTest() {
        if (visible.isEmpty() || vpnOn) return
        stopTests()
        visible.forEach { results[it] = PENDING }
        finished = 0
        val run = ProbeBatch()
        batch = run
        val useIke = protocol == Protocol.L2TP
        updateNote()
        render(list.findFocus()?.tag ?: "first")
        handler.postDelayed(deadline, BATCH_TIMEOUT_MS)

        visible.forEach { index ->
            val server = servers[index]
            run.execute {
                val ms = if (useIke) IkeProbe.test(server.endpoint, run) else ServerTester.test(server.endpoint, server.port, run)
                handler.post {
                    if (batch !== run) return@post
                    results[index] = ms
                    finished++
                    if (finished == visible.size) {
                        finishTests()
                    } else {
                        updateNote()
                        // Keep the actual focused view alive between D-pad key down and key up.
                        list.findViewWithTag<View>(index)?.let { bindPing(it, ms) }
                    }
                }
            }
        }
    }

    private fun updateNote() {
        if (currentCountry == null) {
            val countries = servers.map { it.countryCode }.distinct().size
            tvNote.text = getString(R.string.bs_countries_note, servers.size, countries)
            return
        }

        val ok = visible.count { results[it] > 0 }

        tvNote.text = when {
            vpnOn -> getString(R.string.bs_test_vpn_on)
            testing -> getString(R.string.bs_test_running, finished, visible.size)
            finished < visible.size -> getString(R.string.tv_test_deadline, ok, visible.size - finished)
            ok == 0 -> getString(R.string.bs_test_none)
            else -> getString(R.string.bs_test_done, ok)
        }

        val canTest = !vpnOn && !testing
        btnTestAll.isEnabled = canTest
        btnTestAll.alpha = if (canTest) 1f else 0.5f
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

    private fun currentHost(): String =
        getStringPrefValue(OscPrefKey.HOME_HOSTNAME, PreferenceManager.getDefaultSharedPreferences(this))

    private fun render(focusTag: Any? = list.findFocus()?.tag) {
        if (currentCountry == null) renderCountries() else renderServers(focusTag as? Int)
        // Ping results reorder this list. Follow the same server, not its old screen position.
        if (focusTag != null && !list.isInTouchMode) {
            (list.findViewWithTag<View>(focusTag) ?: list.getChildAt(0))?.requestFocus()
        }
    }

    private fun renderCountries() {
        list.removeAllViews()

        val groups = servers.indices.groupBy { servers[it].countryCode }
        val ordered = groups.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<Int>>> { it.value.size }
                .thenBy { countryName(servers[it.value.first()]) }
        )

        val currentHost = currentHost()

        for ((code, indices) in ordered) {
            val row = layoutInflater.inflate(R.layout.item_country, list, false)

            // The country that holds the server in use is marked, so it is easy to find among the others.
            if (currentHost.isNotEmpty() && indices.any { servers[it].endpoint.host == currentHost }) {
                row.findViewById<TextView>(R.id.tvCountryCurrent).apply {
                    setText(R.string.bs_current_here)
                    visibility = View.VISIBLE
                }
            }

            row.findViewById<TextView>(R.id.tvCountryTitle).text =
                "${flagEmoji(code)} ${countryName(servers[indices.first()])}".trim()
            row.findViewById<TextView>(R.id.tvCountryCount).text =
                if (indices.size == 1) getString(R.string.bs_country_servers_one)
                else getString(R.string.bs_country_servers, indices.size)

            row.tag = code
            row.setOnClickListener { showServers(code) }
            list.addView(row)
        }
    }

    private fun renderServers(focusedIndex: Int?) {
        list.removeAllViews()

        // Servers that answered come first: by quality tier (good first), inside a tier by the lowest ping.
        // The ones not answered yet follow by quality, and the ones that failed are last.
        val currentHost = currentHost()

        val order = visible.sortedWith(
            compareBy<Int> { if (servers[it].endpoint.host == currentHost) 0 else 1 } // the server in use stays on top
            .thenBy {
                when {
                    results[it] > 0 -> 0
                    results[it] == PENDING -> 1
                    else -> 2
                }
            }
                .thenBy { qualityRank(servers[it]) }
                .thenBy { if (results[it] > 0) results[it].toDouble() else -shareOf(servers[it]) }
        )

        val shown = if (showAll || order.size <= ROWS_LIMIT) order else order.take(ROWS_LIMIT).toMutableList().apply {
            // Do not evict the focused server when faster ones enter the first page.
            if (focusedIndex != null && focusedIndex in order && focusedIndex !in this) add(focusedIndex)
        }
        val showPort = protocol == Protocol.SSTP

        for (index in shown) {
            val server = servers[index]
            val result = results[index]
            val row = layoutInflater.inflate(R.layout.item_free_server, list, false)

            row.findViewById<TextView>(R.id.tvTitle).text =
                "${flagEmoji(server.countryCode)} ${countryName(server)} · ${shortName(server.endpoint.host)}".trim()

            // The server in use is framed and tagged: the names look alike, so it is easy to pick it again by mistake.
            val isCurrent = server.endpoint.host == currentHost
            val tvCurrent = row.findViewById<TextView>(R.id.tvCurrent)
            val tvPort = row.findViewById<TextView>(R.id.tvPort)

            if (isCurrent) {
                row.background = getDrawable(R.drawable.bg_server_card_current)
                tvCurrent.setText(R.string.bs_current_server)
                tvCurrent.background = pill(0xFF8B5CF6.toInt())
                tvCurrent.visibility = View.VISIBLE
            }

            // SSTP connects to the server's TCP port, so it is shown; L2TP does not use it.
            if (showPort && server.port in 1..65535) {
                tvPort.text = getString(R.string.bs_port_label, server.port)
                tvPort.visibility = View.VISIBLE
            }

            if (isCurrent || showPort) {
                row.findViewById<View>(R.id.rowMeta).visibility = View.VISIBLE
            }

            // Quality: how much of the server's speed each user gets, from the list's own numbers.
            val chip = row.findViewById<TextView>(R.id.tvSpeed)
            when (qualityOf(server)) {
                Quality.GOOD -> {
                    chip.setText(R.string.bs_quality_good)
                    chip.background = pill(0xFFDDF7E8.toInt())
                    chip.setTextColor(0xFF0B6B30.toInt())
                }

                Quality.MEDIUM -> {
                    chip.setText(R.string.bs_quality_medium)
                    chip.background = pill(0xFFFFF1CC.toInt())
                    chip.setTextColor(0xFF7A5200.toInt())
                }

                Quality.BAD -> {
                    chip.setText(R.string.bs_quality_bad)
                    chip.background = pill(0xFFFDE7E7.toInt())
                    chip.setTextColor(0xFF8A1C1C.toInt())
                }

                Quality.UNKNOWN -> {
                    chip.setText(R.string.bs_quality_unknown)
                    chip.background = pill(0xFFECECEC.toInt())
                    chip.setTextColor(0xFF5F5F5F.toInt())
                }
            }

            bindPing(row, result)
            row.tag = index
            row.setOnClickListener { choose(index) }
            list.addView(row)
        }

        if (shown.size < order.size) {
            val more = MaterialButton(this)
            more.tag = "more"
            more.text = getString(R.string.bs_show_all, order.size)
            more.setOnClickListener {
                showAll = true
                render(order.first { it !in shown })
            }
            list.addView(more)
        }
    }

    private fun bindPing(row: View, result: Int) {
        val pill = row.findViewById<View>(R.id.pill)
        val pillDot = row.findViewById<View>(R.id.dotState)
        val pillText = row.findViewById<TextView>(R.id.tvPing)

        when {
            vpnOn -> {
                // Not tested: no light, the button is simply available.
                pill.visibility = View.GONE
            }

            result > 0 -> {
                pill.background = pill(0xFFDDF7E8.toInt())
                pillDot.background = dot(0xFF2ECC71.toInt())
                pillText.setTextColor(0xFF0B6B30.toInt())
                pillText.text = getString(R.string.bs_ping_ms, result) + " ✓"
            }

            result == ServerTester.FAILED -> {
                pill.background = pill(0xFFFDE7E7.toInt())
                pillDot.background = dot(0xFFE5484D.toInt())
                pillText.setTextColor(0xFF8A1C1C.toInt())
                pillText.text = getString(R.string.bs_server_failed) + " ✕"
            }

            else -> {
                pill.background = pill(0xFFECE8F7.toInt())
                pillDot.background = dot(0xFF9E9E9E.toInt())
                pillText.setTextColor(0xFF5B4A8C.toInt())
                pillText.setText(if (testing) R.string.bs_pill_testing else R.string.tv_not_tested)
            }
        }
    }

    private fun choose(index: Int) {
        val server = servers[index]
        stopTests()
        setResult(
            RESULT_OK,
            android.content.Intent()
                .putExtra(EXTRA_HOST, server.endpoint.host)
                .putExtra(EXTRA_IP, server.endpoint.ip)
                .putExtra(EXTRA_PORT, server.port)
                .putExtra(EXTRA_COUNTRY_CODE, server.countryCode)
                .putExtra(EXTRA_PING, if (vpnOn) 0 else results[index].coerceAtLeast(0))
        )
        finish()
    }
}
