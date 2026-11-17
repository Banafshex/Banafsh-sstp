package kittoku.osc

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class EndpointProbeTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val endpoint = ServerEndpoint("name-without-dns.invalid", "127.0.0.1")

    @Test fun numericCatalogAddressDoesNotNeedDnsOrChangePeerName() {
        assertEquals("name-without-dns.invalid", endpoint.host)
        assertEquals(loopback, EndpointResolver.resolve(endpoint.dialHost, 100) { error("Unexpected DNS") })
        for (invalid in listOf(null, "", "alternate.invalid", "127.1", "1.2.3.256", "01.2.3.4")) {
            val fallback = ServerEndpoint(endpoint.host, invalid)
            assertNull(fallback.ip)
            assertEquals(loopback, EndpointResolver.resolve(fallback.dialHost, 1000) { host ->
                assertEquals(endpoint.host, host)
                loopback
            })
        }
    }

    @Test(timeout = 3000) fun stalledDnsCannotHoldCaller() {
        val release = CountDownLatch(1)
        try {
            assertThrows(UnknownHostException::class.java) {
                EndpointResolver.resolve(endpoint.host, 100) { release.await(); loopback }
            }
        } finally {
            release.countDown()
        }
    }

    @Test(timeout = 4000) fun ikeReplyUsesTheCatalogAddress() {
        DatagramSocket(0, loopback).use { peer ->
            val responder = thread(isDaemon = true) {
                val request = DatagramPacket(ByteArray(2048), 2048)
                peer.receive(request)
                // An ISAKMP header with the initiator cookie from this request.
                peer.send(DatagramPacket(request.data, 28, request.socketAddress))
            }
            ProbeBatch().use { assertTrue(IkeProbe.test(endpoint, it, peer.localPort, 500) > 0) }
            responder.join(1000)
        }
    }

    @Test(timeout = 3000) fun silenceNeverProducesAPing() {
        DatagramSocket(0, loopback).use { peer ->
            ProbeBatch().use { assertEquals(ServerTester.FAILED, IkeProbe.test(endpoint, it, peer.localPort, 100)) }
        }
    }

    @Test(timeout = 3000) fun unrelatedDatagramsDoNotRestartIkeDeadline() {
        DatagramSocket(0, loopback).use { peer ->
            val responder = thread(isDaemon = true) {
                val request = DatagramPacket(ByteArray(2048), 2048)
                peer.receive(request)
                val end = System.nanoTime() + 2_000_000_000L
                // A short datagram cannot be an IKE answer; keep the socket readable past both try deadlines.
                while (!peer.isClosed && System.nanoTime() < end) {
                    if (runCatching { peer.send(DatagramPacket(byteArrayOf(0), 1, request.socketAddress)) }.isFailure) break
                    Thread.sleep(10)
                }
            }
            val start = System.nanoTime()
            ProbeBatch().use { assertEquals(ServerTester.FAILED, IkeProbe.test(endpoint, it, peer.localPort, 100)) }
            assertTrue("Test waited for the unrelated traffic to end", System.nanoTime() - start < 1_500_000_000L)
            peer.close()
            responder.join(1000)
        }
    }
}
