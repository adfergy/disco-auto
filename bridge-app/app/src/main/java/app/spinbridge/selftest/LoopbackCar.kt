// SPDX-License-Identifier: MIT
package app.spinbridge.selftest

import app.spinbridge.myspin.MySpinServer
import app.spinbridge.myspin.Wire
import app.spinbridge.myspin.Wire.Reader
import app.spinbridge.myspin.Wire.Writer
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A stand-in for the car's head unit, on the other end of an in-memory pipe: it runs the client
 * side of the mySPIN handshake, pulls frames the way a head unit does, and can be told to send
 * touches. It exists so the phone side can be exercised end to end with no car attached.
 *
 * It is a model of a head unit built from the same reading of the protocol as the server, so it
 * proves the two halves agree with each other, not that either agrees with a real car. With
 * [discoverySport] it instead copies what the 2015 Discovery Sport's head unit was seen to do on
 * 15 November 2026: protocol 1.0.3, a 671x424 picture as zlib-packed RGB565, the same opening
 * messages in the same order, a GPS line every second, about eight frames a second, and no
 * picture requests until the driver opens the apps screen.
 */
class LoopbackCar(
    private val width: Int,
    private val height: Int,
    private val generation: MySpinServer.Generation,
    /** Pause between receiving a frame and asking for the next, standing in for decode time. */
    private val frameIntervalMillis: Long = 40,
    private val onFrame: (data: ByteArray, compression: Int, width: Int, height: Int) -> Unit,
    private val log: (String) -> Unit = {},
    private val discoverySport: Boolean = false,
    /** How long after the handshake the "driver" opens the apps screen and pictures are first asked for. */
    private val opensScreenAfterMillis: Long = 0,
) : Closeable {

    private val carIn = PipedInputStream(PIPE_SIZE)
    private val carOutSink = PipedInputStream(PIPE_SIZE)

    /** The phone's ends of the link, to hand to a MySpinSession. */
    val phoneInput: InputStream = carOutSink
    val phoneOutput: OutputStream = PipedOutputStream(carIn)

    private val toPhone: OutputStream = PipedOutputStream(carOutSink)
    private val closed = AtomicBoolean()

    /**
     * Everything for the phone goes through one thread that lives as long as the car does. A Java
     * pipe remembers which thread wrote last and reports "write end dead" to the reader once that
     * thread has finished, so writing from short-lived threads (a touch, a timer) would kill the
     * session.
     */
    private val outbox = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = false
    @Volatile private var pausedUntil = 0L
    @Volatile private var awaitingResume = false
    @Volatile var framesReceived = 0
        private set

    /** The compression a 1.0 head unit names in ClientInit and then expects in every frame. */
    private val v10Compression = if (discoverySport) MySpinServer.COMPRESSION_ZLIB else MySpinServer.COMPRESSION_JPEG

    fun start() {
        thread(name = "loopback-car", isDaemon = true) { run() }
        thread(name = "loopback-car-writer", isDaemon = true) {
            try {
                while (!closed.get()) {
                    val bytes = outbox.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    toPhone.write(bytes)
                    toPhone.flush()
                }
            } catch (failure: Exception) {
                if (!closed.get()) log("simulated car could not write: ${failure.javaClass.simpleName}: ${failure.message}")
            }
        }
    }

    private fun run() {
        val assembler = Wire.Assembler()
        val chunk = ByteArray(16_384)
        try {
            while (!closed.get()) {
                val count = carIn.read(chunk)
                if (count < 0) break
                for (packet in assembler.feed(chunk, count)) handle(packet)
            }
        } catch (failure: Exception) {
            if (!closed.get()) log("simulated car stopped: ${failure.javaClass.simpleName}: ${failure.message}")
        }
    }

    private var sawOpeningPing = false

    private fun handle(packet: Wire.Packet) {
        val reader = Reader(packet.payload)
        when (packet.type) {
            MySpinServer.TYPE_PING -> {
                val counter = reader.i32()
                if (!sawOpeningPing) {
                    // The phone's opening ping is what a head unit answers with its version.
                    sawOpeningPing = true
                    val (major, minor, build) = when (generation) {
                        MySpinServer.Generation.V1_0 -> Triple(1, 0, 3)   // what the Discovery Sport announces
                        MySpinServer.Generation.V1_1 -> Triple(1, 1, 0)
                        MySpinServer.Generation.V1_2 -> Triple(1, 2, 0)
                    }
                    send(MySpinServer.TYPE_CLIENT_PROTOCOL_VERSION, Writer().u16(major).u16(minor).u16(build))
                } else {
                    send(MySpinServer.TYPE_PING_ACK, Writer().i32(counter))
                }
            }
            MySpinServer.TYPE_SERVER_PROTOCOL_ACK -> send(MySpinServer.TYPE_CLIENT_INIT, clientInit())
            MySpinServer.TYPE_SERVER_INIT -> {
                log("simulated car got ServerInit: ${Wire.hex(packet.payload, 80)}")
                if (generation != MySpinServer.Generation.V1_2) beginRuntime()
            }
            MySpinServer.TYPE_SERVER_INIT_DONE -> {
                send(MySpinServer.TYPE_CLIENT_INIT_DONE, Writer().u8(1))
                beginRuntime()
            }
            MySpinServer.TYPE_FRAME -> {
                readFrame(reader)
                if (frameIntervalMillis > 0) Thread.sleep(frameIntervalMillis)
                if (System.currentTimeMillis() < pausedUntil) {
                    // "The driver has left the apps screen": a head unit then stops asking.
                    awaitingResume = true
                } else {
                    requestFrame(incremental = true)
                }
            }
            else -> Unit
        }
    }

    private fun clientInit(): Writer {
        val writer = Writer().string(if (discoverySport) "Jaguar-Land Rover" else "Loopback Car").u16(width).u16(height)
        return if (generation == MySpinServer.Generation.V1_0) {
            // The Discovery Sport: zlib, RGB565 little-endian, capabilities 5 (phone call + screen copy).
            writer.u8(v10Compression).u8(MySpinServer.PIXEL_RGB565).u8(2).i32(if (discoverySport) 5 else 3)
        } else {
            writer.u16(177).u16(100)
                .i32(0x1B)
                .u8(MySpinServer.PIXEL_RGB565).u8(2).i32(0).u8(0)
                .i32(7)
                .u8(0)
                .also { if (generation == MySpinServer.Generation.V1_2) it.u8(0) }
        }
    }

    private fun beginRuntime() {
        running = true
        if (discoverySport) {
            introduceLikeTheDiscoverySport()
        } else {
            // What a head unit volunteers once it is up: its Bluetooth address and day mode.
            val address = Writer().string("00:11:22:33:44:55").build()
            if (generation == MySpinServer.Generation.V1_0) {
                send(MySpinServer.TYPE_CLIENT_CUSTOM_MESSAGE_V10, Writer().u16(1).bytes(address))
            } else {
                send(MySpinServer.TYPE_CLIENT_CUSTOM_DATA, Writer().u8(1).u16(1).u16(address.size).bytes(address))
            }
            send(MySpinServer.TYPE_CLIENT_STATE, Writer().u8(2).u8(0))
        }
        if (opensScreenAfterMillis <= 0) {
            requestFrame(incremental = true)
        } else {
            thread(name = "loopback-car-opens-screen", isDaemon = true) {
                runCatching { Thread.sleep(opensScreenAfterMillis) }
                if (!closed.get()) requestFrame(incremental = true)
            }
        }
        if (discoverySport) thread(name = "loopback-car-gps", isDaemon = true) { gpsLoop() }
    }

    /** The messages the real head unit opened with, in its order (the address and position are made up). */
    private fun introduceLikeTheDiscoverySport() {
        fun info(type: Int, value: String) =
            send(MySpinServer.TYPE_CLIENT_CUSTOM_MESSAGE_V10, Writer().u16(type).string(value))
        send(MySpinServer.TYPE_CLIENT_VOICE_SUPPORT, Writer().u8(0).u8(0))
        info(7, "C01.00.10 P01.00.03 Sep 26 2014")
        info(1, "00:11:22:33:44:55")
        send(MySpinServer.TYPE_SET_BLOCK_MODE, Writer().u8(3))
        info(2, "version=JLR_2.5R04_18_LVLC_SW_18_Sep_2015")
        info(3, "host=SIMULATED")
        info(4, "user=SIMULATED")
        info(5, "date=Fri 09/18/2015")
        info(6, "time=18:58:56.48")
        send(MySpinServer.TYPE_CLIENT_VOICE_SUPPORT, Writer().u8(2).u8(1))
        send(MySpinServer.TYPE_CLIENT_VOICE_STATUS, Writer().u8(1).u8(0))
        send(MySpinServer.TYPE_CLIENT_VOICE_STATUS, Writer().u8(1).u8(1))
        send(MySpinServer.TYPE_CLIENT_STATE, Writer().u8(2).u8(0))
        send(MySpinServer.TYPE_CLIENT_STATE, Writer().u8(1).u8(0))
    }

    private fun gpsLoop() {
        try {
            while (!closed.get()) {
                send(
                    MySpinServer.TYPE_POSITION_INFORMATION,
                    Writer().string("\$GPRMC,120000.000,A,0000.00000,N,00000.00000,E,000.0,000.0,010126,,,A*00")
                )
                Thread.sleep(1_000)
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun requestFrame(incremental: Boolean) {
        send(
            MySpinServer.TYPE_FRAME_REQUEST,
            Writer().u16(0).u16(0).u16(width).u16(height).u8(if (incremental) 1 else 0)
        )
    }

    private fun readFrame(reader: Reader) {
        val rectangles = reader.u8()
        repeat(rectangles) {
            reader.u16(); reader.u16()
            val frameWidth = reader.u16()
            val frameHeight = reader.u16()
            var compression = v10Compression
            if (generation != MySpinServer.Generation.V1_0) {
                reader.u8(); reader.u8()
                compression = reader.i32()
                reader.u8(); reader.u8(); reader.u8()
            }
            val data = reader.bytes(reader.i32())
            framesReceived++
            onFrame(data, compression, frameWidth, frameHeight)
        }
    }

    /** Sends one finger event, as the head unit's touchscreen would. */
    fun touch(action: Int, x: Int, y: Int, fingerId: Int = 0) {
        if (!running) return
        val writer = Writer().u8(1).u8(action).u16(x.coerceIn(0, width - 1)).u16(y.coerceIn(0, height - 1)).i32(fingerId)
        if (generation == MySpinServer.Generation.V1_2) writer.i32(0)
        send(MySpinServer.TYPE_TOUCH_EVENT, writer)
    }

    fun softkey(key: Int, pressed: Boolean) {
        if (running) send(MySpinServer.TYPE_SOFTKEY_EVENT, Writer().u8(if (pressed) 1 else 2).u8(key))
    }

    /** Stops asking for pictures for [millis], as a head unit does when the driver leaves the apps screen. */
    fun leaveScreen(millis: Long) {
        if (!running) return
        pausedUntil = System.currentTimeMillis() + millis
        thread(name = "loopback-car-returns", isDaemon = true) {
            runCatching { Thread.sleep(millis + 50) }
            if (!closed.get() && awaitingResume) {
                awaitingResume = false
                requestFrame(incremental = false)
            }
        }
    }

    private fun send(type: Int, writer: Writer) {
        if (!closed.get()) outbox.put(Wire.encode(type, writer.build()))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { toPhone.close() }
        runCatching { carIn.close() }
    }

    private companion object {
        const val PIPE_SIZE = 1 shl 20
    }
}
