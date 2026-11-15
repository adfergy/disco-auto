// SPDX-License-Identifier: MIT
package app.spinbridge.myspin

import app.spinbridge.myspin.Wire.Packet
import app.spinbridge.myspin.Wire.Writer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Drives [MySpinServer] the way a head unit would, for each protocol generation, and pins the
 * bytes it answers with to what the Land Rover launcher 2.10.0 sends.
 */
class MySpinServerTest {

    private class Recorder : MySpinServer.Listener {
        val sent = ArrayList<Packet>()
        val logs = ArrayList<String>()
        var running: MySpinServer.Client? = null
        var frameRequests = 0
        var lastIncremental: Boolean? = null
        val touches = ArrayList<List<MySpinServer.Touch>>()
        val softkeys = ArrayList<Pair<Int, Boolean>>()
        val states = ArrayList<Pair<Int, Int>>()
        val strings = ArrayList<Pair<Int, String>>()
        val keys = ArrayList<Pair<Int, Int>>()
        val audio = ArrayList<Triple<Long, Int, Int>>()

        override fun onLog(line: String) { logs += line }
        override fun onRunning(client: MySpinServer.Client) { running = client }
        override fun onFrameRequest(x: Int, y: Int, width: Int, height: Int, incremental: Boolean) {
            frameRequests++
            lastIncremental = incremental
        }
        override fun onTouch(touches: List<MySpinServer.Touch>) { this.touches += touches }
        override fun onSoftkey(key: Int, pressed: Boolean) { softkeys += key to pressed }
        override fun onClientState(kind: Int, value: Int) { states += kind to value }
        override fun onCustomString(type: Int, value: String) { strings += type to value }
        override fun onCustomKey(action: Int, key: Int) { keys += action to key }
        override fun onAudioStatus(requestId: Long, status: Int, result: Int) { audio += Triple(requestId, status, result) }

        fun take(type: Int): Packet {
            val index = sent.indexOfFirst { it.type == type }
            if (index < 0) fail("expected a packet of type $type, sent: ${sent.map { it.type }}")
            return sent.removeAt(index)
        }
    }

    private fun server(recorder: Recorder) = MySpinServer(
        send = { recorder.sent += it },
        listener = recorder,
        nowMillis = { 1_700_000_000_000L },
        utcOffsetMillis = { 3_600_000 },
    )

    private fun version(major: Int, minor: Int, build: Int) =
        Packet(1, Writer().u16(major).u16(minor).u16(build).build())

    private fun clientInit11(nonTouch: Boolean = false): Packet {
        val writer = Writer()
            .string("Land Rover")
            .u16(800).u16(480)
            .u16(177).u16(100)
            .i32(0x1B)                       // phone call, navigate, position, audio handling
            .u8(2).u8(2).i32(0).u8(0)        // RGB565, little-endian, no override, level 0
            .i32(7)                          // JPEG | zlib | uncompressed
            .u8(0)                           // no custom data ids
        if (nonTouch) writer.u8(0)
        return Packet(11, writer.build())
    }

    private fun hex(text: String): ByteArray =
        text.filterNot(Char::isWhitespace).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val serverName = "01 15 00 " + "mySPIN Android Server".toByteArray().joinToString(" ") { "%02x".format(it) }
    private val android = "01 07 00 " + "Android".toByteArray().joinToString(" ") { "%02x".format(it) }

    @Test
    fun protocol11HandshakeMatchesTheLauncher() {
        val recorder = Recorder()
        val server = server(recorder)

        server.receive(version(1, 1, 0))
        assertArrayEquals(hex("01"), recorder.take(2).payload)
        assertEquals(MySpinServer.State.AWAIT_INIT, server.state)

        server.receive(clientInit11())
        assertArrayEquals(hex("01"), recorder.take(12).payload)
        assertArrayEquals(hex("$serverName 01 01 01 02 00 00 00 $android"), recorder.take(14).payload)
        assertEquals(MySpinServer.State.RUNNING, server.state)

        val client = recorder.running
        assertNotNull(client)
        client!!
        assertEquals("Land Rover", client.name)
        assertEquals(800, client.width)
        assertEquals(480, client.height)
        assertEquals(MySpinServer.Generation.V1_1, client.generation)
        assertEquals(MySpinServer.COMPRESSION_JPEG, client.frameCompression)
        assertTrue(client.hasAudioHandling)

        // UTC then local time, each an i64 of seconds inside ServerCustomData.
        val utc = recorder.take(68).payload
        assertArrayEquals(hex("02 04 00 08 00") + Writer().i64(1_700_000_000L).build(), utc)
        val local = recorder.take(68).payload
        assertArrayEquals(hex("02 06 00 08 00") + Writer().i64(1_700_003_600L).build(), local)
        // Then what the launcher sends when its screen comes up: transition ends, inactive ends.
        assertArrayEquals(hex("02 00"), recorder.take(36).payload)
        assertArrayEquals(hex("03 00"), recorder.take(36).payload)
        assertTrue("nothing else is sent unprompted", recorder.sent.isEmpty())
    }

