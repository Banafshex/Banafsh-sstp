package kittoku.osc.activity

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.preference.PreferenceManager
import kittoku.osc.ProtocolSwitch
import kittoku.osc.SoftEtherBridge
import kittoku.osc.SpeedProbe
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setIntPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue


/**
 * Picks the best server by really using it: connect with the selected protocol, download a small
 * sample through the tunnel, disconnect, next server. The user's own server settings are saved first
 * and put back at the end (or when the run is stopped).
 */
internal class AutoPicker(private val context: Context, private val ui: Ui) {

    interface Ui {
        fun onProgress(percent: Int, step: Step, current: Int, total: Int)
        fun onRowState(index: Int, state: Int, mbps: Double)
        fun onFinished(bestIndex: Int)
        fun onStopped()
    }

    enum class Step { CONNECTING, MEASURING, DISCONNECTING }

    class Candidate(val index: Int, val server: FreeServer, val ping: Int)

    private enum class Stage { IDLE, CONNECTING, SETTLING, PROBING, DISCONNECTING }

    private class Saved(
        val host: String,
        val port: Int,
        val user: String,
        val pass: String,
        val verify: Boolean,
        val uiHost: String?,
        val uiCountry: String?,
    )

    companion object {
        const val ROW_IDLE = 0
        const val ROW_TESTING = 1
        const val ROW_DONE = 2
        const val ROW_FAILED = 3

        const val MAX_CANDIDATES = 8

        // About 350 KB per server: 8 servers stay near 3 MB in total.
        private const val PROBE_BYTES = 350_000
        private const val PROBE_MAX_MS = 4000L

        private const val CONNECT_TIMEOUT_MS = 18_000L
        private const val GIVE_UP_CHECK_MS = 4_000L
        private const val DISCONNECT_TIMEOUT_MS = 6_000L
        private const val SETTLE_MS = 500L
        private const val POLL_MS = 250L

        // Speeds within 15% of the best count as equal: then the lower ping wins.
        private const val NEAR_BEST = 0.85
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val uiPrefs: SharedPreferences = context.getSharedPreferences("banafsh_ui", Context.MODE_PRIVATE)

    private var token = 0
    private var stage = Stage.IDLE
    private var stageStartedAt = 0L

    private var queue: List<Candidate> = emptyList()
    private var position = 0
    private val speeds = HashMap<Int, Double>()
    private var saved: Saved? = null

    var running = false
        private set

    fun start(candidates: List<Candidate>) {
        if (running || candidates.isEmpty()) return

        queue = candidates
        position = 0
        speeds.clear()
        running = true
        token++

        saved = Saved(
            host = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs),
            port = getIntPrefValue(OscPrefKey.SSL_PORT, prefs),
            user = getStringPrefValue(OscPrefKey.HOME_USERNAME, prefs),
            pass = getStringPrefValue(OscPrefKey.HOME_PASSWORD, prefs),
            verify = getBooleanPrefValue(OscPrefKey.SSL_DO_VERIFY, prefs),
            uiHost = uiPrefs.getString("server_host", null),
            uiCountry = uiPrefs.getString("server_country", null),
        )

        // Eight test sessions would leave eight log files.
        SoftEtherBridge.suppressLog = true

        beginServer()
    }

