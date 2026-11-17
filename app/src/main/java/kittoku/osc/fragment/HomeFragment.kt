package kittoku.osc.fragment

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.graphics.PorterDuff
import android.net.TrafficStats
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import java.util.Locale
import kittoku.osc.Protocol
import kittoku.osc.ProtocolSwitch
import kittoku.osc.R
import kittoku.osc.activity.MainActivity
import kittoku.osc.flagEmoji
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.service.PREF_WAITING_NETWORK

internal class HomeFragment : Fragment(R.layout.fragment_home) {
    private lateinit var prefs: SharedPreferences
    private lateinit var uiPrefs: SharedPreferences
    private val anims = mutableListOf<Animator>()
    private var lastState = -1

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        view?.post { refresh() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        uiPrefs = requireContext().getSharedPreferences("banafsh_ui", android.content.Context.MODE_PRIVATE)

        view.findViewById<View>(R.id.btnConnect).setOnClickListener {
            (activity as? MainActivity)?.toggleConnection()
        }
        view.findViewById<View>(R.id.cardServer).setOnClickListener {
            (activity as? MainActivity)?.showFreeConfig()
        }
        view.findViewById<View>(R.id.cardProtocol).setOnClickListener {
            (activity as? MainActivity)?.showProtocolDialog()
        }
        view.findViewById<View>(R.id.cardSettings).setOnClickListener {
            (activity as? MainActivity)?.openSettings()
        }
        val connect = view.findViewById<View>(R.id.btnConnect)
        // D-pad toward the options column always lands on the server picker, in either language.
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        if (rtl) connect.nextFocusLeftId = R.id.cardServer else connect.nextFocusRightId = R.id.cardServer
        for (id in listOf(R.id.cardServer, R.id.cardProtocol, R.id.cardSettings)) {
            val row = view.findViewById<View>(id)
            if (rtl) row.nextFocusRightId = connect.id else row.nextFocusLeftId = connect.id
        }
        if (savedInstanceState == null) connect.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        uiPrefs.registerOnSharedPreferenceChangeListener(listener)
        // If the app was killed while connected, the saved "connected" state is stale: clear it.
        if (getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs) && !ProtocolSwitch.isAnyRunning()) {
            setBooleanPrefValue(false, OscPrefKey.HOME_CONNECTOR, prefs)
            setStringPrefValue("", OscPrefKey.HOME_STATUS, prefs)
            ProtocolSwitch.clearActive(requireContext())
        }