    @Test
    fun runtimeOpensWithTimeThenTransitionEndsThenInactiveEnds() {
        for ((major, minor, build) in listOf(Triple(1, 0, 3), Triple(1, 1, 0))) {
            val recorder = Recorder()
            val server = server(recorder)
            server.receive(version(major, minor, build))
            server.receive(
                if (minor == 0) Packet(11, Writer().string("JLR").u16(800).u16(480).u8(1).u8(2).u8(2).i32(3).build())
                else clientInit11()
            )
            val types = recorder.sent.map { it.type }
            val expected = if (minor == 0) listOf(2, 12, 14, 46, 36, 36) else listOf(2, 12, 14, 68, 68, 36, 36)
            assertEquals("protocol $major.$minor", expected, types)
            val states = recorder.sent.filter { it.type == 36 }.map { it.payload.toList() }
            assertEquals(listOf(listOf<Byte>(2, 0), listOf<Byte>(3, 0)), states)
        }
    }

    @Test
    fun protocol11FrameCarriesFramePropertiesAndScale() {
        val recorder = Recorder()
        val server = server(recorder)
        server.receive(version(1, 1, 0))
        server.receive(clientInit11())
        recorder.sent.clear()

        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        assertFalse("no frame without a request", server.sendFrame(jpeg, MySpinServer.COMPRESSION_JPEG))

        server.receive(Packet(25, Writer().u16(0).u16(0).u16(800).u16(480).u8(0).build()))
        assertEquals(1, recorder.frameRequests)
        assertEquals(false, recorder.lastIncremental)

        assertTrue(server.sendFrame(jpeg, MySpinServer.COMPRESSION_JPEG))
        assertArrayEquals(
            hex("01  00 00 00 00 20 03 e0 01  02 02 01 00 00 00 00  64 64  05 00 00 00  ff d8 01 02 03"),
            recorder.take(26).payload
        )
        assertFalse("one request buys one frame", server.sendFrame(jpeg, MySpinServer.COMPRESSION_JPEG))
    }

    @Test
    fun rawPixelsAreAnnouncedAsUncompressed() {
        val recorder = Recorder()
        val server = server(recorder)
        server.receive(version(1, 1, 0))
        server.receive(clientInit11())
        server.receive(Packet(25, Writer().u16(0).u16(0).u16(800).u16(480).u8(1).build()))
        recorder.sent.clear()
        assertTrue(server.sendFrame(byteArrayOf(9), MySpinServer.COMPRESSION_NONE))
        val payload = recorder.take(26).payload
        assertEquals("compression field", 4, payload[11].toInt())
    }

    @Test
    fun protocol10UsesTheShortLayouts() {
        val recorder = Recorder()
        val server = server(recorder)

        server.receive(version(1, 0, 3))
        assertArrayEquals(hex("01"), recorder.take(2).payload)

        // name, 800x480, compression JPEG, RGB565, little-endian, capabilities 3
        server.receive(Packet(11, Writer().string("JLR").u16(800).u16(480).u8(1).u8(2).u8(2).i32(3).build()))
        assertArrayEquals(hex("01"), recorder.take(12).payload)
        assertArrayEquals(hex("$serverName 01 01 01 01 00 00 00"), recorder.take(14).payload)
        assertEquals(MySpinServer.State.RUNNING, server.state)

        val client = recorder.running!!
        assertEquals(MySpinServer.Generation.V1_0, client.generation)
        assertEquals(MySpinServer.COMPRESSION_JPEG, client.frameCompression)
        assertFalse(client.hasAudioHandling)

        // 1.0 sends only UTC, as ServerCustomDataInt (46): id u16, i64.
        assertArrayEquals(hex("04 00") + Writer().i64(1_700_000_000L).build(), recorder.take(46).payload)

        server.receive(Packet(25, Writer().u16(0).u16(0).u16(800).u16(480).u8(0).build()))
        assertTrue(server.sendFrame(byteArrayOf(7, 8), MySpinServer.COMPRESSION_JPEG))
        assertArrayEquals(hex("01  00 00 00 00 20 03 e0 01  02 00 00 00  07 08"), recorder.take(26).payload)

        // 1.0 acknowledges a compression change without applying it.
        server.receive(Packet(23, hex("02")))
        assertArrayEquals(hex("01"), recorder.take(24).payload)
        assertEquals(MySpinServer.COMPRESSION_JPEG, server.client!!.frameCompression)

        // Bluetooth name goes out as ServerCustomMessage (44) on 1.0.
        server.sendBluetoothName("Pixel")
        assertArrayEquals(hex("01 00 01 05 00") + "Pixel".toByteArray(), recorder.take(44).payload)

        assertFalse("1.0 has no audio focus message", server.sendAudioFocus(1, MySpinServer.AUDIO_REQUEST, 3))
    }

