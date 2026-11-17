package kittoku.osc.l2tp

import android.app.PendingIntent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.TunInterface
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.TunParameters
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.TunProvider
import com.arcansecurity.vpn.l2tpipsec.core.util.Log
import com.arcansecurity.vpn.l2tpipsec.core.util.VpnLogger
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kittoku.osc.preference.AppRouting

/**
 * Routes IPv4 through the tunnel, subject only to the user-selected app rule.
 *
 * IPv6 is blackholed (a route without an address) so a dual-stack connection cannot leak around an IPv4-only tunnel.
 */
internal class L2tpTunProvider(
    private val service: VpnService,
    private val appRouting: AppRouting,
    private val onEstablished: (ParcelFileDescriptor) -> Unit,
    private val configureIntent: PendingIntent?,
    private val logger: VpnLogger,
) : TunProvider {

    private val log = Log(TAG, logger)

    override fun establish(params: TunParameters): TunInterface? {
        val builder = service.Builder()
            .setSession("Banafsh")
            .setMtu(params.mtu)
            .addAddress(params.address, params.prefixLength)
            .addRoute("0.0.0.0", 0)

        params.dnsServers.forEach { dns ->
            runCatching { builder.addDnsServer(dns) }
                .onFailure { log.w("Rejecting unusable DNS server '$dns'", it) }
        }
        params.searchDomain?.takeIf { it.isNotBlank() }?.let { domain ->
            runCatching { builder.addSearchDomain(domain) }
                .onFailure { log.w("Rejecting unusable search domain '$domain'", it) }
        }

        if (params.blockIpv6) {
            runCatching { builder.addRoute("::", 0) }
                .onFailure { log.w("Could not install the IPv6 blackhole route", it) }
        }

        if (!appRouting.applyTo(builder)) {
            log.w("No selected VPN app is installed; not establishing an unrestricted tunnel")
            return null
        }

        builder.setBlocking(true)
        configureIntent?.let { builder.setConfigureIntent(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            builder.setUnderlyingNetworks(null)
        }

        val descriptor = builder.establish()
        if (descriptor == null) {
            log.w("VpnService.Builder.establish() returned null: consent was revoked")
            return null
        }

        log.i(
            "TUN up: ${params.address}/${params.prefixLength} mtu=${params.mtu} " +
                "dns=${params.dnsServers.joinToString(",").ifEmpty { "(none)" }} " +
                "appRouting=${appRouting.mode} " +
                "ipv6=${if (params.blockIpv6) "blocked" else "untouched"}",
        )
        onEstablished(descriptor)
        return ParcelFileDescriptorTun(descriptor)
    }

    private companion object {
        const val TAG = "Tun"
    }
}

/** [TunInterface] over the descriptor `VpnService.Builder.establish()` produced. */
private class ParcelFileDescriptorTun(
    private val descriptor: ParcelFileDescriptor,
) : TunInterface {

    private val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)
    private val closed = AtomicBoolean(false)

    override fun readPacket(buffer: ByteArray): Int {
        if (closed.get()) return -1
        return try {
            input.read(buffer)
        } catch (e: IOException) {
            // Closing the descriptor from another thread is how a blocking read is cancelled.
            if (closed.get()) -1 else throw e
        }
    }

    override fun writePacket(buffer: ByteArray, offset: Int, length: Int) {
        if (closed.get()) return
        output.write(buffer, offset, length)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { descriptor.close() }
    }
}
