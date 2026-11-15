// SPDX-License-Identifier: MIT
package app.spinbridge.myspin

import app.spinbridge.myspin.Wire.Packet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays what the 2015 Discovery Sport's head unit actually sent on 15 November 2026 (the bytes
 * are from that session's log, car-logs/2026-11-15-test1; only the car's Bluetooth address is
 * replaced) and pins what the app answered, since that exchange is known to work in the car.
 */
class DiscoverySportReplayTest {

    private class Recorder : MySpinServer.Listener {
        val sent = ArrayList<Packet>()
        val logs = ArrayList<String>()
        var running: MySpinServer.Client? = null
        val touches = ArrayList<MySpinServer.Touch>()
        val states = ArrayList<Pair<Int, Int>>()
        val strings = ArrayList<Pair<Int, String>>()
        val positions = ArrayList<String>()
        val carMessages = ArrayList<Triple<Int, Boolean, String>>()
        var frameRequests = 0

        override fun onLog(line: String) { logs += line }
        override fun onRunning(client: MySpinServer.Client) { running = client }
        override fun onFrameRequest(x: Int, y: Int, width: Int, height: Int, incremental: Boolean) { frameRequests++ }
        override fun onTouch(touches: List<MySpinServer.Touch>) { this.touches += touches }
        override fun onClientState(kind: Int, value: Int) { states += kind to value }
        override fun onCustomString(type: Int, value: String) { strings += type to value }
        override fun onPosition(nmea: String) { positions += nmea }
        override fun onCarMessage(type: Int, isKey: Boolean, summary: String) { carMessages += Triple(type, isKey, summary) }
    }

    private val recorder = Recorder()
    private val server = MySpinServer(
        send = { recorder.sent += it },
        listener = recorder,
        nowMillis = { 1_795_000_000_000L },
        utcOffsetMillis = { 0 },
    )
    private val assembler = Wire.Assembler()

    private fun bytes(hex: String): ByteArray =
        hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    /** Feeds raw bytes exactly as they came off the USB pipe. */
    private fun fromCar(hex: String) {
        val chunk = bytes(hex)
        for (packet in assembler.feed(chunk, chunk.size)) server.receive(packet)
    }

    private fun handshake() {
        // "rx raw chunk 1": ClientProtocolVersion 1.0.3
        fromCar("01 00 00 00 00 06 00 00 00 01 00 00 00 03 00")
        // "rx raw chunk 2": ClientInit
        fromCar(
            "0b 00 00 00 00 1f 00 00 00 01 11 00 4a 61 67 75 61 72 2d 4c 61 6e 64 20 52 6f 76 65 72 " +
                "9f 02 a8 01 02 02 02 05 00 00 00"
        )
    }

    @Test
    fun `the handshake is understood and answered as it was in the car`() {
        handshake()
        val client = recorder.running!!
        assertEquals("Jaguar-Land Rover", client.name)
        assertEquals("1.0.3", client.version)
        assertEquals(MySpinServer.Generation.V1_0, client.generation)
        assertEquals(671, client.width)
        assertEquals(424, client.height)
        assertEquals(MySpinServer.PIXEL_RGB565, client.pixelFormat)
        assertEquals(2, client.endianness)
        assertEquals(MySpinServer.COMPRESSION_ZLIB, client.frameCompression)
        assertEquals(5, client.capabilities)
        assertFalse("1.0 has no audio handling", client.hasAudioHandling)
        assertTrue(client.describe().contains("capabilities=0x5 (InitiatePhoneCall+ScreenCopy)"))

        // What went back, in order: version ack, init ack, ServerInit, the time, then the two
        // state messages that start a session.
        assertEquals(listOf(2, 12, 14, 46, 36, 36), recorder.sent.map { it.type })
        assertArrayEquals(bytes("01"), recorder.sent[0].payload)
        assertArrayEquals(bytes("01"), recorder.sent[1].payload)
        assertArrayEquals(
            bytes("01 15 00 6d 79 53 50 49 4e 20 41 6e 64 72 6f 69 64 20 53 65 72 76 65 72 01 01 01 01 00 00 00"),
            recorder.sent[2].payload
        )
        assertArrayEquals(bytes("02 00"), recorder.sent[4].payload)
        assertArrayEquals(bytes("03 00"), recorder.sent[5].payload)
        assertEquals(MySpinServer.State.RUNNING, server.state)
    }

