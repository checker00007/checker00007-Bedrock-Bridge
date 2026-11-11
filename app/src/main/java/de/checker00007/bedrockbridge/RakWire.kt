package de.checker00007.bedrockbridge

import java.nio.ByteBuffer
import kotlin.random.Random

internal object RakWire {
    const val PORT = 19132
    private val magic = byteArrayOf(0, -1, -1, 0, -2, -2, -2, -2, -3, -3, -3, -3, 18, 52, 86, 120)

    fun ping(timestamp: Long = Random.nextLong()): ByteArray =
        ByteBuffer.allocate(33).put(1).putLong(timestamp).put(magic).putLong(Random.nextLong()).array()

    fun isPing(packet: ByteArray): Boolean = packet.size == 33 &&
        (packet[0] == 1.toByte() || packet[0] == 2.toByte()) &&
        packet.copyOfRange(9, 25).contentEquals(magic)

    fun isOpen(packet: ByteArray): Boolean = packet.size in 18..1500 &&
        packet[0] == 5.toByte() && packet.copyOfRange(1, 17).contentEquals(magic)

    fun readPong(ping: ByteArray, response: ByteArray): String? {
        if (response.size < 35 || response[0] != 28.toByte() ||
            !ping.copyOfRange(1, 9).contentEquals(response.copyOfRange(1, 9)) ||
            !response.copyOfRange(17, 33).contentEquals(magic)) return null
        val size = ByteBuffer.wrap(response, 33, 2).short.toInt() and 0xffff
        if (size !in 1..2048 || response.size != 35 + size) return null
        return response.decodeToString(startIndex = 35).takeIf {
            val fields = it.split(';')
            fields.size >= 9 && fields[0] == "MCPE" && fields[2].toIntOrNull() != null
        }
    }

    fun pong(ping: ByteArray, id: Long, motd: String): ByteArray {
        val data = motd.encodeToByteArray()
        return ByteBuffer.allocate(35 + data.size).put(28).put(ping, 1, 8)
            .putLong(id).put(magic).putShort(data.size.toShort()).put(data).array()
    }

    fun localMotd(upstream: String, name: String, id: Long, port: Int): String {
        val fields = upstream.split(';').toMutableList()
        while (fields.size < 12) fields.add("")
        fields[1] = name.replace(';', ' ').replace('\n', ' ').replace('\r', ' ')
        fields[6] = id.toString()
        fields[7] = "Bedrock Bridge"
        fields[10] = port.toString()
        fields[11] = "0"
        return fields.joinToString(";")
    }
}
