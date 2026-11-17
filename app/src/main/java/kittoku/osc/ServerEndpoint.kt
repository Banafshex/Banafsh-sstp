package kittoku.osc

import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The name is still the TLS peer / HTTP Host. Only the socket destination may use the catalog IP. */
internal class ServerEndpoint(val host: String, ip: String? = null) {
    val ip = ip?.takeIf { ipv4Bytes(it) != null }
    val dialHost = this.ip ?: host
}

// Accept only canonical dotted quads in the IP field. In particular, never resolve a second hostname here.
private fun ipv4Bytes(text: String): ByteArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    return ByteArray(4) { i ->
        val part = parts[i]
        if (part.length !in 1..3 || (part.length > 1 && part[0] == '0') || part.any { it !in '0'..'9' }) return null
        val n = part.toInt()
        if (n > 255) return null
        n.toByte()
    }
}

internal object EndpointResolver {
    // A socket timeout cannot interrupt getByName. Bound both the caller's wait and the number of
    // native lookups that can outlive it on a TV with an unresponsive DNS server.
    private val dns = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32), { r ->
        Thread(r, "vpn-endpoint-dns").apply { isDaemon = true }
    }).apply { allowCoreThreadTimeOut(true) }

    fun resolve(host: String, timeoutMs: Int, lookup: (String) -> InetAddress = InetAddress::getByName): InetAddress {
        ipv4Bytes(host)?.let { return InetAddress.getByAddress(it) }
        val task = FutureTask { lookup(host) }
        try {
            dns.execute(task)
        } catch (_: RejectedExecutionException) {
            throw UnknownHostException("DNS busy: $host")
        }
        try {
            return task.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw UnknownHostException("DNS timed out: $host")
        } catch (e: ExecutionException) {
            throw UnknownHostException(host).apply { initCause(e.cause) }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("DNS lookup cancelled")
        } finally {
            task.cancel(true)
            dns.remove(task)
        }
    }
}