    @Test
    fun `the car's opening messages are read as it meant them`() {
        handshake()
        fromCar("3d 00 00 00 00 02 00 00 00 00 00")                                     // voice support 0/0
        fromCar(                                                                       // info string 7
            "2b 00 00 00 00 24 00 00 00 07 00 01 1f 00 43 30 31 2e 30 30 2e 31 30 20 50 30 31 2e 30 30 2e 30 33 " +
                "20 53 65 70 20 32 36 20 32 30 31 34"
        )
        fromCar("2b 00 00 00 00 16 00 00 00 01 00 01 11 00 30 30 3a 31 31 3a 32 32 3a 33 33 3a 34 34 3a 35 35")   // address
        fromCar("27 00 00 00 00 01 00 00 00 03")                                        // block mode 3
        fromCar("3d 00 00 00 00 02 00 00 00 02 01")                                     // voice support 2/1
        // Two packets in one chunk, as they arrived: voice status 1/0 then 1/1.
        fromCar("3f 00 00 00 00 02 00 00 00 01 00 3f 00 00 00 00 02 00 00 00 01 01")

        assertEquals(listOf(7 to "C01.00.10 P01.00.03 Sep 26 2014", 1 to "00:11:22:33:44:55"), recorder.strings)
        // Reported, in order; the block mode is housekeeping and only logged.
        assertEquals(
            listOf(
                "car voice support 0 (not supported) constraint 0",
                "car info string 7: \"C01.00.10 P01.00.03 Sep 26 2014\"",
                "car info string 1: \"00:11:22:33:44:55\"",
                "car voice support 2 (full hands-free audio) constraint 1 (microphone and speakers together only)",
                "car voice session status 1 (idle) constraint 0 (none)",
                "car voice session status 1 (idle) constraint 1 (no hands-free connection)",
            ),
            recorder.carMessages.map { it.third }
        )
        assertTrue("none of these is a key", recorder.carMessages.none { it.second })
        assertTrue(recorder.logs.any { it.startsWith("car block mode 3") })
        // None of it is answered: the car has never been sent anything in reply to these.
        assertEquals(listOf(2, 12, 14, 46, 36, 36), recorder.sent.map { it.type })
    }

    @Test
    fun `a listener that fails on a car message does not break the session`() {
        val failing = object : MySpinServer.Listener {
            val logs = ArrayList<String>()
            var softkeys = 0
            override fun onLog(line: String) { logs += line }
            override fun onRunning(client: MySpinServer.Client) {}
            override fun onFrameRequest(x: Int, y: Int, width: Int, height: Int, incremental: Boolean) {}
            override fun onSoftkey(key: Int, pressed: Boolean) { softkeys++ }
            override fun onCarMessage(type: Int, isKey: Boolean, summary: String): Unit = throw IllegalStateException("boom")
        }
        val fragile = MySpinServer(send = {}, listener = failing)
        val feed = Wire.Assembler()
        for (hex in listOf(
            "01 00 00 00 00 06 00 00 00 01 00 00 00 03 00",
            "0b 00 00 00 00 1f 00 00 00 01 11 00 4a 61 67 75 61 72 2d 4c 61 6e 64 20 52 6f 76 65 72 9f 02 a8 01 02 02 02 05 00 00 00",
            "1f 00 00 00 00 02 00 00 00 01 01",
            "1f 00 00 00 00 02 00 00 00 02 01",
        )) {
            val chunk = bytes(hex)
            for (packet in feed.feed(chunk, chunk.size)) fragile.receive(packet)
        }
        assertEquals(2, failing.softkeys)
        assertEquals(MySpinServer.State.RUNNING, fragile.state)
        assertTrue(failing.logs.any { it.startsWith("a listener failed on a car message") })
    }

