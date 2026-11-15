// SPDX-License-Identifier: MIT
package app.spinbridge.myspin

import java.io.ByteArrayOutputStream
import java.io.IOException

/** A malformed mySPIN packet. Fatal for the session: the stream has no sync marker to recover on. */
class MySpinProtocolException(message: String) : IOException(message)

/**
 * mySPIN framing: `type u8 | u32 (always 0) | payload length u32 | payload`, little-endian.
 */
object Wire {
    const val HEADER_SIZE = 9

    /** Largest payload accepted from the car. Its packets are tiny; this only bounds a corrupt length. */
    const val MAX_INBOUND_PAYLOAD = 1 shl 20

    class Packet(val type: Int, val payload: ByteArray) {
        override fun toString(): String = "Packet(type=$type, ${payload.size} bytes)"
    }

    fun encode(type: Int, payload: ByteArray): ByteArray {
        require(type in 0..0xFF) { "packet type $type does not fit a byte" }
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = type.toByte()
        // bytes 1..4 stay zero
        putU32(out, 5, payload.size)
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    private fun putU32(target: ByteArray, at: Int, value: Int) {
        target[at] = value.toByte()
        target[at + 1] = (value ushr 8).toByte()
        target[at + 2] = (value ushr 16).toByte()
        target[at + 3] = (value ushr 24).toByte()
    }

    /**
     * Turns the chunks a USB accessory read returns into whole packets. A chunk can hold several
     * packets, or a fraction of one.
     */
    class Assembler {
        private var buffer = ByteArray(4096)
        private var filled = 0

        fun feed(chunk: ByteArray, length: Int): List<Packet> {
            if (length <= 0) return emptyList()
            if (filled + length > buffer.size) {
                buffer = buffer.copyOf(maxOf(buffer.size * 2, filled + length))
            }
            chunk.copyInto(buffer, filled, 0, length)
            filled += length

            val packets = ArrayList<Packet>(2)
            var at = 0
            while (filled - at >= HEADER_SIZE) {
                val type = buffer[at].toInt() and 0xFF
                val size = (buffer[at + 5].toInt() and 0xFF) or
                    ((buffer[at + 6].toInt() and 0xFF) shl 8) or
                    ((buffer[at + 7].toInt() and 0xFF) shl 16) or
                    ((buffer[at + 8].toInt() and 0xFF) shl 24)
                if (size < 0 || size > MAX_INBOUND_PAYLOAD) {
                    throw MySpinProtocolException("packet type $type announces an invalid length $size")
                }
                if (filled - at < HEADER_SIZE + size) break
                packets += Packet(type, buffer.copyOfRange(at + HEADER_SIZE, at + HEADER_SIZE + size))
                at += HEADER_SIZE + size
            }
            if (at > 0) {
                buffer.copyInto(buffer, 0, at, filled)
                filled -= at
            }
            return packets
        }
    }

    /** Little-endian field reader over one payload. */
    class Reader(private val bytes: ByteArray) {
        var position = 0
            private set

        val remaining: Int get() = bytes.size - position

        fun u8(): Int {
            if (remaining < 1) throw MySpinProtocolException("payload truncated at byte $position")
            return bytes[position++].toInt() and 0xFF
        }

        fun u16(): Int {
            val low = u8()
            return low or (u8() shl 8)
        }

        fun i32(): Int {
            val low = u16()
            return low or (u16() shl 16)
        }

        fun u32(): Long = i32().toLong() and 0xFFFF_FFFFL

        fun bytes(count: Int): ByteArray {
            if (count < 0 || count > remaining) {
                throw MySpinProtocolException("field of $count bytes at $position overruns the payload")
            }
            return bytes.copyOfRange(position, position + count).also { position += count }
        }

        /** `encoding u8 | length u16 | bytes`: 1 is UTF-8, 2 is ISO-8859-1. */
        fun string(): String {
            val encoding = u8()
            val raw = bytes(u16())
            return when (encoding) {
                2 -> String(raw, Charsets.ISO_8859_1)
                else -> String(raw, Charsets.UTF_8)
            }
        }

        /** `count u8` followed by that many `u8`. */
        fun u8List(): List<Int> = List(u8()) { u8() }

        /** `count u8` followed by that many `u16`. */
        fun u16List(): List<Int> = List(u8()) { u16() }
    }

    /** Little-endian field writer for one payload. */
    class Writer {
        private val out = ByteArrayOutputStream()

        fun u8(value: Int): Writer = apply { out.write(value and 0xFF) }

        fun u16(value: Int): Writer = apply {
            u8(value)
            u8(value ushr 8)
        }

        fun i32(value: Int): Writer = apply {
            u16(value)
            u16(value ushr 16)
        }

        fun i64(value: Long): Writer = apply {
            i32(value.toInt())
            i32((value ushr 32).toInt())
        }

        fun bytes(value: ByteArray): Writer = apply { out.write(value) }

        /** Always UTF-8, as the launcher writes it. An empty string is `01 00 00`. */
        fun string(value: String): Writer = apply {
            val raw = value.toByteArray(Charsets.UTF_8)
            require(raw.size <= 0xFFFF) { "string of ${raw.size} bytes does not fit a u16 length" }
            u8(1)
            u16(raw.size)
            bytes(raw)
        }

        fun build(): ByteArray = out.toByteArray()
    }

    fun hex(bytes: ByteArray, limit: Int = 64): String {
        val shown = minOf(bytes.size, limit)
        val text = StringBuilder(shown * 3)
        for (i in 0 until shown) {
            if (i > 0) text.append(' ')
            text.append(HEX[(bytes[i].toInt() ushr 4) and 0xF]).append(HEX[bytes[i].toInt() and 0xF])
        }
        if (bytes.size > shown) text.append(" … (").append(bytes.size).append(" bytes)")
        return text.toString()
    }

    private const val HEX = "0123456789abcdef"
}
