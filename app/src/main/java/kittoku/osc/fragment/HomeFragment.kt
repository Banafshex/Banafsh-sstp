package kittoku.osc.fragment

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.graphics.PorterDuff
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.appcompat.app.AppCompatActivity
import kittoku.osc.IranApps
import kittoku.osc.IranRanges
import kittoku.osc.NewsChecker
import kittoku.osc.NewsItem
import kittoku.osc.R
import kittoku.osc.activity.BypassAppsActivity
import kittoku.osc.activity.MainActivity
import kittoku.osc.activity.NetworkView
import kittoku.osc.activity.UpdateUi
import kittoku.osc.flagEmoji
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.service.SstpVpnService
import kittoku.osc.service.PREF_WAITING_NETWORK

internal class HomeFragment : Fragment(R.layout.fragment_home) {
    private lateinit var prefs: SharedPreferences
    private val anims = mutableListOf<Animator>()
    private var lastState = -1

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        view?.post { refresh() }
    }

    private var shortcuts: ShortcutsCard? = null

    private fun network(): NetworkView? = activity?.findViewById(R.id.networkView)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        network()?.anchor = view.findViewById(R.id.btnConnect)
        shortcuts = ShortcutsCard(this, view)

        view.findViewById<View>(R.id.btnConnect).setOnClickListener {
            (activity as? MainActivity)?.toggleConnection()
        }
        view.findViewById<View>(R.id.cardServer).setOnClickListener {
            (activity as? MainActivity)?.showServerDialog()
        }
        view.findViewById<View>(R.id.btnFree).setOnClickListener {
            (activity as? MainActivity)?.showFreeConfig()
        }
        view.findViewById<View>(R.id.btnManageApps).setOnClickListener {
            startActivity(Intent(requireContext(), BypassAppsActivity::class.java))
        }
        view.findViewById<SwitchMaterial>(R.id.swSites).setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(IranRanges.KEY_ENABLED, checked).apply()

            if (getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)) {
                Toast.makeText(requireContext(), R.string.bs_nv_sites_next, Toast.LENGTH_LONG).show()
            }
        }
        view.findViewById<SwitchMaterial>(R.id.swBypass).setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(IranApps.KEY_ENABLED, checked).apply()

            if (getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)) {
                Toast.makeText(requireContext(), R.string.bs_bypass_reconnect, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        // If the app was killed while connected, the saved "connected" state is stale: clear it.
        if (getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs) && !SstpVpnService.isRunning) {
            setBooleanPrefValue(false, OscPrefKey.HOME_CONNECTOR, prefs)
            setStringPrefValue("", OscPrefKey.HOME_STATUS, prefs)
        }

        lastState = -1
        network()?.setActive(true)
        shortcuts?.refresh()
        refresh()
    }

    override fun onStop() {
        super.onStop()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        network()?.setActive(false)
        stopAnimations()
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

        v.findViewById<SwitchMaterial>(R.id.swBypass).apply {
            val enabled = prefs.getBoolean(IranApps.KEY_ENABLED, true)
            if (isChecked != enabled) {
                isChecked = enabled
            }
        }

        v.findViewById<SwitchMaterial>(R.id.swSites).apply {
            val enabled = prefs.getBoolean(IranRanges.KEY_ENABLED, false)
            if (isChecked != enabled) {
                isChecked = enabled
            }
        }

        val purple = ContextCompat.getColor(ctx, R.color.banafsh_purple)
        val cyan = ContextCompat.getColor(ctx, R.color.banafsh_cyan)
        val mute = ContextCompat.getColor(ctx, R.color.banafsh_mute)

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
        v.findViewById<View>(R.id.dot).background.mutate().setTint(if (state == 0) mute else if (state == 1) purple else cyan)

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
            if (state == 0) R.drawable.bg_ring else R.drawable.bg_ring_live
        )
        label.setTextColor(0xFFF4F3FF.toInt())
        icon.setColorFilter(if (state == 0) 0xFFC9B6FF.toInt() else cyan, PorterDuff.Mode.SRC_IN)
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
        v.findViewById<TextView>(R.id.tvAddress).text =
            if (host.isEmpty()) getString(R.string.bs_no_server) else "$host:$port"

        if (state != lastState) {
            val previous = lastState
            lastState = state
            stopAnimations()
            if (state == 1) startConnecting()
            network()?.setMode(
                when (state) {
                    1 -> NetworkView.Mode.CONNECTING
                    2 -> NetworkView.Mode.CONNECTED
                    else -> NetworkView.Mode.IDLE
                },
                celebrate = state == 2 && previous == 1
            )
        }
    }

    // While connecting, two thin waves leave the button and fade out before they reach the outer ring.
    private fun startConnecting() {
        val v = view ?: return
        listOf(R.id.wave1 to 0L, R.id.wave2 to 900L).forEach { (id, delay) ->
            val wave = v.findViewById<View>(id)
            wave.alpha = 0f
            ObjectAnimator.ofPropertyValuesHolder(
                wave,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.36f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.36f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 0f)
            ).apply {
                duration = 1800
                startDelay = delay
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
                anims.add(this)
            }
        }
    }

    private fun stopAnimations() {
        anims.forEach { it.cancel() }
        anims.clear()
        listOf(R.id.wave1, R.id.wave2).forEach { id ->
            view?.findViewById<View>(id)?.apply {
                scaleX = 1f
                scaleY = 1f
                alpha = 0f
            }
        }
    }
}
