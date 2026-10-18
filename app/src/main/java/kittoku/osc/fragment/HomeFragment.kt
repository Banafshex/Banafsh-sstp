package kittoku.osc.fragment

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import kittoku.osc.IranApps
import kittoku.osc.R
import kittoku.osc.activity.MainActivity
import kittoku.osc.flagEmoji
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getIntPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue

internal class HomeFragment : Fragment(R.layout.fragment_home) {
    private lateinit var prefs: SharedPreferences
    private val anims = mutableListOf<Animator>()
    private var lastState = -1

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        view?.post { refresh() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        view.findViewById<View>(R.id.btnConnect).setOnClickListener {
            (activity as? MainActivity)?.toggleConnection()
        }
        view.findViewById<View>(R.id.cardServer).setOnClickListener {
            (activity as? MainActivity)?.showServerDialog()
        }
        view.findViewById<View>(R.id.btnFree).setOnClickListener {
            (activity as? MainActivity)?.showFreeConfig()
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
        lastState = -1
        refresh()
    }

    override fun onStop() {
        super.onStop()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        stopAnimations()
    }

    private fun refresh() {
        val v = view ?: return
        val ctx = context ?: return

        val on = getBooleanPrefValue(OscPrefKey.HOME_CONNECTOR, prefs)
        val established = getStringPrefValue(OscPrefKey.HOME_STATUS, prefs).isNotEmpty()
        val state = when {
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

        val purple = ContextCompat.getColor(ctx, R.color.banafsh_purple)
        val cyan = ContextCompat.getColor(ctx, R.color.banafsh_cyan)
        val mute = ContextCompat.getColor(ctx, R.color.banafsh_mute)

        v.findViewById<TextView>(R.id.tvStatus).setText(
            when (state) {
                2 -> R.string.bs_status_connected
                1 -> R.string.bs_status_connecting
                else -> R.string.bs_status_disconnected
            }
        )
        v.findViewById<TextView>(R.id.tvHint).setText(
            when (state) {
                2 -> R.string.bs_hint_connected
                1 -> R.string.bs_hint_connecting
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
        btn.setBackgroundResource(if (state == 0) R.drawable.bg_connect_idle else R.drawable.bg_connect_active)
        val fg = if (state == 0) 0xFFFFFFFF.toInt() else purple
        label.setTextColor(fg)
        icon.setColorFilter(fg, PorterDuff.Mode.SRC_IN)
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
            if (state == 2 && previous == 1) playPop()
        }
    }

    private fun startConnecting() {
        val v = view ?: return
        val ring = v.findViewById<View>(R.id.ring)
        ring.visibility = View.VISIBLE
        ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f).apply {
            duration = 1100
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
            anims.add(this)
        }
        listOf(R.id.ripple1, R.id.ripple2, R.id.ripple3).forEachIndexed { i, id ->
            val r = v.findViewById<View>(id)
            r.visibility = View.VISIBLE
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2400
                startDelay = i * 800L
                interpolator = DecelerateInterpolator()
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    r.scaleX = 0.7f + 1.05f * f
                    r.scaleY = r.scaleX
                    r.alpha = 0.9f * (1f - f)
                }
                start()
                anims.add(this)
            }
        }
    }

    private fun stopAnimations() {
        anims.forEach { it.cancel() }
        anims.clear()
        val v = view ?: return
        v.findViewById<View>(R.id.ring).visibility = View.INVISIBLE
        listOf(R.id.ripple1, R.id.ripple2, R.id.ripple3).forEach {
            v.findViewById<View>(it).apply {
                alpha = 0f
                visibility = View.INVISIBLE
            }
        }
    }

    private fun playPop() {
        val v = view ?: return
        val btn = v.findViewById<View>(R.id.btnConnect)
        ObjectAnimator.ofPropertyValuesHolder(
            btn,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.14f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.14f, 1f)
        ).apply {
            duration = 650
            interpolator = OvershootInterpolator()
            start()
        }

        val burst = v.findViewById<View>(R.id.burst)
        burst.visibility = View.VISIBLE
        ObjectAnimator.ofPropertyValuesHolder(
            burst,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.8f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.8f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f)
        ).apply {
            duration = 800
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    burst.visibility = View.INVISIBLE
                }
            })
            start()
        }
    }
}
