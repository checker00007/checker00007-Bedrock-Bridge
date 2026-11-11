package de.checker00007.bedrockbridge

import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TransportTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun netherWireAcceptsIndependentDiscoveryFixtureAndRejectsTampering() {
        val request = "a87c6e38b3ff0ef3514ada5d0d433d56709091080306399a2bd75d4c1fbd06562546e9f4ff4485881fd5cb97a655a701ff0f8709e13bf6858e2051c321db615e"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val decoded = requireNotNull(NetherWire.decode(request))
        assertEquals(0, decoded.kind)
        assertEquals(0x0102030405060708L, decoded.sender)
        assertArrayEquals(request, NetherWire.encode(0, decoded.sender))
        request[0] = 0
        assertNull(NetherWire.decode(request))
    }

    @Test
    fun netherSignalsStayBoundToTheirRecipient() {
        val bytes = NetherWire.signal(-1, 42, "CONNECTRESPONSE 123 v=0")
        val packet = requireNotNull(NetherWire.decode(bytes))
        assertEquals(-1L, packet.sender)
        assertEquals("CONNECTRESPONSE 123 v=0", packet.signalFor(42))
        assertNull(packet.signalFor(43))
    }

    @Test
    fun trickledCandidatesGoInTheApplicationSection() {
        val offer = "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=ice-ufrag:c\r\na=end-of-candidates\r\n"
        val candidate = "candidate:1 1 udp 1 192.0.2.10 4567 typ host"
        val result = NetherBridge.insertCandidates(offer, setOf(candidate))
        assertTrue(result.contains("a=$candidate\r\na=end-of-candidates"))
        assertEquals(result, NetherBridge.insertCandidates(result, setOf(candidate)))
    }

    @Test(timeout = 8_000)
    fun netherDiscoveryAndHttpAnswerRoundTrip() {
        val offer = "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=candidate:1 1 udp 1 192.0.2.1 4567 typ host\r\n"
        val answer = offer.replace("192.0.2.1", "198.51.100.3")
        val http = MockWebServer()
        http.enqueue(MockResponse().setBody(answer))
        http.start(loopback, 0)
        val worker = Executors.newSingleThreadExecutor()
        try {
            DatagramSocket(0, loopback).use { lan ->
                val ready = java.util.concurrent.CountDownLatch(1)
                val target = InetSocketAddress(loopback, lan.localPort)
                val server = thread(isDaemon = true) {
                    try {
                        NetherBridge(
                            lan, Profile(host = "127.0.0.1", port = http.port, signaling = "HTTP"),
                            worker, { true }, { if (it.contains("aktiv auf UDP")) ready.countDown() }
                        ).run()
                    } catch (_: java.net.SocketException) {
                        check(lan.isClosed)
                    }
                }
                try {
                    assertTrue(ready.await(2, TimeUnit.SECONDS))
                    DatagramSocket(0, loopback).use { client ->
                        client.soTimeout = 3000
                        client.sendBytes(NetherWire.encode(0, 42), target)
                        val advertised = requireNotNull(NetherWire.decode(client.receiveBytes(ByteArray(4096)).first))
                        assertEquals(1, advertised.kind)
                        client.sendBytes(NetherWire.signal(42, advertised.sender, "CONNECTREQUEST 7 $offer"), target)
                        val reply = requireNotNull(NetherWire.decode(client.receiveBytes(ByteArray(4096)).first))
                        assertEquals("CONNECTRESPONSE 7 $answer", reply.signalFor(42))
                        val request = requireNotNull(http.takeRequest(1, TimeUnit.SECONDS))
                        assertEquals("/v1/join/42", request.path)
                        assertEquals("POST", request.method)
                        assertEquals("application/sdp", request.getHeader("Content-Type"))
                        assertEquals(offer, request.body.readUtf8())
                    }
                } finally {
                    lan.close()
                    server.join(1000)
                }
            }
        } finally {
            worker.shutdownNow()
            http.close()
        }
    }

    @Test(timeout = 8_000)
    fun legacyDiscoversAndRelaysInBothDirections() {
        val motd = "MCPE;Upstream;2193;26.52;0;20;17;world;Survival;1;19132;19133"
        DatagramSocket(0, loopback).use { upstream ->
            upstream.soTimeout = 3500
            val server = thread(isDaemon = true) {
                val (ping, probe) = upstream.receiveBytes(ByteArray(4096))
                upstream.sendBytes(RakWire.pong(ping, 17, motd), probe)
                val (request, peer) = upstream.receiveBytes(ByteArray(4096))
                upstream.sendBytes(request.reversedArray(), peer)
            }
            DatagramSocket(0, loopback).use { lan ->
                val ready = java.util.concurrent.CountDownLatch(1)
                val bridge = LegacyBridge(
                    lan, InetSocketAddress(loopback, upstream.localPort),
                    Profile(name = "Test-LAN", host = "127.0.0.1", mode = Mode.LEGACY),
                    { true }, { if (it.contains("aktiv auf UDP")) ready.countDown() }
                )
                val relay = thread(isDaemon = true) {
                    try {
                        bridge.run()
                    } catch (_: java.net.SocketException) {
                        check(lan.isClosed)
                    }
                }
                try {
                    assertTrue(ready.await(3, TimeUnit.SECONDS))
                    DatagramSocket(0, loopback).use { client ->
                        client.soTimeout = 2000
                        val target = InetSocketAddress(loopback, lan.localPort)
                        val ping = RakWire.ping()
                        client.sendBytes(ping, target)
                        val pong = client.receiveBytes(ByteArray(4096)).first
                        assertTrue(RakWire.readPong(ping, pong)!!.contains(";Test-LAN;2193;26.52;"))
                        val open = byteArrayOf(5) + ping.copyOfRange(9, 25) + byteArrayOf(11)
                        client.sendBytes(open, target)
                        assertArrayEquals(open.reversedArray(), client.receiveBytes(ByteArray(4096)).first)
                    }
                } finally {
                    bridge.close()
                    relay.join(1000)
                    server.join(1000)
                }
            }
        }
    }

    @Test
    fun profileRejectsUrlsAndInvalidPorts() {
        assertTrue(Profile(host = "mc.example.org").valid())
        assertTrue(Profile(host = "2001:db8::5").valid())
        assertFalse(Profile(host = "https://mc.example.org").valid())
        assertFalse(Profile(host = "mc.example.org", port = 0).valid())
    }
}
