package kittoku.osc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kittoku.osc.activity.MainActivity
import kittoku.osc.activity.ServerCatalog
import kittoku.osc.activity.ServerListActivity
import kittoku.osc.preference.OscPrefKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket

/** Local-only fixtures: the DNS name is deliberately unresolvable and the listener never answers TLS. */
@RunWith(AndroidJUnit4::class)
class ServerChoiceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val ui = context.getSharedPreferences("banafsh_ui", Context.MODE_PRIVATE)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val protocol = ProtocolSwitch.get(context)
    private val previousCache = ui.getString("servers_cache_sstp", null)
    private val previousCacheTime = ui.getLong("servers_cache_sstp_updated", 0L)

    @Before fun setup() {
        inst.setInTouchMode(false)
        ProtocolSwitch.set(context, Protocol.SSTP)
    }

    @After fun restoreCatalog() {
        ProtocolSwitch.set(context, protocol)
        ui.edit().putString("servers_cache_sstp", previousCache).putLong("servers_cache_sstp_updated", previousCacheTime).commit()
    }

    private fun seed(port: Int, count: Int) {
        val text = (0 until count).joinToString(prefix = "[", postfix = "]") { i ->
            """{"host":"fixture-$i.invalid","ip":"127.0.0.1","port":$port,"country":"NL","ping":99}"""
        }
        assertEquals(count, ServerCatalog(context, Protocol.SSTP).cacheResponse(text).size)
    }

    private fun await(message: String, timeoutMs: Long = 5000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        do {
            var ok = false
            inst.runOnMainSync { ok = predicate() }
            if (ok) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < end)
        fail(message)
    }

    private fun key(code: Int = KeyEvent.KEYCODE_DPAD_CENTER) {
        inst.sendKeyDownUpSync(code)
        inst.waitForIdleSync()
    }

    private fun openCountry(picker: ServerListActivity) {
        await("Country never focused") { picker.hasWindowFocus() && picker.currentFocus?.tag == "NL" }
        key()
        await("Remote did not land on a server during the test") {
            picker.currentFocus?.tag is Int && !picker.findViewById<View>(R.id.btnTestAll).isEnabled
        }
    }

    @Test fun silentCountryLeavesTestingAndOkReturnsServerWithoutInventingAPing() {
        ServerSocket(0, 128, loopback).use { peer ->
            seed(peer.localPort, 80) // more than can finish before the country deadline
            ActivityScenario.launchActivityForResult<ServerListActivity>(Intent(context, ServerListActivity::class.java)).use { scenario ->
                lateinit var picker: ServerListActivity
                scenario.onActivity { picker = it }
                openCountry(picker)
                await("Country test did not end with queued servers", 16_000) {
                    picker.findViewById<View>(R.id.btnTestAll).isEnabled &&
                        (picker.findViewById<LinearLayout>(R.id.listServers).getChildAt(0)
                            .findViewById<TextView>(R.id.tvPing)?.text == picker.getString(R.string.tv_not_tested))
                }
                key()
                val result = scenario.result
                assertEquals(Activity.RESULT_OK, result.resultCode)
                val data = requireNotNull(result.resultData)
                assertEquals("fixture-0.invalid", data.getStringExtra(ServerListActivity.EXTRA_HOST))
                assertEquals("127.0.0.1", data.getStringExtra(ServerListActivity.EXTRA_IP))
                assertEquals(0, data.getIntExtra(ServerListActivity.EXTRA_PING, -1))
            }
        }
    }

    private fun appOp(mode: String) {
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
            "appops set ${context.packageName} ACTIVATE_VPN $mode"
        )).bufferedReader().use { it.readText() }
    }

    @Test fun oneOkDuringSilentProbeStartsRealSstpSocketToSameIp() {
        // Opt-in on a disposable TV AVD only. Consent is pre-authorized there; no public peer or TUN is used.
        assumeTrue(InstrumentationRegistry.getArguments().getString("loopbackTunnel") == "true")
        appOp("allow")
        try {
            assertNull("VPN not prepared on the test AVD", VpnService.prepare(context))
            ui.edit().putBoolean("onboarding_done", true).putBoolean("hide_welcome", true)
                .putString("last_seen_version", BuildConfig.VERSION_NAME).putBoolean("reconnect_default_v1", true).commit()
            prefs.edit().putBoolean(OscPrefKey.RECONNECTION_ENABLED.name, false).commit()
            ServerSocket(0, 8, loopback).use { peer ->
                peer.soTimeout = 8000
                seed(peer.localPort, 1)
                ActivityScenario.launch(MainActivity::class.java).use { scene ->
                    val monitor = inst.addMonitor(ServerListActivity::class.java.name, null, false)
                    try {
                        var rtl = false
                        scene.onActivity { rtl = it.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL }
                        key(if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                        key()
                        val picker = inst.waitForMonitorWithTimeout(monitor, 5000) as ServerListActivity
                        openCountry(picker)
                        peer.accept().use { _ -> // probe is connected to our fixture, still waiting for TLS
                            key()
                            peer.accept().use { tunnel ->
                                tunnel.soTimeout = 4000
                                assertEquals("Expected a TLS handshake from the VPN service", 22, tunnel.getInputStream().read())
                            }
                        }
                        assertEquals("fixture-0.invalid", prefs.getString(OscPrefKey.HOME_HOSTNAME.name, null))
                        assertEquals(0, ui.getInt("server_ping", -1))
                    } finally {
                        inst.removeMonitor(monitor)
                    }
                }
            }
        } finally {
            context.stopService(Intent(context, kittoku.osc.service.SstpVpnService::class.java))
            CatalogSelection(context).clear()
            prefs.edit().remove(OscPrefKey.HOME_HOSTNAME.name).remove(OscPrefKey.RECONNECTION_ENABLED.name).commit()
            appOp("default")
        }
    }
}
