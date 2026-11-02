package kittoku.osc

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import kittoku.osc.control.LogWriter
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.getSetPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.getURIPrefValue
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import vn.unlimit.softether.SoftEtherTrafficSnapshot
import vn.unlimit.softether.SoftEtherVpnService
import vn.unlimit.softether.model.AuthMethod
import vn.unlimit.softether.model.ConnectionConfig
import vn.unlimit.softether.model.Route
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/** Log file shared by the callbacks (main thread) and the logcat reader thread. */
private class LogSink(private val writer: LogWriter) {
    private var closed = false

    @Synchronized
    fun write(message: String) {
        if (closed) return
        try {
            writer.write(message)
            writer.flush()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        try {
            writer.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Connects the SoftEther module to the same screens SSTP uses.
 * It writes the same preferences the home screen and the tile already watch
 * (ROOT_STATE, HOME_CONNECTOR, HOME_STATUS) and repeats the connection after a drop,
 * using the reconnection settings of the app.
 */
internal object SoftEtherBridge : SoftEtherVpnService.StateListener, SoftEtherVpnService.TrafficListener {
    private const val UI_PREFS = "banafsh_ui"
    private const val KEY_ACTIVE = "softether_active"

    // A "disconnected" report this soon after a start, with no progress before it, belongs to the previous run.
    private const val STALE_MS = 1500L
    private const val RESTART_DELAY_MS = 2000L

    // The free VPN Gate relays only have this virtual hub (the default "VPN" gets error 8 = hub not found).
    private const val VPN_GATE_HUB = "VPNGATE"

    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var registered = false

    @Volatile private var userStopped = true
    @Volatile private var restarting = false
    @Volatile private var sawProgress = false
    @Volatile private var startedAt = 0L
    @Volatile private var retryPending = false
    private var retriesLeft = 0
    private var attempt = 0

    private var logSink: LogSink? = null
    private var dumpThread: Thread? = null
    private var sessionStamp: String? = null
    @Volatile private var lastTrafficLogAt = 0L

    private val midDumpTask = Runnable { dumpLogcat("10 s after connect") }

    private val retryTask = Runnable {
        retryPending = false

        appContext?.let {
            if (!userStopped) begin(it)
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext

        if (!registered) {
            registered = true

            // The process died while SoftEther was active (crash or kill): keep what logcat still has about it.
            if (isActive(appContext!!)) {
                recoverPreviousSession(appContext!!)
            }

            SoftEtherVpnService.addStateListener(this)
            SoftEtherVpnService.addTrafficListener(this)
        }
    }

    // ---- diagnostics (same log folder as the SSTP log) -------------------------------------

    private val logKeys = listOf(
        "SoftEther", "TunTerminal", "KeepAliveManager", "DuplexModeSelector", "ConnectionController",
        "AndroidRuntime", "FATAL", "DEBUG", "libc", "VpnService"
    )

    private fun openSink(app: Context, prefs: SharedPreferences, suffix: String): LogSink? {
        if (!getBooleanPrefValue(OscPrefKey.LOG_DO_SAVE_LOG, prefs)) return null

        return try {
            val uri = getURIPrefValue(OscPrefKey.LOG_DIR, prefs) ?: return null
            val dir = DocumentFile.fromTreeUri(app, uri) ?: return null
            val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())
            val file = dir.createFile("text/plain", "log_osc_${stamp}_softether$suffix.txt") ?: return null
            val stream = app.contentResolver.openOutputStream(file.uri, "wa") ?: return null
            LogSink(LogWriter(stream))
        } catch (_: Exception) {
            null
        }
    }

    private fun log(message: String) {
        logSink?.write("[${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())}] $message")
    }

    // An app can read its own logcat lines without any permission.
    private fun readOwnLogcat(since: String?): List<String> = try {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "6000", "-v", "time"))
        val lines = process.inputStream.bufferedReader().useLines { sequence ->
            sequence.filter { line ->
                // "MM-dd HH:mm:ss.SSS ..." sorts as text, so older attempts are skipped
                (since == null || (line.length >= 18 && line.substring(0, 18) >= since)) &&
                    logKeys.any { line.contains(it) }
            }.toList()
        }
        process.destroy()
        pickLines(lines)
    } catch (e: Exception) {
        listOf("logcat is not readable: ${e.message}")
    }

    // Long sessions: keep the start (DHCP, tunnel setup), every warning/error, and the end.
    private fun pickLines(lines: List<String>): List<String> {
        if (lines.size <= 450) return lines

        val head = lines.take(250)
        val tail = lines.takeLast(120)
        val middle = lines.subList(250, lines.size - 120)
            .filter { it.contains(" W/") || it.contains(" E/") }
            .take(120)

        return head + "... ${lines.size - 370} lines skipped, warnings and errors kept ..." + middle + tail
    }

    private fun dumpLogcat(title: String, delayMs: Long = 0L) {
        val sink = logSink ?: return
        val since = sessionStamp

        val thread = Thread {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                }
            }

            sink.write("--- $title: engine log (logcat) ---")
            readOwnLogcat(since).forEach { sink.write(it) }
            sink.write("--- end ---")
        }
        dumpThread = thread
        thread.start()
    }

