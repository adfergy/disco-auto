// SPDX-License-Identifier: MIT
package app.spinbridge.myspin

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs one mySPIN session over a pair of streams (the USB accessory pipe in the car, a loopback
 * pipe in tests): a reader thread feeds [MySpinServer], a writer thread drains an outbound queue,
 * and a timer sends the 500 ms heartbeat and watches for silence.
 *
 * Frames: the car pulls, the picture source pushes, and they meet in a one-slot cache.
 * - a frame offered while a request is open goes out at once;
 * - a request that finds an unsent frame takes it at once;
 * - a request for the whole picture (not incremental) gets the latest frame again at once;
 * - otherwise the request waits for the next frame, but is answered with the same frame after
 *   [staleFrameResendMillis] so that a still picture never leaves the car waiting.
 */
class MySpinSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val listener: Listener,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pingIntervalMillis: Long = PING_INTERVAL_MS,
    private val idleTimeoutMillis: Long = IDLE_TIMEOUT_MS,
    private val handshakeTimeoutMillis: Long = HANDSHAKE_TIMEOUT_MS,
    private val staleFrameResendMillis: Long = STALE_FRAME_RESEND_MS,
    private val tickMillis: Long = TICK_MS,
    private val readChunkSize: Int = READ_CHUNK,
) : Closeable {

    interface Listener {
        fun onRunning(client: MySpinServer.Client) {}

        /** The car asked for a frame and none is waiting; a source that renders on demand can do so now. */
        fun onFrameWanted(incremental: Boolean) {}
        fun onTouch(touches: List<MySpinServer.Touch>) {}
        fun onSoftkey(key: Int, pressed: Boolean) {}
        fun onClientState(kind: Int, value: Int) {}
        fun onFrameFormatChanged(client: MySpinServer.Client) {}
        fun onCustomString(type: Int, value: String) {}
        fun onCustomKey(action: Int, key: Int) {}
        fun onAudioStatus(requestId: Long, status: Int, result: Int) {}
        fun onPosition(nmea: String) {}

        /** The session ended; [error] is null when it was closed on purpose or the car hung up cleanly. */
        fun onClosed(error: Throwable?) {}
    }

    class Stats {
        @Volatile var framesSent = 0
        @Volatile var frameBytesSent = 0L
        @Volatile var frameRequests = 0
        @Volatile var packetsReceived = 0
        @Volatile var touchPackets = 0
        @Volatile var pingsSent = 0
        @Volatile var pingAcks = 0
    }

    val stats = Stats()

    private val lock = Any()
    private val closed = AtomicBoolean()
    private val outbound = LinkedBlockingQueue<ByteArray>()
    private var timer: ScheduledExecutorService? = null
    private val startedAt = clock()

    // Guarded by lock.
    private var latestFrame: ByteArray? = null
    private var latestCompression = MySpinServer.COMPRESSION_JPEG
    private var latestFrameSent = true
    private var requestedAt = 0L
    private var lastPingAt = 0L
    private var pingCounter = 1
    private val loggedPerType = HashMap<Int, Int>()

    @Volatile private var lastReceiveAt = clock()
    @Volatile private var rawBytesReceived = 0L
    @Volatile private var handshakeDone = false
    private var rawChunksLogged = 0
    private var lastWaitingLogAt = startedAt

    private val server = MySpinServer(
        send = { packet -> enqueue(packet) },
        listener = object : MySpinServer.Listener {
            override fun onLog(line: String) = log(line)

            override fun onRunning(client: MySpinServer.Client) {
                log("handshake complete: ${client.describe()}")
                listener.onRunning(client)
            }

            override fun onFrameRequest(x: Int, y: Int, width: Int, height: Int, incremental: Boolean) {
                stats.frameRequests++
                if (stats.frameRequests <= VERBOSE_FIRST) {
                    log("frame request #${stats.frameRequests}: $x,$y ${width}x$height incremental=$incremental")
                }
                requestedAt = clock()
                val frame = latestFrame
                if (frame != null && (!latestFrameSent || !incremental)) {
                    sendLocked(frame)
                } else {
                    listener.onFrameWanted(incremental)
                }
            }

            override fun onTouch(touches: List<MySpinServer.Touch>) {
                stats.touchPackets++
                listener.onTouch(touches)
            }

            override fun onSoftkey(key: Int, pressed: Boolean) = listener.onSoftkey(key, pressed)
            override fun onClientState(kind: Int, value: Int) = listener.onClientState(kind, value)
            override fun onFrameFormatChanged(client: MySpinServer.Client) {
                // The cached frame was encoded for the old format; never send it under the new one.
                latestFrame = null
                latestFrameSent = true
                listener.onFrameFormatChanged(client)
            }
            override fun onCustomString(type: Int, value: String) = listener.onCustomString(type, value)
            override fun onCustomKey(action: Int, key: Int) = listener.onCustomKey(action, key)
            override fun onAudioStatus(requestId: Long, status: Int, result: Int) =
                listener.onAudioStatus(requestId, status, result)
            override fun onPosition(nmea: String) = listener.onPosition(nmea)
        }
    )

    val isOpen: Boolean get() = !closed.get()
    val client: MySpinServer.Client? get() = synchronized(lock) { server.client }
    val isRunning: Boolean get() = synchronized(lock) { server.state == MySpinServer.State.RUNNING }

    fun start() {
        // The launcher opens every connection with this ping (type 128, counter 0) before the car
        // has said anything; captures of a real phone show the same 13 bytes.
        outbound.put(VERIFICATION_PING)
        log("tx verification ping: ${Wire.hex(VERIFICATION_PING)}")
        // The timer first: a reader that fails at once must find it, or finish() could not stop it.
        timer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "myspin-timer").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::tick, tickMillis, tickMillis, TimeUnit.MILLISECONDS) }
        thread(name = "myspin-writer", isDaemon = true) { writeLoop() }
        thread(name = "myspin-reader", isDaemon = true) { readLoop() }
    }

    /**
     * Hands in the newest picture, already encoded for [compression] at the car's screen size.
     * False once the session is over.
     */
    fun offerFrame(data: ByteArray, compression: Int): Boolean {
        if (closed.get()) return false
        try {
            synchronized(lock) {
                latestFrame = data
                latestCompression = compression
                latestFrameSent = false
                if (server.state == MySpinServer.State.RUNNING && server.hasPendingFrameRequest) sendLocked(data)
            }
        } catch (failure: Throwable) {
            finish(failure)
            return false
        }
        return true
    }

    fun sendBluetoothName(name: String) = guarded { server.sendBluetoothName(name) }

    fun sendServerState(kind: Int, active: Boolean) = guarded { server.sendServerState(kind, active) }

    /**
     * Asks the car for (or returns) its speakers; false on protocol 1.0 or before the handshake.
     * The launcher uses one fixed request id per kind of audio, 1 for main audio, so this does too.
     */
    fun sendAudioFocus(request: Boolean): Boolean {
        var sent = false
        guarded {
            sent = server.sendAudioFocus(
                MySpinServer.AUDIO_REQUEST_ID_MAIN,
                if (request) MySpinServer.AUDIO_REQUEST else MySpinServer.AUDIO_RELEASE,
                MySpinServer.AUDIO_TYPE_MAIN
            )
        }
        return sent
    }

    private inline fun guarded(action: () -> Unit) {
        if (closed.get()) return
        try {
            synchronized(lock) { action() }
        } catch (failure: Throwable) {
            finish(failure)
        }
    }

    /** With the lock held. */
    private fun sendLocked(frame: ByteArray) {
        if (server.sendFrame(frame, latestCompression)) {
            latestFrameSent = true
            stats.framesSent++
            stats.frameBytesSent += frame.size
            if (stats.framesSent <= VERBOSE_FIRST) {
                log("frame #${stats.framesSent} sent: ${frame.size} bytes ${MySpinServer.compressionName(latestCompression)}")
            }
        }
    }

    private fun enqueue(packet: Wire.Packet) {
        if (shouldLog(packet.type, outboundTypes = true)) {
            log(
                "tx type ${packet.type} (${packet.payload.size} bytes)" +
                    if (packet.type in PRIVATE_TYPES) "" else ": ${Wire.hex(packet.payload, 48)}"
            )
        }
        if (packet.type == MySpinServer.TYPE_PING) stats.pingsSent++
        outbound.put(Wire.encode(packet.type, packet.payload))
    }

    private fun writeLoop() {
        try {
            while (!closed.get()) {
                val bytes = outbound.poll(200, TimeUnit.MILLISECONDS) ?: continue
                output.write(bytes)
                output.flush()
            }
        } catch (failure: Throwable) {
            finish(failure)
        }
    }

    private fun readLoop() {
        val assembler = Wire.Assembler()
        val chunk = ByteArray(readChunkSize)
        try {
            while (!closed.get()) {
                val count = input.read(chunk, 0, chunk.size)
                if (count < 0) break
                if (count == 0) continue
                lastReceiveAt = clock()
                rawBytesReceived += count
                // The first bytes from the car, verbatim: if it speaks something other than the
                // framing we expect, this is the only place that shows what. Only until the
                // handshake is done: after it the car sends its position and Bluetooth address,
                // which do not belong in a log that may be passed on.
                if (rawChunksLogged < RAW_CHUNKS_LOGGED && !handshakeDone) {
                    rawChunksLogged++
                    log("rx raw chunk $rawChunksLogged ($count bytes): ${Wire.hex(chunk.copyOf(count), 96)}")
                }
                for (packet in assembler.feed(chunk, count)) {
                    stats.packetsReceived++
                    if (packet.type == MySpinServer.TYPE_PING_ACK) stats.pingAcks++
                    if (shouldLog(packet.type, outboundTypes = false)) {
                        log(
                            "rx type ${packet.type} (${packet.payload.size} bytes)" +
                                if (packet.type in PRIVATE_TYPES) "" else ": ${Wire.hex(packet.payload, 96)}"
                        )
                    }
                    synchronized(lock) {
                        server.receive(packet)
                        if (server.state == MySpinServer.State.RUNNING) handshakeDone = true
                    }
                }
            }
            finish(null)
        } catch (failure: Throwable) {
            finish(failure)
        }
    }

    /** Logs every packet of a type at first, then stops, so a long drive does not fill the disk. */
    private fun shouldLog(type: Int, outboundTypes: Boolean): Boolean {
        val quiet = type == MySpinServer.TYPE_FRAME || type == MySpinServer.TYPE_FRAME_REQUEST ||
            type == MySpinServer.TYPE_POSITION_INFORMATION
        if (quiet) return false
        val key = if (outboundTypes) type or 0x1000 else type
        val limit = when (type) {
            MySpinServer.TYPE_PING, MySpinServer.TYPE_PING_ACK -> 6
            MySpinServer.TYPE_TOUCH_EVENT -> 200
            else -> 400
        }
        val seen = synchronized(loggedPerType) { (loggedPerType[key] ?: 0).also { loggedPerType[key] = it + 1 } }
        return seen < limit
    }

    private fun tick() {
        if (closed.get()) return
        val now = clock()
        try {
            synchronized(lock) {
                if (server.state != MySpinServer.State.RUNNING) {
                    if (now - lastWaitingLogAt >= WAITING_LOG_MS) {
                        lastWaitingLogAt = now
                        log(
                            "still waiting for the car (${(now - startedAt) / 1000} s): state=${server.state}, " +
                                "$rawBytesReceived bytes and ${stats.packetsReceived} packets received so far"
                        )
                    }
                    if (handshakeTimeoutMillis > 0 && now - startedAt > handshakeTimeoutMillis) {
                        throw MySpinProtocolException(
                            "no mySPIN handshake from the car in ${handshakeTimeoutMillis / 1000} s " +
                                "(received ${stats.packetsReceived} packets)"
                        )
                    }
                    return
                }
                if (idleTimeoutMillis > 0 && now - lastReceiveAt > idleTimeoutMillis) {
                    throw MySpinProtocolException("nothing from the car for ${idleTimeoutMillis / 1000} s")
                }
                if (now - lastPingAt >= pingIntervalMillis) {
                    lastPingAt = now
                    server.ping(pingCounter++)
                }
                val frame = latestFrame
                if (frame != null && latestFrameSent && server.hasPendingFrameRequest &&
                    now - requestedAt >= staleFrameResendMillis
                ) {
                    sendLocked(frame)
                }
            }
        } catch (failure: Throwable) {
            finish(failure)
        }
    }

    private fun finish(error: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        // shutdown(), not shutdownNow(): this can be running on the timer thread itself, and an
        // interrupt delivered to it here would abort the notifications below.
        timer?.shutdown()
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching {
            log(
                "session closed" + (error?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: "") +
                    " | frames=${stats.framesSent} (${stats.frameBytesSent / 1024} KiB) requests=${stats.frameRequests}" +
                    " rx=${stats.packetsReceived} touch=${stats.touchPackets} pings=${stats.pingsSent}/${stats.pingAcks}"
            )
        }
        listener.onClosed(error)
    }

    override fun close() = finish(null)

    companion object {
        const val PING_INTERVAL_MS = 500L

        /**
         * The launcher gives up after 2 s of silence. We are more patient: dropping the link costs
         * the driver a replug, and a head unit that is merely slow does not deserve that.
         */
        const val IDLE_TIMEOUT_MS = 6_000L
        const val HANDSHAKE_TIMEOUT_MS = 20_000L
        const val STALE_FRAME_RESEND_MS = 250L
        const val TICK_MS = 50L

        /** The size the launcher reads the accessory with. */
        const val READ_CHUNK = 16_384
        private const val VERBOSE_FIRST = 12
        private const val RAW_CHUNKS_LOGGED = 12

        /** Packets whose contents are personal (the car's Bluetooth address among its info strings): logged by size only. */
        private val PRIVATE_TYPES = setOf(
            MySpinServer.TYPE_CLIENT_CUSTOM_MESSAGE_V10, MySpinServer.TYPE_CLIENT_CUSTOM_DATA,
            // ...and our replies, which carry the phone's Bluetooth name.
            MySpinServer.TYPE_SERVER_CUSTOM_MESSAGE_V10, MySpinServer.TYPE_SERVER_CUSTOM_DATA,
        )
        private const val WAITING_LOG_MS = 5_000L

        /** `80 | 00 00 00 00 | 04 00 00 00 | 00 00 00 00`: Ping with counter 0. */
        val VERIFICATION_PING: ByteArray = byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0)
    }
}
