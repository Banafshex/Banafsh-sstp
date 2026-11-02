package kittoku.osc

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.SstpVpnService


/** The two ways the app can build a tunnel. The choice lives in the "banafsh_ui" preferences. */
internal enum class Protocol(val id: String, val nameRes: Int, val descRes: Int) {
    SSTP("sstp", R.string.bs_protocol_sstp, R.string.bs_protocol_sstp_desc),
    SOFTETHER_TCP("softether_tcp", R.string.bs_protocol_softether_tcp, R.string.bs_protocol_softether_tcp_desc),
    SOFTETHER_UDP("softether_udp", R.string.bs_protocol_softether_udp, R.string.bs_protocol_softether_udp_desc);

    val isSoftEther: Boolean get() = this != SSTP

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

    fun isAnyRunning(): Boolean = SstpVpnService.isRunning || SoftEtherBridge.isRunning()

    fun connect(context: Context) {
        val app = context.applicationContext
        val protocol = get(app)

        // The other protocol may still hold the tunnel (for example a server was picked while it was on).
        val otherRunning = if (protocol.isSoftEther) SstpVpnService.isRunning else SoftEtherBridge.isRunning()

        handler.removeCallbacksAndMessages(null)

        if (otherRunning) {
            if (protocol.isSoftEther) stopSstp(app) else SoftEtherBridge.disconnect(app)
            handler.postDelayed({ start(app, protocol) }, SWITCH_DELAY_MS)
        } else {
            start(app, protocol)
        }
    }

    fun disconnect(context: Context) {
        val app = context.applicationContext

        handler.removeCallbacksAndMessages(null)

        val softEtherRunning = SoftEtherBridge.isRunning()
        if (softEtherRunning) {
            SoftEtherBridge.disconnect(app)
        }

        // Same as before when nothing runs: SSTP clears its own saved state.
        if (SstpVpnService.isRunning || !softEtherRunning) {
            stopSstp(app)
        }
    }

    private fun start(app: Context, protocol: Protocol) {
        if (protocol.isSoftEther) {
            SoftEtherBridge.connect(app)
        } else {
            ContextCompat.startForegroundService(
                app,
                Intent(app, SstpVpnService::class.java).setAction(ACTION_VPN_CONNECT)
            )
        }
    }

    private fun stopSstp(app: Context) {
        app.startService(Intent(app, SstpVpnService::class.java).setAction(ACTION_VPN_DISCONNECT))
    }
}