    @Test
    fun protocol12WaitsForClientInitDone() {
        val recorder = Recorder()
        val server = server(recorder)

        server.receive(version(1, 2, 0))
        recorder.take(2)
        server.receive(clientInit11(nonTouch = true))
        recorder.take(12)
        assertArrayEquals(hex("$serverName 01 01 01 06 00 00 00 $android 01 00 00"), recorder.take(14).payload)
        assertArrayEquals(hex("01"), recorder.take(18).payload)
        assertEquals("still waiting for the car", MySpinServer.State.AWAIT_INIT, server.state)

        server.receive(Packet(69, "deu".toByteArray()))      // settings may arrive during init
        assertTrue("no app list before runtime", recorder.sent.none { it.type == 70 })
        server.receive(Packet(17, hex("01")))
        assertEquals(MySpinServer.State.RUNNING, server.state)
        assertNotNull(recorder.running)

        // 1.2 only: the car's settings are answered with the app list, in the car's language.
        recorder.take(68); recorder.take(68)
        assertArrayEquals(hex("02 00"), recorder.take(36).payload)
        assertArrayEquals(hex("03 00"), recorder.take(36).payload)
        assertArrayEquals("launcher visible, 1.2 only", hex("04 01"), recorder.take(36).payload)
        val list = recorder.take(70).payload
        val expected = Writer()
            .i32(1)
            .string("app.spinbridge").string("Disco Auto").i32(0).string("Disco Auto").string("")
            .u8(1).i32(1).u8(3).u8(1).bytes("deu".toByteArray()).build()
        assertArrayEquals(expected, list)

        // Settings again at runtime: answered again.
        server.receive(Packet(69, "eng".toByteArray()))
        assertEquals("eng", String(recorder.take(70).payload.takeLast(3).toByteArray()))

        // The car starting our entry is acknowledged as started.
        server.receive(Packet(73, Writer().string("app.spinbridge").u8(0).u8(0).build()))
        assertArrayEquals(Writer().string("app.spinbridge").u8(2).build(), recorder.take(74).payload)
    }

    @Test
    fun appListIsNotSentOnOlderProtocols() {
        val recorder = Recorder()
        val server = server(recorder)
        server.receive(version(1, 1, 0))
        server.receive(clientInit11())
        server.receive(Packet(69, "eng".toByteArray()))
        assertTrue(recorder.sent.none { it.type == 70 })
    }