    private fun closeLog() {
        val sink = logSink ?: return
        logSink = null

        val thread = dumpThread
        if (thread != null && thread.isAlive) {
            Thread {
                try {
                    thread.join(4000)
                } catch (_: InterruptedException) {
                }
                sink.close()
            }.start()
        } else {
            sink.close()
        }
    }

    private fun recoverPreviousSession(app: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val sink = openSink(app, prefs, "_recovered") ?: return

        sessionStamp = null
        logSink = sink
        log("The previous SoftEther session ended without a clean disconnect (crash or kill)")
        dumpLogcat("Previous session")
        closeLog()
    }

    private fun ui(context: Context) = context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)

    private fun isActive(context: Context) = ui(context).getBoolean(KEY_ACTIVE, false)

    private fun setActive(context: Context, active: Boolean) {
        ui(context).edit().putBoolean(KEY_ACTIVE, active).apply()
    }

    private fun tunnelIsUp(): Boolean {
        val state = SoftEtherVpnService.currentState
        return state != SoftEtherVpnService.STATE_DISCONNECTED && state != SoftEtherVpnService.STATE_ERROR
    }

    fun isRunning(): Boolean = retryPending || restarting || tunnelIsUp()

    /** Used by the home screen when the saved "connected" state is stale (the app was killed). */
    fun clearActive(context: Context) {
        setActive(context.applicationContext, false)
    }

    // ---- start / stop ---------------------------------------------------------------------

    fun connect(context: Context) {
        val app = context.applicationContext
        init(app)
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)

        handler.removeCallbacksAndMessages(null)
        retryPending = false
        userStopped = false

        retriesLeft = if (getBooleanPrefValue(OscPrefKey.RECONNECTION_ENABLED, prefs)) {
            getIntPrefValue(OscPrefKey.RECONNECTION_COUNT, prefs)
        } else 0

        setActive(app, true)
        setBooleanPrefValue(true, OscPrefKey.ROOT_STATE, prefs)
        setBooleanPrefValue(true, OscPrefKey.HOME_CONNECTOR, prefs)
        setStringPrefValue("", OscPrefKey.HOME_STATUS, prefs)

        attempt = 0
        sessionStamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        closeLog()
        logSink = openSink(app, prefs, "")
        log("SoftEther connect requested")
        log("Device: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ABIs ${Build.SUPPORTED_ABIS.joinToString()}, ${Build.MANUFACTURER} ${Build.MODEL}")

        if (tunnelIsUp()) {
            // Still connected to an old server: drop it first so the new one is really used.
            restarting = true
            sendDisconnect(app)
            handler.postDelayed({
                restarting = false
                if (!userStopped) begin(app)
            }, RESTART_DELAY_MS)
        } else {
            begin(app)
        }
    }

    fun disconnect(context: Context) {
        val app = context.applicationContext
        init(app)
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)

        userStopped = true
        restarting = false
        retryPending = false
        handler.removeCallbacksAndMessages(null)
        log("Disconnect requested by the user")

        if (tunnelIsUp()) {
            // The state report that follows clears the saved state.
            dumpLogcat("Session end", 1500L)
            sendDisconnect(app)
        } else {
            finish(app, prefs)
        }
    }

    private fun begin(app: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)

        sawProgress = false
        startedAt = SystemClock.elapsedRealtime()
        attempt++

        // A missing or broken native library would otherwise kill the app inside the service.
        try {
            System.loadLibrary("softether")
        } catch (t: Throwable) {
            log("Attempt $attempt: the native SoftEther library could not be loaded: $t")
            Toast.makeText(app, R.string.bs_softether_native_failed, Toast.LENGTH_LONG).show()
            finish(app, prefs)
            return
        }

        try {
            val config = buildConfig(app, prefs)

            log(
                "Attempt $attempt: ${config.serverHost}:${config.serverPort}, user=${config.username}, hub=${config.virtualHub}, " +
                    "tcp=${config.useTcp}, udp=${config.useUdp}, routes=${config.routes.size}, " +
                    "excludedApps=${config.excludedApps.size}, dns=${config.dnsServer}"
            )

            ContextCompat.startForegroundService(
                app,
                Intent(app, SoftEtherVpnService::class.java)
                    .setAction(SoftEtherVpnService.ACTION_CONNECT)
                    .putExtra(SoftEtherVpnService.EXTRA_CONFIG, config)
            )
        } catch (e: Exception) {
            log("Attempt $attempt: could not start the SoftEther service: $e")
            finish(app, prefs)
        }
    }

    private fun sendDisconnect(app: Context) {
        ContextCompat.startForegroundService(
            app,
            Intent(app, SoftEtherVpnService::class.java).setAction(SoftEtherVpnService.ACTION_DISCONNECT)
        )
    }

    // ---- state reports from the module ------------------------------------------------------

    // Packets leaving and arriving through the tunnel: tells whether the server answers at all.
    override fun onSoftEtherTrafficUpdated(snapshot: SoftEtherTrafficSnapshot) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrafficLogAt < 5000L) return
        lastTrafficLogAt = now

        if (logSink == null) return
        log("Traffic: sent ${snapshot.outBytes} B / ${snapshot.packetsOut} pkts, received ${snapshot.inBytes} B / ${snapshot.packetsIn} pkts")
    }

    override fun onSoftEtherStateChanged(state: String, assignedIp: String) {
        val app = appContext ?: return

        // The module replays its current state when we register; ignore it unless SoftEther is the chosen tunnel.
        if (!isActive(app)) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(app)

        log("State: $state" + if (assignedIp.isNotEmpty()) " (ip $assignedIp)" else "")

        when (state) {
            SoftEtherVpnService.STATE_CONNECTING,
            SoftEtherVpnService.STATE_TLS_HANDSHAKE,
            SoftEtherVpnService.STATE_PROTOCOL_HANDSHAKE,
            SoftEtherVpnService.STATE_AUTHENTICATING,
            SoftEtherVpnService.STATE_SESSION_SETUP -> {
                sawProgress = true
                setBooleanPrefValue(true, OscPrefKey.ROOT_STATE, prefs)
                setBooleanPrefValue(true, OscPrefKey.HOME_CONNECTOR, prefs)
                setStringPrefValue("", OscPrefKey.HOME_STATUS, prefs)
            }

            SoftEtherVpnService.STATE_CONNECTED -> {
                sawProgress = true
                handler.removeCallbacks(midDumpTask)
                handler.postDelayed(midDumpTask, 10_000L)
                retriesLeft = if (getBooleanPrefValue(OscPrefKey.RECONNECTION_ENABLED, prefs)) {
                    getIntPrefValue(OscPrefKey.RECONNECTION_COUNT, prefs)
                } else 0

                val host = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)
                val port = getIntPrefValue(OscPrefKey.SSL_PORT, prefs)

                setBooleanPrefValue(true, OscPrefKey.ROOT_STATE, prefs)
                setBooleanPrefValue(true, OscPrefKey.HOME_CONNECTOR, prefs)
                setStringPrefValue(
                    "SoftEther\n$host:$port" + if (assignedIp.isNotEmpty()) "\n$assignedIp" else "",
                    OscPrefKey.HOME_STATUS,
                    prefs
                )
            }

            SoftEtherVpnService.STATE_DISCONNECTED,
            SoftEtherVpnService.STATE_ERROR -> onDown(app, prefs)

            else -> Unit // DISCONNECTING
        }
    }

    private fun onDown(app: Context, prefs: SharedPreferences) {
        if (restarting) return

        if (!userStopped) {
            val stale = !sawProgress && SystemClock.elapsedRealtime() - startedAt < STALE_MS
            if (stale) {
                log("Ignored a disconnect report that belongs to the previous run")
                return
            }

            log("The tunnel ended without the user asking (retries left: $retriesLeft)")
            dumpLogcat("Attempt $attempt")

            if (retriesLeft > 0 && VpnService.prepare(app) == null) {
                retriesLeft--
                retryPending = true

                // The screen shows "connecting" while we wait for the next try.
                setBooleanPrefValue(true, OscPrefKey.HOME_CONNECTOR, prefs)
                setStringPrefValue("", OscPrefKey.HOME_STATUS, prefs)

                val seconds = getIntPrefValue(OscPrefKey.RECONNECTION_INTERVAL, prefs).coerceAtLeast(1)
                handler.postDelayed(retryTask, seconds * 1000L)
                return
            }
        }

        finish(app, prefs)
    }

    private fun finish(app: Context, prefs: SharedPreferences) {
        log("SoftEther session finished")
        closeLog()

        handler.removeCallbacks(retryTask)
        handler.removeCallbacks(midDumpTask)
        retryPending = false
        userStopped = true

        setActive(app, false)
        setBooleanPrefValue(false, OscPrefKey.ROOT_STATE, prefs)
        setBooleanPrefValue(false, OscPrefKey.HOME_CONNECTOR, prefs)
        setStringPrefValue("", OscPrefKey.HOME_STATUS, prefs)
    }

    // ---- settings -> module config --------------------------------------------------------------

    private fun buildConfig(app: Context, prefs: SharedPreferences): ConnectionConfig {
        val ui = ui(app)

        val customDns = if (getBooleanPrefValue(OscPrefKey.DNS_DO_USE_CUSTOM_SERVER, prefs)) {
            getStringPrefValue(OscPrefKey.DNS_CUSTOM_ADDRESS, prefs).trim()
        } else ""

        var config = ConnectionConfig(
            serverHost = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs),
            serverPort = getIntPrefValue(OscPrefKey.SSL_PORT, prefs),
            username = getStringPrefValue(OscPrefKey.HOME_USERNAME, prefs),
            password = getStringPrefValue(OscPrefKey.HOME_PASSWORD, prefs),
            virtualHub = VPN_GATE_HUB,
            sessionName = "Banafsh",
            routes = routes(app, prefs),
            excludedApps = excludedApps(prefs),
            useTcp = true,
            useUdp = false,
            country = if (ui.getString("server_host", "") == getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)) {
                ui.getString("server_country", "").orEmpty()
            } else "",
            authMethod = AuthMethod.AUTO
        )

        if (customDns.isNotEmpty()) {
            config = config.copy(dnsServer = customDns, secondaryDnsServer = "")
        }

        return config
    }

    // Same rule as the SSTP tunnel: Iranian apps and the apps the user picked connect directly.
    private fun excludedApps(prefs: SharedPreferences): List<String> {
        if (!prefs.getBoolean(IranApps.KEY_ENABLED, true)) return emptyList()

        val packages = HashSet<String>()
        prefs.getStringSet(IranApps.KEY_LIST, null)?.let { packages.addAll(it) }
        packages.addAll(getSetPrefValue(OscPrefKey.ROUTE_SELECTED_APPS, prefs))

        return packages.toList()
    }

    // Experimental "Iranian sites connect directly" switch: route everything except those ranges.
    private fun routes(app: Context, prefs: SharedPreferences): List<Route> {
        val everything = listOf(Route("0.0.0.0", 0))

        if (!prefs.getBoolean(IranRanges.KEY_ENABLED, false)) return everything

        val ranges = IranRanges.load(app)
        if (ranges.isEmpty()) return everything

        return try {
            val routes = IranRanges.complementCidrs(ranges)
            if (routes.size > IranRanges.MAX_ROUTES) everything
            else routes.map { (address, length) -> Route(IranRanges.format(address), length) }
        } catch (_: Exception) {
            everything
        }
    }
}
