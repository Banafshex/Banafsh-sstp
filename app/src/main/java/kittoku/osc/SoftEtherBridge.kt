package kittoku.osc

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.getSetPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import vn.unlimit.softether.SoftEtherVpnService
import vn.unlimit.softether.model.AuthMethod
import vn.unlimit.softether.model.ConnectionConfig
import vn.unlimit.softether.model.Route


/**
 * Connects the SoftEther module to the same screens SSTP uses.
 * It writes the same preferences the home screen and the tile already watch
 * (ROOT_STATE, HOME_CONNECTOR, HOME_STATUS) and repeats the connection after a drop,
 * using the reconnection settings of the app.
 */
internal object SoftEtherBridge : SoftEtherVpnService.StateListener {
    private const val UI_PREFS = "banafsh_ui"
    private const val KEY_ACTIVE = "softether_active"

    // A "disconnected" report this soon after a start, with no progress before it, belongs to the previous run.
    private const val STALE_MS = 1500L
    private const val RESTART_DELAY_MS = 2000L

    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var registered = false

    @Volatile private var userStopped = true
    @Volatile private var restarting = false
    @Volatile private var sawProgress = false
    @Volatile private var startedAt = 0L
    @Volatile private var retryPending = false
    private var retriesLeft = 0

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
            SoftEtherVpnService.addStateListener(this)
        }
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

        if (tunnelIsUp()) {
            // The state report that follows clears the saved state.
            sendDisconnect(app)
        } else {
            finish(app, prefs)
        }
    }

    private fun begin(app: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)

        sawProgress = false
        startedAt = SystemClock.elapsedRealtime()

        ContextCompat.startForegroundService(
            app,
            Intent(app, SoftEtherVpnService::class.java)
                .setAction(SoftEtherVpnService.ACTION_CONNECT)
                .putExtra(SoftEtherVpnService.EXTRA_CONFIG, buildConfig(app, prefs))
        )
    }

    private fun sendDisconnect(app: Context) {
        ContextCompat.startForegroundService(
            app,
            Intent(app, SoftEtherVpnService::class.java).setAction(SoftEtherVpnService.ACTION_DISCONNECT)
        )
    }

    // ---- state reports from the module ------------------------------------------------------

    override fun onSoftEtherStateChanged(state: String, assignedIp: String) {
        val app = appContext ?: return

        // The module replays its current state when we register; ignore it unless SoftEther is the chosen tunnel.
        if (!isActive(app)) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(app)

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
            if (stale) return

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
        handler.removeCallbacks(retryTask)
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