    @Test
    fun runtimeEventsAreDecoded() {
        val recorder = Recorder()
        val server = server(recorder)
        server.receive(version(1, 1, 0))
        server.receive(clientInit11())
        recorder.sent.clear()

        // Two fingers: down at (100, 200) id 1, moved at (799, 479) id 2.
        server.receive(Packet(33, Writer().u8(2)
            .u8(1).u16(100).u16(200).i32(1)
            .u8(3).u16(799).u16(479).i32(2).build()))
        assertEquals(
            listOf(MySpinServer.Touch(1, 100, 200, 1), MySpinServer.Touch(3, 799, 479, 2)),
            recorder.touches.single()
        )

        server.receive(Packet(31, hex("01 01")))    // press Home
        server.receive(Packet(31, hex("02 02")))    // release Back
        assertEquals(listOf(1 to true, 2 to false), recorder.softkeys)

        server.receive(Packet(35, hex("02 01")))    // night
        assertEquals(listOf(2 to 1), recorder.states)

        // The car's Bluetooth MAC arrives as a custom string of type 1.
        val mac = Writer().string("AA:BB:CC:DD:EE:FF").build()
        server.receive(Packet(67, Writer().u8(1).u16(1).u16(mac.size).bytes(mac).build()))
        assertEquals(listOf(1 to "AA:BB:CC:DD:EE:FF"), recorder.strings)

        server.sendBluetoothName("Pixel 9")
        val name = Writer().string("Pixel 9").build()
        assertArrayEquals(Writer().u8(1).u16(1).u16(name.size).bytes(name).build(), recorder.take(68).payload)

        // A knob tick: kind 5 (key), action 4 (click), key 20.
        server.receive(Packet(67, Writer().u8(5).u16(4).u16(1).u8(20).build()))
        assertEquals(listOf(4 to 20), recorder.keys)

        assertTrue(server.sendAudioFocus(7, MySpinServer.AUDIO_REQUEST, MySpinServer.AUDIO_TYPE_MAIN))
        assertArrayEquals(hex("07 00 00 00 01 03 00 00"), recorder.take(58).payload)
        server.receive(Packet(59, hex("07 00 00 00 03 00")))
        assertEquals(listOf(Triple(7L, 3, 0)), recorder.audio)

        // A pixel format change is acknowledged but not applied, exactly as the launcher does.
        server.receive(Packet(21, hex("03 02 00")))
        assertArrayEquals(hex("01"), recorder.take(22).payload)
        assertEquals(MySpinServer.PIXEL_RGB565, server.client!!.pixelFormat)
        // A compression override (1.1 and later) is applied.
        server.receive(Packet(23, hex("02 00 00 00")))
        assertArrayEquals(hex("01"), recorder.take(24).payload)
        assertEquals(MySpinServer.COMPRESSION_ZLIB, server.client!!.frameCompression)
        // SetFrameSize has no handler in the launcher: no acknowledgement, no change.
        server.receive(Packet(27, Writer().u16(1024).u16(600).u16(200).u16(120).build()))
        assertTrue(recorder.sent.none { it.type == 28 })
        assertEquals(800, server.client!!.width)

        // A ping from the car is answered with its own counter.
        server.receive(Packet(128, hex("2a 00 00 00")))
        assertArrayEquals(hex("2a 00 00 00"), recorder.take(129).payload)
    }

    @Test
    fun compressionChoiceFollowsTheLauncher() {
        fun choose(override: Int, supported: Int): Int {
            val recorder = Recorder()
            val server = server(recorder)
            server.receive(version(1, 1, 0))
            server.receive(Packet(11, Writer().string("x").u16(800).u16(480).u16(0).u16(0).i32(0)
                .u8(2).u8(2).i32(override).u8(0).i32(supported).u8(0).build()))
            return recorder.running!!.frameCompression
        }
        assertEquals(MySpinServer.COMPRESSION_JPEG, choose(0, 7))
        assertEquals(MySpinServer.COMPRESSION_ZLIB, choose(0, 6))
        assertEquals(MySpinServer.COMPRESSION_NONE, choose(0, 4))
        assertEquals(MySpinServer.COMPRESSION_NONE, choose(4, 7))
        assertEquals(MySpinServer.COMPRESSION_ZLIB, choose(2, 7))
    }

    @Test
    fun unknownProtocolVersionIsRefused() {
        val recorder = Recorder()
        val server = server(recorder)
        try {
            server.receive(version(2, 0, 0))
            fail("2.0 must be refused")
        } catch (expected: MySpinProtocolException) {
            assertTrue(expected.message!!.contains("2.0.0"))
        }
    }

    @Test
    fun assemblerHandlesSplitAndJoinedChunks() {
        val first = Wire.encode(1, byteArrayOf(1, 0, 1, 0, 0, 0))
        val second = Wire.encode(128, byteArrayOf(5, 0, 0, 0))
        val joined = first + second
        val assembler = Wire.Assembler()

        assertTrue(assembler.feed(joined, 4).isEmpty())
        val rest = joined.copyOfRange(4, joined.size)
        val head = assembler.feed(rest, 12)
        assertEquals(listOf(1), head.map { it.type })
        val tail = assembler.feed(rest.copyOfRange(12, rest.size), rest.size - 12)
        assertEquals(listOf(128), tail.map { it.type })
        assertArrayEquals(byteArrayOf(5, 0, 0, 0), tail.single().payload)

        val both = Wire.Assembler().feed(joined, joined.size)
        assertEquals(listOf(1, 128), both.map { it.type })
    }
}
