package kittoku.osc

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.SstpVpnService


/** The tunnel engines behind the protocols. */
internal enum class Engine { SSTP, SOFTETHER, L2TP }

/** The ways the app can build a tunnel. The choice lives in the "banafsh_ui" preferences. */
internal enum class Protocol(val id: String, val engine: Engine, val nameRes: Int, val descRes: Int) {
    SSTP("sstp", Engine.SSTP, R.string.bs_protocol_sstp, R.string.bs_protocol_sstp_desc),
    SOFTETHER_TCP("softether_tcp", Engine.SOFTETHER, R.string.bs_protocol_softether_tcp, R.string.bs_protocol_softether_tcp_desc),
    SOFTETHER_UDP("softether_udp", Engine.SOFTETHER, R.string.bs_protocol_softether_udp, R.string.bs_protocol_softether_udp_desc),
    L2TP("l2tp", Engine.L2TP, R.string.bs_protocol_l2tp, R.string.bs_protocol_l2tp_desc);

    companion object {
        // "softether" is what the first version saved: it was the TCP mode.
        fun fromId(id: String?): Protocol = when (id) {
            "softether" -> SOFTETHER_TCP
            else -> values().firstOrNull { it.id == id } ?: SSTP
        }
    }
}

/** Starts and stops the tunnel with whichever protocol is selected. Used by the home screen and the tile. */
internal object ProtocolSwitch {
    private const val KEY_PROTOCOL = "protocol"
    private const val SWITCH_DELAY_MS = 2000L

    private val handler = Handler(Looper.getMainLooper())

    private fun ui(context: Context) =
        context.applicationContext.getSharedPreferences("banafsh_ui", Context.MODE_PRIVATE)

    fun get(context: Context): Protocol = Protocol.fromId(ui(context).getString(KEY_PROTOCOL, null))

    fun set(context: Context, protocol: Protocol) {
        ui(context).edit().putString(KEY_PROTOCOL, protocol.id).apply()
    }

    private fun running(engine: Engine): Boolean = when (engine) {
        Engine.SSTP -> SstpVpnService.isRunning
        Engine.SOFTETHER -> SoftEtherBridge.isRunning()
        Engine.L2TP -> L2tpBridge.isRunning()
    }

    private fun stop(app: Context, engine: Engine) {
        when (engine) {
            Engine.SSTP -> stopSstp(app)
            Engine.SOFTETHER -> SoftEtherBridge.disconnect(app)
            Engine.L2TP -> L2tpBridge.disconnect(app)
        }
    }

    fun isAnyRunning(): Boolean = Engine.values().any { running(it) }

    /** Forgets the saved "this protocol is active" flags (the app was killed while connected). */
    fun clearActive(context: Context) {
        SoftEtherBridge.clearActive(context)
        L2tpBridge.clearActive(context)
    }

    fun connect(context: Context) {
        val app = context.applicationContext
        val protocol = get(app)

        // Another engine may still hold the tunnel (for example a server was picked while it was on).
        val others = Engine.values().filter { it != protocol.engine && running(it) }

        handler.removeCallbacksAndMessages(null)

        if (others.isNotEmpty()) {
            others.forEach { stop(app, it) }
            handler.postDelayed({ start(app, protocol) }, SWITCH_DELAY_MS)
        } else {
            start(app, protocol)
        }
    }

    fun disconnect(context: Context) {
        val app = context.applicationContext

        handler.removeCallbacksAndMessages(null)

        var any = false
        for (engine in Engine.values()) {
            if (running(engine)) {
                any = true
                stop(app, engine)
            }
        }

        // Same as before when nothing runs: SSTP clears its own saved state.
        if (!any) {
            stopSstp(app)
        }
    }

    private fun start(app: Context, protocol: Protocol) {
        when (protocol.engine) {
            Engine.SSTP -> ContextCompat.startForegroundService(
                app,
                Intent(app, SstpVpnService::class.java).setAction(ACTION_VPN_CONNECT)
            )

            Engine.SOFTETHER -> SoftEtherBridge.connect(app)
            Engine.L2TP -> L2tpBridge.connect(app)
        }
    }

    private fun stopSstp(app: Context) {
        app.startService(Intent(app, SstpVpnService::class.java).setAction(ACTION_VPN_DISCONNECT))
    }
}
