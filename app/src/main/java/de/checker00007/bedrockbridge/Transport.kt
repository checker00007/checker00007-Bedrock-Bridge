package de.checker00007.bedrockbridge

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.random.Random

internal fun DatagramSocket.sendBytes(bytes: ByteArray, to: InetSocketAddress) =
    send(DatagramPacket(bytes, bytes.size, to))

internal fun DatagramSocket.receiveBytes(buffer: ByteArray): Pair<ByteArray, InetSocketAddress> {
    val packet = DatagramPacket(buffer, buffer.size)
    receive(packet)
    return packet.data.copyOf(packet.length) to (packet.socketAddress as InetSocketAddress)
}

internal fun rakProbe(target: InetSocketAddress): Pair<String, Long> =
    DatagramSocket().use { socket ->
        socket.connect(target)
        socket.soTimeout = 2500
        val ping = RakWire.ping()
        socket.sendBytes(ping, target)
        val reply = socket.receiveBytes(ByteArray(4096)).first
        val motd = RakWire.readPong(ping, reply) ?: throw IOException("Ungültige RakNet-Antwort.")
        motd to ByteBuffer.wrap(reply, 9, 8).long
    }

internal class LegacyBridge(
    private val lan: DatagramSocket,
    private val target: InetSocketAddress,
    private val profile: Profile,
    private val allowed: (InetSocketAddress) -> Boolean,
    private val log: (String) -> Unit
) : Closeable {
    private var peer: InetSocketAddress? = null
    private val upstream = DatagramSocket().apply {
        connect(target)
        soTimeout = 1
    }

    fun run() {
        val (motd, id) = rakProbe(target)
        val advertisement = RakWire.localMotd(motd, profile.name, id, lan.localPort)
        val buffer = ByteArray(65_507)
        val upstreamBuffer = ByteArray(65_507)
        lan.soTimeout = 50
        var lastPacket = 0L
        var lastDiscovery = 0L
        log("RakNet-LAN aktiv auf UDP ${lan.localPort}.")
        while (!lan.isClosed) {
            val now = System.nanoTime()
            if (peer != null && now - lastPacket > 30_000_000_000L) {
                peer = null
                log("RakNet-Sitzung abgelaufen.")
            }
            try {
                val (data, from) = lan.receiveBytes(buffer)
                if (allowed(from)) {
                    when {
                        RakWire.isPing(data) -> {
                            lan.sendBytes(RakWire.pong(data, id, advertisement), from)
                            if (now - lastDiscovery > 3_000_000_000L) {
                                log("RakNet-LAN-Suche beantwortet.")
                                lastDiscovery = now
                            }
                        }
                        from == peer || (peer == null && RakWire.isOpen(data)) -> {
                            upstream.sendBytes(data, target)
                            if (peer == null) log("RakNet-Verbindung wird weitergeleitet.")
                            peer = from
                            lastPacket = now
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {
                // Periodically drain upstream even when the console is idle.
            }
            val client = peer ?: continue
            for (i in 0 until 16) {
                try {
                    val (data, _) = upstream.receiveBytes(upstreamBuffer)
                    lan.sendBytes(data, client)
                } catch (_: SocketTimeoutException) {
                    break
                }
            }
        }
    }

    override fun close() {
        lan.close()
        upstream.close()
    }
}

internal class JoinEndpoint(private val profile: Profile) {
    private fun url(path: String): URL {
        val scheme = if (profile.signaling == "HTTP") "http" else "https"
        val port = if (profile.signaling == "HTTPS 443") 443 else profile.port
        val host = if (':' in profile.host) "[${profile.host}]" else profile.host
        return URL(scheme, host, port, path)
    }

    fun status(): String = request("/v1/join")
    fun answer(clientId: Long, offer: String): String =
        request("/v1/join/${java.lang.Long.toUnsignedString(clientId)}", offer)

    private fun request(path: String, body: String? = null): String {
        val connection = url(path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3500
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            if (body != null) {
                val bytes = body.encodeToByteArray()
                require(bytes.size <= NetherWire.MAX_SIGNAL) { "NetherNet-Angebot zu groß." }
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/sdp")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("NetherNet-Signaling meldet HTTP $code.")
            val result = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (result.size() + n > NetherWire.MAX_SIGNAL) {
                        throw IOException("NetherNet-Antwort zu groß.")
                    }
                    result.write(buffer, 0, n)
                }
            }
            return result.toByteArray().decodeToString()
        } finally {
            connection.disconnect()
        }
    }
}

// LAN signaling adapter inspired by bedrockproxy's direct mode (MIT).
// Media and authentication stay between the client and the upstream.
internal class NetherBridge(
    private val socket: DatagramSocket,
    private val profile: Profile,
    private val executor: ExecutorService,
    private val allowed: (InetSocketAddress) -> Boolean,
    private val log: (String) -> Unit
) {
    private data class Key(val address: InetSocketAddress, val sender: Long, val connection: ULong)
    private data class Exchange(
        val started: Long,
        var changed: Long,
        var offer: String? = null,
        val candidates: MutableSet<String> = linkedSetOf()
    )

    fun run() {
        val id = Random.nextLong()
        val reply = NetherWire.advertisement(profile, id)
        val pending = mutableMapOf<Key, Exchange>()
        val endpoint = JoinEndpoint(profile)
        socket.soTimeout = 125
        val buffer = ByteArray(NetherWire.MAX_SIGNAL + 128)
        var lastDiscovery = 0L
        log("NetherNet-LAN aktiv auf UDP ${socket.localPort}.")
        while (!socket.isClosed) {
            try {
                val (bytes, from) = socket.receiveBytes(buffer)
                val packet = if (allowed(from)) NetherWire.decode(bytes) else null
                if (packet?.kind == 0) {
                    socket.sendBytes(reply, from)
                    val now = System.nanoTime()
                    if (now - lastDiscovery > 3_000_000_000L) {
                        lastDiscovery = now
                        log("NetherNet-LAN-Suche beantwortet.")
                    }
                }
                val parts = packet?.signalFor(id)?.split(' ', limit = 3)
                if (packet != null && parts?.size == 3) {
                    val connection = parts[1].toULongOrNull()
                    if (connection != null) {
                        receiveSignal(pending, Key(from, packet.sender, connection), parts[0], parts[2])
                    }
                }
            } catch (_: SocketTimeoutException) {
                // Flush offers even when there are no more candidate packets.
            }
            val now = System.nanoTime()
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val (key, exchange) = iterator.next()
                val offer = exchange.offer
                val hasCandidate = offer?.contains("a=candidate:") == true || exchange.candidates.isNotEmpty()
                if (offer != null && hasCandidate &&
                    (now - exchange.changed >= 500_000_000L || now - exchange.started >= 5_000_000_000L)
                ) {
                    iterator.remove()
                    sendOffer(endpoint, id, key, offer, exchange.candidates)
                } else if (now - exchange.started >= 5_000_000_000L) {
                    iterator.remove()
                    if (offer != null) {
                        log("NetherNet: Die Xbox hat keine ICE-Kandidaten geliefert.")
                        reply(id, key, "CONNECTERROR", "2")
                    }
                }
            }
        }
    }

    private fun receiveSignal(pending: MutableMap<Key, Exchange>, key: Key, type: String, data: String) {
        if (type == "CONNECTERROR") {
            pending.remove(key)
            return
        }
        if (type != "CONNECTREQUEST" && type != "CANDIDATEADD") return
        if (key !in pending && pending.size >= 8) return
        val now = System.nanoTime()
        val exchange = pending.getOrPut(key) { Exchange(now, now) }
        if (type == "CONNECTREQUEST" && data.startsWith("v=0") && exchange.offer == null) {
            exchange.offer = data
            exchange.changed = now
        } else if (type == "CANDIDATEADD") {
            val candidate = data.removePrefix("a=")
            if (candidate.startsWith("candidate:") && candidate.length <= 1024 &&
                '\n' !in candidate && '\r' !in candidate && exchange.candidates.size < 12 &&
                exchange.candidates.add(candidate)
            ) exchange.changed = now
        }
    }

    private fun sendOffer(endpoint: JoinEndpoint, id: Long, key: Key, offer: String, candidates: Set<String>) {
        try {
            executor.execute {
                try {
                    if (socket.isClosed) return@execute
                    log("NetherNet: Xbox-Angebot empfangen, frage Geyser an.")
                    val answer = endpoint.answer(key.sender, insertCandidates(offer, candidates))
                    if (!answer.startsWith("v=0") || !answer.contains("a=candidate:")) {
                        throw IOException("SDP-Antwort ohne nutzbaren ICE-Kandidaten.")
                    }
                    reply(id, key, "CONNECTRESPONSE", answer)
                    log("NetherNet: SDP-Antwort gesendet. Spielverbindung auf der Xbox prüfen.")
                } catch (error: Exception) {
                    if (!socket.isClosed) {
                        log("NetherNet-Beitritt: ${networkError(error)}")
                        runCatching { reply(id, key, "CONNECTERROR", "13") }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            if (!socket.isClosed) reply(id, key, "CONNECTERROR", "2")
        }
    }

    private fun reply(id: Long, key: Key, type: String, data: String) {
        socket.sendBytes(NetherWire.signal(id, key.sender, "$type ${key.connection} $data"), key.address)
    }

    companion object {
        fun insertCandidates(offer: String, candidates: Set<String>): String {
            if (candidates.isEmpty()) return offer
            val lines = offer.replace("\r\n", "\n").trimEnd('\n').split('\n').toMutableList()
            val start = lines.indexOfFirst { it.startsWith("m=application ") }
            require(start >= 0) { "SDP-Datenkanal fehlt." }
            val end = (start + 1 until lines.size).firstOrNull {
                lines[it].startsWith("m=") || lines[it] == "a=end-of-candidates"
            } ?: lines.size
            lines.addAll(end, candidates.map { "a=$it" }.filter { it !in lines })
            return lines.joinToString("\r\n", postfix = "\r\n")
        }
    }
}

internal fun networkError(error: Exception): String = when (error) {
    is java.net.UnknownHostException -> "Serveradresse konnte nicht aufgelöst werden."
    is java.net.BindException -> "Der lokale LAN-Port ist bereits belegt oder nicht verfügbar."
    is SocketTimeoutException -> "Zeitüberschreitung. Adresse, Port und Erreichbarkeit prüfen."
    is java.net.ConnectException, is java.net.PortUnreachableException ->
        "Server oder Port nicht erreichbar. Geyser-Endpunkt und Transport prüfen."
    is javax.net.ssl.SSLException -> "TLS fehlgeschlagen. Servername, Zertifikat und Signaling-Port prüfen."
    is SecurityException -> "Android hat den Netzwerkzugriff verweigert."
    else -> error.message?.take(180) ?: "Netzwerkfehler (${error.javaClass.simpleName})."
}
