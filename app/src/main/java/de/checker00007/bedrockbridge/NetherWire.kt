package de.checker00007.bedrockbridge

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Wire format adapted from go-nethernet v1.0.26 (MIT, see notices/go-nethernet.txt).
internal object NetherWire {
    const val PORT = 7551
    const val MAX_SIGNAL = 12_000
    private const val HEADER = 20
    private val key = MessageDigest.getInstance("SHA-256").digest(le(8).putLong(0xdeadbeef).array())
    private val hex = "0123456789abcdef"

    data class Packet(val kind: Int, val sender: Long, val payload: ByteArray) {
        fun signalFor(recipient: Long): String? {
            if (kind != 2 || payload.size !in 12..MAX_SIGNAL + 12) return null
            val buffer = le(payload)
            if (buffer.long != recipient) return null
            val declared = buffer.int
            if (declared !in 1..buffer.remaining()) return null
            // Vanilla may continue writing the signal past its declared size.
            return payload.decodeToString(startIndex = 12)
        }
    }

    fun encode(kind: Int, sender: Long, payload: ByteArray = byteArrayOf()): ByteArray {
        require(payload.size <= MAX_SIGNAL + 12)
        val plain = le(HEADER + payload.size).putShort((HEADER + payload.size).toShort())
            .putShort(kind.toShort()).putLong(sender).putLong(0).put(payload).array()
        return checksum(plain) + crypt(Cipher.ENCRYPT_MODE, plain)
    }

    fun decode(bytes: ByteArray): Packet? {
        if (bytes.size !in 64..MAX_SIGNAL + 128 || (bytes.size - 32) % 16 != 0) return null
        return runCatching {
            val plain = crypt(Cipher.DECRYPT_MODE, bytes.copyOfRange(32, bytes.size))
            if (plain.size < HEADER || !MessageDigest.isEqual(bytes.copyOf(32), checksum(plain))) {
                return null
            }
            val buffer = le(plain)
            // Some client variants write a different length. The MAC covers the entire payload.
            buffer.short
            val kind = buffer.short.toInt()
            val sender = buffer.long
            if (kind !in 0..2 || (kind == 0 && plain.size != HEADER)) return null
            Packet(kind, sender, plain.copyOfRange(HEADER, plain.size))
        }.getOrNull()
    }

    fun advertisement(profile: Profile, sender: Long): ByteArray {
        val data = ByteArrayOutputStream().apply {
            write(7)
            text("Bedrock Bridge")
            zigzag(profile.protocol)
            text(profile.version)
            text(profile.name)
            zigzag(0)
            zigzag(10)
            zigzag(0)
            write(byteArrayOf(0, 0, 1, 0))
            text(profile.nonce)
            zigzag(4)
        }.toByteArray()
        val ascii = ByteArray(data.size * 2)
        data.forEachIndexed { i, b ->
            ascii[2 * i] = hex[(b.toInt() ushr 4) and 15].code.toByte()
            ascii[2 * i + 1] = hex[b.toInt() and 15].code.toByte()
        }
        return encode(1, sender, le(4 + ascii.size).putInt(ascii.size).put(ascii).array())
    }

    fun signal(sender: Long, recipient: Long, signal: String): ByteArray {
        val bytes = signal.encodeToByteArray()
        require(bytes.size in 1..MAX_SIGNAL)
        return encode(2, sender, le(12 + bytes.size).putLong(recipient).putInt(bytes.size).put(bytes).array())
    }

    private fun ByteArrayOutputStream.text(value: String) {
        val bytes = value.encodeToByteArray()
        unsigned(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.zigzag(value: Int) = unsigned((value shl 1) xor (value shr 31))

    private fun ByteArrayOutputStream.unsigned(value: Int) {
        var rest = value
        while (rest ushr 7 != 0) {
            write((rest and 127) or 128)
            rest = rest ushr 7
        }
        write(rest)
    }

    private fun checksum(bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(bytes)
    }

    private fun crypt(mode: Int, bytes: ByteArray): ByteArray = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
        init(mode, SecretKeySpec(key, "AES"))
        doFinal(bytes)
    }

    private fun le(size: Int) = le(ByteArray(size))
    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}
