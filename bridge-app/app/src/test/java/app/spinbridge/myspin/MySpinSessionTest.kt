// SPDX-License-Identifier: MIT
package app.spinbridge.myspin

import app.spinbridge.myspin.Wire.Writer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs a whole [MySpinSession] against a fake head unit on the other end of two pipes: the opening
 * ping, the handshake, the heartbeat, and the frame hand-off in both orders.
 */
class MySpinSessionTest {

    /** The car's end of the link: reads whole packets, writes whatever a test asks it to. */
    private class FakeCar(private val fromPhone: InputStream, private val toPhone: OutputStream) {
        val received = LinkedBlockingQueue<Wire.Packet>()

        init {
            thread(name = "fake-car-reader", isDaemon = true) {
                val assembler = Wire.Assembler()
                val chunk = ByteArray(4096)
                try {
                    while (true) {
                        val count = fromPhone.read(chunk)
                        if (count < 0) break
                        assembler.feed(chunk, count).forEach(received::put)
                    }
                } catch (_: Exception) {
                }
            }
        }

        fun send(type: Int, payload: ByteArray) {
            toPhone.write(Wire.encode(type, payload))
            toPhone.flush()
        }

        /** The next packet of [type], skipping anything else (pings mostly). */
        fun await(type: Int, seconds: Long = 5): Wire.Packet {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (true) {
                val left = deadline - System.nanoTime()
                val packet = received.poll(maxOf(left, 1), TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("the phone never sent type $type")
                if (packet.type == type) return packet
            }
        }

        fun handshake11() {
            send(1, Writer().u16(1).u16(1).u16(0).build())
            await(2)
            send(
                11,
                Writer().string("Land Rover").u16(800).u16(480).u16(177).u16(100).i32(0x1B)
                    .u8(2).u8(2).i32(0).u8(0).i32(7).u8(0).build()
            )
            await(12)
            await(14)
        }

        fun requestFrame(incremental: Boolean) =
            send(25, Writer().u16(0).u16(0).u16(800).u16(480).u8(if (incremental) 1 else 0).build())
    }

    private class Harness(staleFrameResendMillis: Long = 250) {
        private val phoneIn = PipedInputStream(1 shl 16)
        private val carOut = PipedOutputStream(phoneIn)
        private val carIn = PipedInputStream(1 shl 16)
        private val phoneOut = PipedOutputStream(carIn)

        val running = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var closeError: Throwable? = null
        val touches = LinkedBlockingQueue<List<MySpinServer.Touch>>()
        val wanted = LinkedBlockingQueue<Boolean>()
        val logs = LinkedBlockingQueue<String>()

        val session = MySpinSession(
            input = phoneIn,
            output = phoneOut,
            listener = object : MySpinSession.Listener {
                override fun onRunning(client: MySpinServer.Client) = running.countDown()
                override fun onFrameWanted(incremental: Boolean) { wanted.put(incremental) }
                override fun onTouch(touches: List<MySpinServer.Touch>) { this@Harness.touches.put(touches) }
                override fun onClosed(error: Throwable?) {
                    closeError = error
                    closed.countDown()
                }
            },
            log = { logs.put(it) },
            pingIntervalMillis = 50,
            idleTimeoutMillis = 2_000,
            handshakeTimeoutMillis = 2_000,
            staleFrameResendMillis = staleFrameResendMillis,
            tickMillis = 10,
        )
        val car = FakeCar(carIn, carOut)
    }

    @Test
    fun opensWithTheVerificationPingThenHandshakesAndHeartbeats() {
        val harness = Harness()
        harness.session.start()

        val first = harness.car.received.poll(5, TimeUnit.SECONDS)
        assertNotNull(first)
        assertEquals(128, first!!.type)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), first.payload)

        harness.car.handshake11()
        assertTrue(harness.running.await(5, TimeUnit.SECONDS))
        assertEquals("Land Rover", harness.session.client!!.name)

        // Heartbeat counters start at 1 and climb.
        val one = harness.car.await(128)
        val two = harness.car.await(128)
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), one.payload)
        assertArrayEquals(byteArrayOf(2, 0, 0, 0), two.payload)
        harness.car.send(129, two.payload)

        harness.session.close()
        assertTrue(harness.closed.await(5, TimeUnit.SECONDS))
        assertEquals(null, harness.closeError)
    }

    @Test
    fun frameOfferedBeforeTheRequestIsServedOnRequest() {
        val harness = Harness()
        harness.session.start()
        harness.car.handshake11()
        assertTrue(harness.running.await(5, TimeUnit.SECONDS))

        val jpeg = byteArrayOf(1, 2, 3, 4)
        assertTrue(harness.session.offerFrame(jpeg, MySpinServer.COMPRESSION_JPEG))
        harness.car.requestFrame(incremental = false)
        val frame = harness.car.await(26)
        assertArrayEquals(jpeg, frame.payload.copyOfRange(frame.payload.size - 4, frame.payload.size))
        assertEquals(1, harness.session.stats.framesSent)
        harness.session.close()
    }

    @Test
    fun requestBeforeTheFrameAsksTheSourceAndIsServedWhenItArrives() {
        val harness = Harness(staleFrameResendMillis = 60_000)
        harness.session.start()
        harness.car.handshake11()
        assertTrue(harness.running.await(5, TimeUnit.SECONDS))

        harness.car.requestFrame(incremental = true)
        assertEquals(true, harness.wanted.poll(5, TimeUnit.SECONDS))
        assertEquals(0, harness.session.stats.framesSent)

        harness.session.offerFrame(byteArrayOf(9, 9), MySpinServer.COMPRESSION_JPEG)
        harness.car.await(26)
        assertEquals(1, harness.session.stats.framesSent)
        harness.session.close()
    }

    @Test
    fun aStillPictureIsResentRatherThanLeavingTheCarWaiting() {
        val harness = Harness(staleFrameResendMillis = 80)
        harness.session.start()
        harness.car.handshake11()
        assertTrue(harness.running.await(5, TimeUnit.SECONDS))

        harness.session.offerFrame(byteArrayOf(5), MySpinServer.COMPRESSION_JPEG)
        harness.car.requestFrame(incremental = true)
        harness.car.await(26)
        // Same picture, new incremental request: nothing new to send, so it is resent after the delay.
        harness.car.requestFrame(incremental = true)
        harness.car.await(26)
        assertEquals(2, harness.session.stats.framesSent)
        // A non-incremental request is answered at once with the frame already sent.
        harness.car.requestFrame(incremental = false)
        harness.car.await(26)
        assertEquals(3, harness.session.stats.framesSent)
        harness.session.close()
    }

    @Test
    fun touchesReachTheListenerAndSilenceEndsTheSession() {
        val harness = Harness()
        harness.session.start()
        harness.car.handshake11()
        assertTrue(harness.running.await(5, TimeUnit.SECONDS))

        harness.car.send(33, Writer().u8(1).u8(1).u16(400).u16(240).i32(0).build())
        assertEquals(listOf(MySpinServer.Touch(1, 400, 240, 0)), harness.touches.poll(5, TimeUnit.SECONDS))

        // The fake car now says nothing (it never acks pings): the idle timeout must fire.
        assertTrue(harness.closed.await(6, TimeUnit.SECONDS))
        assertTrue(harness.closeError is MySpinProtocolException)
    }

    @Test
    fun noHandshakeTimesOut() {
        val harness = Harness()
        harness.session.start()
        assertTrue(harness.closed.await(6, TimeUnit.SECONDS))
        assertTrue(harness.closeError!!.message!!.contains("no mySPIN handshake"))
    }
}