        lastState = -1
        refresh()
    }

    override fun onStop() {
        super.onStop()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        uiPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        stopAnimations()
        stopSpeed()
    }

    private fun refresh() {
        val v = view ?: return
        val ctx = context ?: return

        val on = getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)
        val established = getStringPrefValue(OscPrefKey.HOME_STATUS, prefs).isNotEmpty()
        val waiting = on && prefs.getBoolean(PREF_WAITING_NETWORK, false)
        val state = when {
            waiting -> 1
            on && established -> 2
            on -> 1
            else -> 0
        }

        val accent = ContextCompat.getColor(ctx, R.color.bs_accent)
        val good = ContextCompat.getColor(ctx, R.color.bs_good)
        val aqua = ContextCompat.getColor(ctx, R.color.bs_aqua)
        val accentSoft = ContextCompat.getColor(ctx, R.color.bs_accent_soft)
        val mute = ContextCompat.getColor(ctx, R.color.bs_ink_mute)

        v.findViewById<TextView>(R.id.tvStatus).setText(
            when (state) {
                1 -> if (waiting) R.string.bs_status_no_internet else R.string.bs_status_connecting
                2 -> R.string.bs_status_connected
                else -> R.string.bs_status_disconnected
            }
        )
        v.findViewById<TextView>(R.id.tvHint).setText(
            when (state) {
                1 -> if (waiting) R.string.bs_hint_no_internet else R.string.bs_hint_connecting
                2 -> R.string.bs_hint_connected
                else -> R.string.bs_hint_disconnected
            }
        )
        v.findViewById<View>(R.id.dot).background.mutate().setTint(if (state == 0) mute else if (state == 1) accent else good)

        val btn = v.findViewById<View>(R.id.btnConnect)
        val label = v.findViewById<TextView>(R.id.tvConnect)
        val icon = v.findViewById<ImageView>(R.id.ivPower)
        label.setText(
            when (state) {
                2 -> R.string.bs_disconnect
                1 -> R.string.bs_connecting_cancel
                else -> R.string.bs_connect
            }
        )
        btn.setBackgroundResource(
            when (state) {
                2 -> R.drawable.bg_connect_active
                1 -> R.drawable.bg_connect_connecting
                else -> R.drawable.bg_connect_idle
            }
        )
        v.findViewById<View>(R.id.ring).setBackgroundResource(
            when (state) {
                2 -> R.drawable.bg_ring_live
                1 -> R.drawable.bg_ring_connecting
                else -> R.drawable.bg_ring
            }
        )
        // Violet when idle, light violet while connecting, aqua when connected.
        val tone = when (state) {
            2 -> aqua
            1 -> accentSoft
            else -> accent
        }
        label.setTextColor(tone)
        icon.setColorFilter(tone, PorterDuff.Mode.SRC_IN)
        btn.contentDescription = label.text

        val host = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)
        val port = getIntPrefValue(OscPrefKey.SSL_PORT, prefs)
        val ui = ctx.getSharedPreferences("banafsh_ui", android.content.Context.MODE_PRIVATE)
        val flag = if (host.isNotEmpty() && ui.getString("server_host", "") == host) {
            flagEmoji(ui.getString("server_country", "") ?: "")
        } else ""
        v.findViewById<TextView>(R.id.tvFlag).apply {
            text = flag
            visibility = if (flag.isEmpty()) View.GONE else View.VISIBLE
        }

        val protocol = ProtocolSwitch.get(ctx)

        // L2TP does not use the server's TCP port: only SSTP shows it.
        v.findViewById<TextView>(R.id.tvAddress).text = when {
            host.isEmpty() -> getString(R.string.bs_no_server)
            protocol == Protocol.SSTP -> "$host:$port"
            else -> host
        }

        // The ping measured in the server list when this server was picked.
        val ping = if (host.isNotEmpty() && ui.getString("server_host", "") == host) ui.getInt("server_ping", 0) else 0
        v.findViewById<TextView>(R.id.tvPing).apply {
            if (ping > 0) {
                text = getString(R.string.bs_ping_ms, ping)
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        v.findViewById<TextView>(R.id.tvProtocol).text = protocol.id.uppercase()

        if (state != lastState) {
            lastState = state
            stopAnimations()
            if (state != 0) startRingAnimation(state)

            v.findViewById<View>(R.id.speedPanel).visibility = if (state == 2) View.VISIBLE else View.INVISIBLE

            // Only the change of state moves anything: a soft glow fades in when connected and out otherwise.
            activity?.findViewById<View>(R.id.bgGlow)?.animate()
                ?.alpha(if (state == 2) 1f else 0f)
                ?.setDuration(700)
                ?.start()

            if (state == 2) startSpeed() else stopSpeed()
        }
    }

    // ---- live speed -----------------------------------------------------------------------------
    // The app's own traffic is what the tunnel carries (plus its small overhead), so it tracks the real speed.

    private val speedHandler = Handler(Looper.getMainLooper())
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastAt = 0L

    private val speedTask = object : Runnable {
        override fun run() {
            sampleSpeed()
            speedHandler.postDelayed(this, 1000L)
        }
    }

    private fun startSpeed() {
        stopSpeed()

        val uid = Process.myUid()
        lastRx = TrafficStats.getUidRxBytes(uid)
        lastTx = TrafficStats.getUidTxBytes(uid)
        lastAt = SystemClock.elapsedRealtime()

        showSpeed(0.0, 0.0)
        speedHandler.postDelayed(speedTask, 1000L)
    }

    private fun stopSpeed() {
        speedHandler.removeCallbacks(speedTask)
        view?.findViewById<TextView>(R.id.tvDown)?.text = getString(R.string.bs_speed_none)
        view?.findViewById<TextView>(R.id.tvUp)?.text = getString(R.string.bs_speed_none)
    }

    private fun sampleSpeed() {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        val now = SystemClock.elapsedRealtime()
        val seconds = (now - lastAt) / 1000.0

        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong() || seconds <= 0.0) {
            stopSpeed()
            return
        }

        showSpeed(((rx - lastRx) / seconds).coerceAtLeast(0.0), ((tx - lastTx) / seconds).coerceAtLeast(0.0))

        lastRx = rx
        lastTx = tx
        lastAt = now
    }

    private fun showSpeed(down: Double, up: Double) {
        view?.findViewById<TextView>(R.id.tvDown)?.text = formatSpeed(down)
        view?.findViewById<TextView>(R.id.tvUp)?.text = formatSpeed(up)
    }

    private fun formatSpeed(bytesPerSecond: Double): String {
        return if (bytesPerSecond >= 1024 * 1024) {
            String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024 * 1024))
        } else {
            String.format(Locale.US, "%d KB/s", (bytesPerSecond / 1024).toInt())
        }
    }

    // Connecting: the dashed ring turns and the icon blinks. Connected: the solid ring breathes slowly.
    // They run only while this screen is in front.
    private fun startRingAnimation(state: Int) {
        val v = view ?: return
        val ring = v.findViewById<View>(R.id.ring)
        val icon = v.findViewById<View>(R.id.ivPower)

        if (state == 1) {
            ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f).apply {
                duration = 3000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
                anims.add(this)
            }
            ObjectAnimator.ofFloat(icon, View.ALPHA, 0.5f, 1f).apply {
                duration = 800
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
                anims.add(this)
            }
        } else if (state == 2) {
            ObjectAnimator.ofPropertyValuesHolder(
                ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.04f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 1f)
            ).apply {
                duration = 2000
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
                anims.add(this)
            }
        }
    }

    private fun stopAnimations() {
        anims.forEach { it.cancel() }
        anims.clear()

        view?.findViewById<View>(R.id.ring)?.apply {
            rotation = 0f
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
        }
        view?.findViewById<View>(R.id.ivPower)?.alpha = 1f
    }
}