    @Test
    fun `touch position and state arrive as their own callbacks, not as car messages`() {
        handshake()
        fromCar("21 00 00 00 00 0a 00 00 00 01 01 1d 01 05 01 00 00 00 00")              // touch down at 285,261
        fromCar("21 00 00 00 00 0a 00 00 00 01 02 1d 01 05 01 00 00 00 00")              // touch up
        fromCar("23 00 00 00 00 02 00 00 00 02 00")                                     // day mode
        fromCar("23 00 00 00 00 02 00 00 00 01 00")                                     // stationary
        fromCar("19 00 00 00 00 09 00 00 00 00 00 00 00 9f 02 a8 01 01")                 // frame request, incremental
        val nmea = "\$GPRMC,120000.000,A,0000.00000,N,00000.00000,E,000.0,000.0,010126,,,A*00"
        val position = Wire.Writer().string(nmea).build()
        server.receive(Packet(MySpinServer.TYPE_POSITION_INFORMATION, position))

        assertEquals(
            listOf(
                MySpinServer.Touch(MySpinServer.TOUCH_DOWN, 285, 261, 0),
                MySpinServer.Touch(MySpinServer.TOUCH_UP, 285, 261, 0),
            ),
            recorder.touches
        )
        assertEquals(listOf(2 to 0, 1 to 0), recorder.states)
        assertEquals(1, recorder.frameRequests)
        assertEquals(listOf(nmea), recorder.positions)
        assertTrue("routine traffic is not reported as an event", recorder.carMessages.isEmpty())
    }

    @Test
    fun `a frame goes out in the 1_0 layout the car drew`() {
        handshake()
        fromCar("19 00 00 00 00 09 00 00 00 00 00 00 00 9f 02 a8 01 01")
        recorder.sent.clear()
        val picture = byteArrayOf(0x78, 0x01, 0x03, 0x00)
        assertTrue(server.sendFrame(picture, MySpinServer.COMPRESSION_ZLIB))
        val frame = recorder.sent.single()
        assertEquals(MySpinServer.TYPE_FRAME, frame.type)
        // one rectangle, x 0, y 0, 671 wide, 424 high, then the length and the bytes; no format fields in 1.0
        assertArrayEquals(bytes("01 00 00 00 00 9f 02 a8 01 04 00 00 00 78 01 03 00"), frame.payload)
        assertFalse("one frame per request", server.sendFrame(picture, MySpinServer.COMPRESSION_ZLIB))
    }

    @Test
    fun `anything else the car says is reported, keys marked as keys`() {
        handshake()
        fromCar("1f 00 00 00 00 02 00 00 00 01 01")                                     // softkey Home pressed
        fromCar("1f 00 00 00 00 02 00 00 00 02 01")                                     // softkey Home released
        fromCar("3f 00 00 00 00 02 00 00 00 03 00")                                     // voice session active
        fromCar("63 00 00 00 00 02 00 00 00 aa bb")                                     // a type this code does not know
        // A hard key in the 1.2 form, which this car's protocol does not have but which must not be lost.
        fromCar("43 00 00 00 00 06 00 00 00 05 01 00 01 00 21")

        assertEquals(
            listOf(
                Triple(31, true, "softkey HOME pressed"),
                Triple(31, true, "softkey HOME released"),
                Triple(63, false, "car voice session status 3 (active) constraint 0 (none)"),
                Triple(99, false, "unhandled type 99: aa bb"),
                Triple(67, true, "hard key 33 (DPadRight) press"),
            ),
            recorder.carMessages
        )
        assertFalse("1.0 has no audio message to send", server.sendAudioFocus(1, MySpinServer.AUDIO_REQUEST, MySpinServer.AUDIO_TYPE_MAIN))
    }

    @Test
    fun `messages during the handshake are not reported as events`() {
        fromCar("01 00 00 00 00 06 00 00 00 01 00 00 00 03 00")
        fromCar("3d 00 00 00 00 02 00 00 00 00 00")   // voice support before ClientInit
        assertTrue(recorder.carMessages.isEmpty())
        assertTrue(recorder.logs.any { it.startsWith("car voice support 0") })
    }
}