    /** Stops the run. [onDone] runs when the test tunnel is gone (or after a short wait). */
    fun cancel(onDone: (() -> Unit)? = null) {
        if (!running) {
            onDone?.invoke()
            return
        }

        val current = queue.getOrNull(position)

        running = false
        token++
        stage = Stage.IDLE
        handler.removeCallbacksAndMessages(null)

        if (current != null) {
            ui.onRowState(current.index, ROW_IDLE, 0.0)
        }

        ProtocolSwitch.disconnect(context)
        restore()
        SoftEtherBridge.suppressLog = false
        ui.onStopped()

        val startedAt = SystemClock.elapsedRealtime()
        val waitDown = object : Runnable {
            override fun run() {
                val down = !getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)

                if (down || SystemClock.elapsedRealtime() - startedAt > DISCONNECT_TIMEOUT_MS) {
                    onDone?.invoke()
                } else {
                    handler.postDelayed(this, POLL_MS)
                }
            }
        }
        handler.post(waitDown)
    }

    // ---- one server at a time -------------------------------------------------------------

    private fun beginServer() {
        if (!running) return

        if (position >= queue.size) {
            finish()
            return
        }

        val candidate = queue[position]
        val myToken = token

        ui.onRowState(candidate.index, ROW_TESTING, 0.0)
        apply(candidate.server)

        stage = Stage.CONNECTING
        stageStartedAt = SystemClock.elapsedRealtime()
        ProtocolSwitch.connect(context)

        handler.postDelayed({ poll(myToken) }, POLL_MS)
    }

    private fun poll(myToken: Int) {
        if (myToken != token || !running) return

        val elapsed = SystemClock.elapsedRealtime() - stageStartedAt

        when (stage) {
            Stage.CONNECTING -> {
                if (isTunnelUp()) {
                    stage = Stage.SETTLING
                    stageStartedAt = SystemClock.elapsedRealtime()
                    handler.postDelayed({ startProbe(myToken) }, SETTLE_MS)
                } else if (elapsed > CONNECT_TIMEOUT_MS) {
                    serverFailed()
                } else if (elapsed > GIVE_UP_CHECK_MS && !getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)) {
                    // The tunnel service gave up by itself (wrong server, refused): no need to wait for the timeout.
                    serverFailed()
                }
            }

            Stage.DISCONNECTING -> {
                if (!getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs) || elapsed > DISCONNECT_TIMEOUT_MS) {
                    position++
                    beginServer()
                    return
                }
            }

            else -> Unit
        }

        report()
        handler.postDelayed({ poll(myToken) }, POLL_MS)
    }

    private fun isTunnelUp(): Boolean {
        return getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs) &&
            getStringPrefValue(OscPrefKey.HOME_STATUS, prefs).isNotEmpty()
    }

    private fun startProbe(myToken: Int) {
        if (myToken != token || !running) return

        stage = Stage.PROBING
        stageStartedAt = SystemClock.elapsedRealtime()

        Thread {
            val mbps = SpeedProbe.measure(PROBE_BYTES, PROBE_MAX_MS) { myToken != token || !running }

            handler.post { probeDone(myToken, mbps) }
        }.start()
    }

    private fun probeDone(myToken: Int, mbps: Double) {
        if (myToken != token || !running) return

        val candidate = queue[position]

        if (mbps > 0.0) {
            speeds[candidate.index] = mbps
            ui.onRowState(candidate.index, ROW_DONE, mbps)
        } else {
            ui.onRowState(candidate.index, ROW_FAILED, 0.0)
        }

        beginDisconnect()
    }

    private fun serverFailed() {
        ui.onRowState(queue[position].index, ROW_FAILED, 0.0)
        beginDisconnect()
    }

    private fun beginDisconnect() {
        stage = Stage.DISCONNECTING
        stageStartedAt = SystemClock.elapsedRealtime()
        ProtocolSwitch.disconnect(context)
    }

    // ---- result -----------------------------------------------------------------------------

    private fun finish() {
        running = false
        stage = Stage.IDLE
        token++

        restore()
        SoftEtherBridge.suppressLog = false

        ui.onFinished(pickBest())
    }

    /** Highest real speed; among nearly equal speeds, the lowest ping. -1 when no server was measured. */
    private fun pickBest(): Int {
        val measured = queue.filter { (speeds[it.index] ?: 0.0) > 0.0 }
        if (measured.isEmpty()) return -1

        val top = measured.maxOf { speeds.getValue(it.index) }

        return measured
            .filter { speeds.getValue(it.index) >= top * NEAR_BEST }
            .minByOrNull { it.ping }!!
            .index
    }

    // ---- progress ---------------------------------------------------------------------------

    private fun report() {
        val elapsed = (SystemClock.elapsedRealtime() - stageStartedAt).toDouble()

        // Each server owns an equal part of the bar; inside it the bar moves with the time of each step.
        val (from, to, expected, step) = when (stage) {
            Stage.CONNECTING -> Quad(0.0, 0.45, 7000.0, Step.CONNECTING)
            Stage.SETTLING, Stage.PROBING -> Quad(0.45, 0.85, 4500.0, Step.MEASURING)
            Stage.DISCONNECTING -> Quad(0.85, 1.0, 2500.0, Step.DISCONNECTING)
            Stage.IDLE -> return
        }

        val inside = from + (to - from) * (elapsed / expected).coerceIn(0.0, 1.0)
        val percent = (((position + inside) / queue.size) * 100).toInt().coerceIn(0, 100)

        ui.onProgress(percent, step, position + 1, queue.size)
    }

    private data class Quad(val from: Double, val to: Double, val expected: Double, val step: Step)

    // ---- the user's own settings -------------------------------------------------------------

    private fun apply(server: FreeServer) {
        setStringPrefValue(server.host, OscPrefKey.HOME_HOSTNAME, prefs)
        setIntPrefValue(server.port, OscPrefKey.SSL_PORT, prefs)
        setStringPrefValue("vpn", OscPrefKey.HOME_USERNAME, prefs)
        setStringPrefValue("vpn", OscPrefKey.HOME_PASSWORD, prefs)
        setBooleanPrefValue(false, OscPrefKey.SSL_DO_VERIFY, prefs)

        uiPrefs.edit()
            .putString("server_host", server.host)
            .putString("server_country", server.countryCode)
            .apply()
    }

    private fun restore() {
        val old = saved ?: return
        saved = null

        setStringPrefValue(old.host, OscPrefKey.HOME_HOSTNAME, prefs)
        setIntPrefValue(old.port, OscPrefKey.SSL_PORT, prefs)
        setStringPrefValue(old.user, OscPrefKey.HOME_USERNAME, prefs)
        setStringPrefValue(old.pass, OscPrefKey.HOME_PASSWORD, prefs)
        setBooleanPrefValue(old.verify, OscPrefKey.SSL_DO_VERIFY, prefs)

        uiPrefs.edit().also {
            if (old.uiHost != null) it.putString("server_host", old.uiHost) else it.remove("server_host")
            if (old.uiCountry != null) it.putString("server_country", old.uiCountry) else it.remove("server_country")
        }.apply()
    }
}
