// SPDX-License-Identifier: MIT
package app.spinbridge.myspin

import app.spinbridge.myspin.Wire.Packet
import app.spinbridge.myspin.Wire.Reader
import app.spinbridge.myspin.Wire.Writer

/**
 * The phone ("server") side of a mySPIN session, for protocol 1.0.x, 1.1.x and 1.2.x, behaving as
 * the Land Rover InControl Apps launcher 2.10.0 does.
 *
 * No I/O and no threads: [receive] is fed every packet from the car and everything to transmit
 * goes out through [send]. That keeps the whole exchange testable on the JVM against a fake car.
 */
class MySpinServer(
    private val send: (Packet) -> Unit,
    private val listener: Listener,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val utcOffsetMillis: (Long) -> Int = { java.util.TimeZone.getDefault().getOffset(it) },
) {
    enum class Generation(val label: String) { V1_0("1.0"), V1_1("1.1"), V1_2("1.2") }

    enum class State { AWAIT_VERSION, AWAIT_INIT, RUNNING }

    /** What the car said about itself in ClientProtocolVersion and ClientInit. */
    data class Client(
        val major: Int,
        val minor: Int,
        val build: Int,
        val generation: Generation,
        val name: String,
        val width: Int,
        val height: Int,
        val physicalWidthMm: Int,
        val physicalHeightMm: Int,
        val capabilities: Int,
        val pixelFormat: Int,
        val endianness: Int,
        /** 1.0: the single compression the car named. 1.1+: its override, 0 when it has none. */
        val overrideCompression: Int,
        /** 1.1+ bitmask of [COMPRESSION_JPEG], [COMPRESSION_ZLIB], [COMPRESSION_UNCOMPRESSED]. */
        val supportedCompressions: Int,
        val compressionLevel: Int,
        val customData: List<Int>,
        val nonTouch: List<Int>,
    ) {
        val version: String get() = "$major.$minor.$build"

        /**
         * The compression frames must use: [COMPRESSION_NONE], [COMPRESSION_JPEG] or
         * [COMPRESSION_ZLIB]. Same rule as the launcher (`iu.t()`): an override wins, otherwise
         * JPEG if the car lists it, then zlib, then raw pixels.
         */
        val frameCompression: Int
            get() = when {
                generation == Generation.V1_0 -> overrideCompression
                overrideCompression == COMPRESSION_UNCOMPRESSED -> COMPRESSION_NONE
                overrideCompression != 0 -> overrideCompression
                supportedCompressions and COMPRESSION_JPEG != 0 -> COMPRESSION_JPEG
                supportedCompressions and COMPRESSION_ZLIB != 0 -> COMPRESSION_ZLIB
                else -> COMPRESSION_NONE
            }

        val hasAudioHandling: Boolean get() = capabilities and CAPABILITY_AUDIO_HANDLING != 0

        fun describe(): String =
            "\"$name\" protocol $version, ${width}x$height px (${physicalWidthMm}x$physicalHeightMm mm), " +
                "pixel ${pixelFormatName(pixelFormat)}/${if (endianness == 2) "LE" else "BE"}, " +
                "compression override=$overrideCompression supported=$supportedCompressions level=$compressionLevel " +
                "-> ${compressionName(frameCompression)}, capabilities=0x${capabilities.toString(16)} (${capabilityNames(capabilities)})" +
                (if (customData.isNotEmpty()) ", customData=$customData" else "") +
                (if (nonTouch.isNotEmpty()) ", nonTouch=$nonTouch" else "")
    }

    data class Touch(val action: Int, val x: Int, val y: Int, val fingerId: Long)

    interface Listener {
        fun onLog(line: String) {}

        /** Handshake complete. Frames may be requested from now on. */
        fun onRunning(client: Client)

        /** The car wants one frame. [incremental] false means it wants the whole picture again. */
        fun onFrameRequest(x: Int, y: Int, width: Int, height: Int, incremental: Boolean)

        fun onTouch(touches: List<Touch>) {}

        /** [key] is one of the SOFTKEY_ constants. */
        fun onSoftkey(key: Int, pressed: Boolean) {}

        /** [kind] 1 = movement ([value] 0 means stationary), 2 = day/night (non-zero is night). */
        fun onClientState(kind: Int, value: Int) {}

        /** The car changed pixel format or compression; [client] is the updated description. */
        fun onFrameFormatChanged(client: Client) {}

        /** Type 1 is the car's Bluetooth MAC address, 2..7 are firmware version strings. */
        fun onCustomString(type: Int, value: String) {}

        /** A hard key (knob, D-pad, push-to-talk): see the KEY_ constants. */
        fun onCustomKey(action: Int, key: Int) {}

        fun onAudioStatus(requestId: Long, status: Int, result: Int) {}
        fun onPosition(nmea: String) {}

        /**
         * What else the car says once the session is running, as one line of text: softkeys and
         * hard keys ([isKey] true; they also have their own callbacks above), voice and phone
         * status, audio answers, custom data, and packet types this code does not know. Not
         * pictures, touches, pings, GPS positions, the moving and day/night states, or the car's
         * housekeeping (picture format, block mode, language, vehicle data). For diagnostics that
         * want to notice what a button on the car causes. Called with the session's lock held: do
         * not block, and do not call back into the session from another thread while handling it.
         */
        fun onCarMessage(type: Int, isKey: Boolean, summary: String) {}
    }

    var state = State.AWAIT_VERSION
        private set
    var client: Client? = null
        private set

    private var version: Triple<Int, Int, Int>? = null
    private var generation: Generation? = null
    private var serverInitDone = false
    private var clientInitDone = false
    private var pendingFrameRequest = false
    private var lastPingSent = 0
    private var blockMode = 1
    private var settingsSeen = false
    private var language = "eng"

    val hasPendingFrameRequest: Boolean get() = pendingFrameRequest

    /** Interprets one packet from the car. Throws [MySpinProtocolException] when the session cannot go on. */
    fun receive(packet: Packet) {
        val reader = Reader(packet.payload)
        when (state) {
            State.AWAIT_VERSION -> receiveVersion(packet, reader)
            State.AWAIT_INIT -> receiveInit(packet, reader)
            State.RUNNING -> receiveRuntime(packet, reader)
        }
    }

    private fun receiveVersion(packet: Packet, reader: Reader) {
        when (packet.type) {
            TYPE_CLIENT_PROTOCOL_VERSION -> {
                val major = reader.u16()
                val minor = reader.u16()
                val build = reader.u16()
                // The launcher concatenates the three numbers as decimal text ("1","1","0" -> 110).
                val code = "$major$minor$build".toIntOrNull() ?: -1
                val chosen = when (code) {
                    in 100..109 -> Generation.V1_0
                    in 110..119 -> Generation.V1_1
                    in 120..129 -> Generation.V1_2
                    else -> throw MySpinProtocolException(
                        "the car speaks mySPIN $major.$minor.$build; only 1.0.x, 1.1.x and 1.2.x are implemented"
                    )
                }
                version = Triple(major, minor, build)
                generation = chosen
                listener.onLog("car protocol version $major.$minor.$build -> using the ${chosen.label} layouts")
                emit(TYPE_SERVER_PROTOCOL_ACK, Writer().u8(1))
                state = State.AWAIT_INIT
            }
            TYPE_PING_ACK, TYPE_PING -> handlePing(packet.type, reader)
            else -> listener.onLog("ignored type ${packet.type} before the protocol version: ${Wire.hex(packet.payload)}")
        }
    }

    private fun receiveInit(packet: Packet, reader: Reader) {
        when (packet.type) {
            TYPE_CLIENT_INIT -> {
                val described = readClientInit(reader)
                client = described
                listener.onLog("ClientInit: ${described.describe()}")
                if (reader.remaining > 0) {
                    listener.onLog("ClientInit has ${reader.remaining} unread trailing bytes")
                }
                emit(TYPE_SERVER_INIT_ACK, Writer().u8(1))
                emit(TYPE_SERVER_INIT, serverInit(described.generation))
                if (described.generation == Generation.V1_2) {
                    emit(TYPE_SERVER_INIT_DONE, Writer().u8(1))
                    serverInitDone = true
                    enterRunningIfBothDone()
                } else {
                    enterRunning()
                }
            }
            TYPE_CLIENT_INIT_DONE -> {
                if (reader.u8() == 1) {
                    clientInitDone = true
                    enterRunningIfBothDone()
                } else {
                    listener.onLog("the car sent ClientInitDone = false")
                }
            }
            TYPE_CLIENT_VOICE_SUPPORT, TYPE_CLIENT_SETTINGS -> receiveRuntime(packet, reader)
            TYPE_PING_ACK, TYPE_PING -> handlePing(packet.type, reader)
            else -> listener.onLog("ignored type ${packet.type} during init: ${Wire.hex(packet.payload)}")
        }
    }

    private fun enterRunningIfBothDone() {
        if (serverInitDone && clientInitDone && state != State.RUNNING) enterRunning()
    }

    private fun enterRunning() {
        val described = client ?: throw MySpinProtocolException("runtime reached without a ClientInit")
        state = State.RUNNING
        listener.onRunning(described)
        sendTime(described.generation)
        // The launcher starts every session "inactive" and, as soon as its screen is up, sends
        // "transition ends" then "inactive ends" (hv.java: "reseting the statemachine by sending
        // transitions"), always before its first frame. A head unit that waits for them would
        // otherwise sit on a blank or "check your phone" screen while frames arrive.
        emit(TYPE_SERVER_STATE, Writer().u8(SERVER_STATE_TRANSITION).u8(0))
        emit(TYPE_SERVER_STATE, Writer().u8(SERVER_STATE_INACTIVE).u8(0))
        if (described.generation == Generation.V1_2) {
            // 1.2 adds a "launcher app state"; the launcher reports itself visible once its screen
            // is up, and MOTO-HUB found 1.2 motorcycle displays want it (value 1 = visible, first).
            emit(TYPE_SERVER_STATE, Writer().u8(SERVER_STATE_LAUNCHER).u8(1))
            if (settingsSeen) sendAppList()
        }
    }

    private fun readClientInit(reader: Reader): Client {
        val (major, minor, build) = version ?: throw MySpinProtocolException("ClientInit before the protocol version")
        val chosen = generation ?: throw MySpinProtocolException("ClientInit before the protocol version")
        val name = reader.string()
        val width = reader.u16()
        val height = reader.u16()
        val described = if (chosen == Generation.V1_0) {
            val compression = reader.u8()
            val pixelFormat = reader.u8()
            val endianness = reader.u8()
            val capabilities = reader.i32()
            Client(
                major, minor, build, chosen, name, width, height, 0, 0, capabilities, pixelFormat,
                endianness, compression, 0, 0, emptyList(), emptyList()
            )
        } else {
            val physicalWidth = reader.u16()
            val physicalHeight = reader.u16()
            val capabilities = reader.i32()
            val pixelFormat = reader.u8()
            val endianness = reader.u8()
            val overrideCompression = reader.i32()
            val level = reader.u8()
            val supported = reader.i32()
            val customData = reader.u16List()
            val nonTouch = if (chosen == Generation.V1_2 && reader.remaining > 0) reader.u8List() else emptyList()
            Client(
                major, minor, build, chosen, name, width, height, physicalWidth, physicalHeight,
                capabilities, pixelFormat, endianness, overrideCompression, supported, level,
                customData, nonTouch
            )
        }
        if (described.width !in 1..MAX_SIDE || described.height !in 1..MAX_SIDE) {
            throw MySpinProtocolException("screen ${described.width}x${described.height} is outside the supported size")
        }
        return described
    }

    /** ServerInit exactly as the launcher's three state machines build it (`iq`, `iw`, `jd`). */
    private fun serverInit(chosen: Generation): Writer {
        val writer = Writer()
            .string(SERVER_NAME)
            .u8(1)
            .u8(1).u8(SOFTKEY_HOME)   // softkey list: one entry, Home
        return when (chosen) {
            Generation.V1_0 -> writer.i32(1)
            Generation.V1_1 -> writer.i32(SERVER_CAPABILITY_AUDIO).string(OS_NAME)
            Generation.V1_2 -> writer.i32(SERVER_CAPABILITY_AUDIO or SERVER_CAPABILITY_APP_START).string(OS_NAME).string("")
        }
    }

    private fun receiveRuntime(packet: Packet, reader: Reader) {
        val chosen = generation ?: return
        when (packet.type) {
            TYPE_SET_PIXEL_FORMAT -> {
                val pixelFormat = reader.u8()
                val endianness = reader.u8()
                val level = if (chosen != Generation.V1_0 && reader.remaining > 0) reader.u8() else null
                // Every launcher stack answers this with a bare acknowledgement and keeps sending
                // the format from ClientInit ("handleSetPixelFormat is not handled yet"). The car
                // worked with that, so we do exactly the same and only note what it asked for.
                listener.onLog("car SetPixelFormat ${pixelFormatName(pixelFormat)}/$endianness level=$level (acknowledged, not applied, as the launcher does)")
                emit(TYPE_PIXEL_FORMAT_ACK, Writer().u8(1))
            }
            TYPE_SET_COMPRESSION -> {
                if (chosen == Generation.V1_0) {
                    // The 1.0 stack acknowledges and carries on with ClientInit's compression.
                    listener.onLog("car SetFrameCompression ${compressionName(reader.u8())} (acknowledged, not applied, as the launcher does)")
                    emit(TYPE_COMPRESSION_ACK, Writer().u8(1))
                } else {
                    val compression = reader.i32()
                    listener.onLog("car OverrideCompression ${compressionName(compression)}")
                    client = client?.copy(overrideCompression = compression)
                    emit(TYPE_COMPRESSION_ACK, Writer().u8(1))
                    client?.let(listener::onFrameFormatChanged)
                }
            }
            TYPE_FRAME_REQUEST -> {
                val x = reader.u16()
                val y = reader.u16()
                val width = reader.u16()
                val height = reader.u16()
                val incremental = reader.u8() != 0
                pendingFrameRequest = true
                listener.onFrameRequest(x, y, width, height, incremental)
            }
            TYPE_SET_FRAME_SIZE -> {
                val width = reader.u16()
                val height = reader.u16()
                val physicalWidth = reader.u16()
                val physicalHeight = reader.u16()
                // No launcher stack handles this packet: it is logged as unhandled and never
                // acknowledged. We stay with that until a car shows it needs otherwise.
                listener.onLog("car SetFrameSize ${width}x$height (${physicalWidth}x$physicalHeight mm): ignored, as the launcher does")
            }
            TYPE_SOFTKEY_EVENT -> {
                val eventType = reader.u8()
                val key = reader.u8()
                listener.onSoftkey(key, pressed = eventType == 1)
                tell(packet.type, isKey = true, "softkey ${softkeyName(key)} ${if (eventType == 1) "pressed" else "released"}")
            }
            TYPE_TOUCH_EVENT -> {
                val count = reader.u8()
                val touches = List(count) { Touch(reader.u8(), reader.u16(), reader.u16(), reader.u32()) }
                listener.onTouch(touches)
            }
            TYPE_CLIENT_STATE -> {
                val kind = reader.u8()
                val value = reader.u8()
                listener.onClientState(kind, value)
                // Moving and day/night change by themselves; anything else is worth a diagnostic's notice.
                if (kind != CLIENT_STATE_MOVING && kind != CLIENT_STATE_NIGHT) {
                    tell(packet.type, isKey = false, "car state ${clientStateName(kind)} = $value")
                }
            }
            TYPE_POSITION_INFORMATION -> listener.onPosition(reader.string())
            TYPE_SET_BLOCK_MODE -> {
                blockMode = reader.u8()
                listener.onLog("car block mode $blockMode (1 allow all, 2 deny all, 3 whitelist)")
            }
            TYPE_PHONE_CALL_STATUS -> {
                val status = reader.u8()
                note(packet.type, "car phone call status $status (${phoneCallStatusName(status)})")
            }
            TYPE_CLIENT_CUSTOM_MESSAGE_V10 -> {
                // 1.0 only: `type u16, String`.
                val type = reader.u16()
                val value = reader.string()
                listener.onCustomString(type, value)
                tell(packet.type, isKey = false, "car info string $type: \"$value\"")
            }
            TYPE_CLIENT_CUSTOM_DATA -> readCustomData(packet.type, reader)
            TYPE_VEHICLE_DATA -> {
                val key = reader.u32()
                listener.onLog("vehicle data 0x${key.toString(16)}: ${reader.string()}")
            }
            TYPE_CLIENT_AUDIO -> {
                val requestId = reader.u32()
                val status = reader.u8()
                val result = reader.u8()
                listener.onAudioStatus(requestId, status, result)
                tell(packet.type, isKey = false, "car audio answer: request $requestId status $status result $result")
            }
            TYPE_AUDIO_TYPE_RESPONSE -> note(packet.type, "car audio type response ${reader.u8()}")
            TYPE_CLIENT_VOICE_SUPPORT -> {
                val support = reader.u8()
                val constraint = reader.u8()
                note(
                    packet.type,
                    "car voice support $support (${voiceSupportName(support)}) constraint $constraint" +
                        if (constraint == 1) " (microphone and speakers together only)" else ""
                )
            }
            TYPE_CLIENT_VOICE_STATUS -> {
                val status = reader.u8()
                val constraint = reader.u8()
                note(
                    packet.type,
                    "car voice session status $status (${voiceStatusName(status)}) constraint $constraint (${voiceConstraintName(constraint)})"
                )
            }
            TYPE_CLIENT_SETTINGS -> {
                language = String(reader.bytes(minOf(3, reader.remaining)), Charsets.US_ASCII)
                settingsSeen = true
                listener.onLog("car language \"$language\"")
                // On protocol 1.2 the launcher answers the car's settings with its app list, and
                // some 1.2 displays draw nothing until they have one.
                if (chosen == Generation.V1_2 && state == State.RUNNING) sendAppList()
            }
            TYPE_CLIENT_REQUEST_APP_ICON -> listener.onLog("car asked for an app icon (not provided): ${Wire.hex(packet.payload, 48)}")
            TYPE_CLIENT_START_APP -> {
                val identifier = reader.string()
                note(packet.type, "car asked to start app \"$identifier\"")
                emit(TYPE_SERVER_APP_STARTED, Writer().string(identifier).u8(APP_START_SUCCESS))
            }
            TYPE_PING, TYPE_PING_ACK -> handlePing(packet.type, reader)
            else -> note(packet.type, "unhandled type ${packet.type}: ${Wire.hex(packet.payload)}")
        }
    }

    private fun readCustomData(packetType: Int, reader: Reader) {
        val kind = reader.u8()
        val type = reader.u16()
        val data = reader.bytes(reader.u16())
        when (kind) {
            CUSTOM_KIND_STRING -> {
                val value = Reader(data).string()
                listener.onCustomString(type, value)
                tell(packetType, isKey = false, "car info string $type: \"$value\"")
            }
            CUSTOM_KIND_KEY -> if (data.isNotEmpty()) {
                val key = data[0].toInt() and 0xFF
                listener.onCustomKey(type, key)
                tell(packetType, isKey = true, "hard key $key (${hardKeyName(key)}) ${keyActionName(type)}")
            } else {
                note(packetType, "car key event with no key code, action $type")
            }
            else -> note(packetType, "car custom data kind=$kind type=$type: ${Wire.hex(data)}")
        }
    }

    /** Logs [summary] and passes it on as a car message. */
    private fun note(type: Int, summary: String) {
        listener.onLog(summary)
        tell(type, isKey = false, summary)
    }

    /**
     * Only once the session is running: what arrives during the handshake is not an event. A
     * diagnostic that trips over a message must not take the link down with it, so whatever the
     * listener throws stops here.
     */
    private fun tell(type: Int, isKey: Boolean, summary: String) {
        if (state != State.RUNNING) return
        try {
            listener.onCarMessage(type, isKey, summary)
        } catch (failure: Exception) {
            listener.onLog("a listener failed on a car message (${failure.javaClass.simpleName}: ${failure.message})")
        }
    }

    private fun handlePing(type: Int, reader: Reader) {
        val counter = if (reader.remaining >= 4) reader.i32() else 0
        if (type == TYPE_PING) {
            // The launcher never expects the car to ping, but answering costs nothing.
            emit(TYPE_PING_ACK, Writer().i32(counter))
        } else if (counter != lastPingSent && counter != 0) {
            listener.onLog("ping ack $counter does not match the last ping $lastPingSent")
        }
    }

    // ---- phone -> car ----------------------------------------------------------------------

    /** The heartbeat. The launcher sends one every 500 ms once the session is running. */
    fun ping(counter: Int) {
        lastPingSent = counter
        emit(TYPE_PING, Writer().i32(counter))
    }

    /**
     * Answers the outstanding frame request with one whole-screen rectangle.
     * [data] must already be encoded for [compression] (a JPEG file, a zlib stream, or raw pixels)
     * at the size and pixel format in [client]. Returns false, sending nothing, when the car has
     * not asked for a frame.
     */
    fun sendFrame(data: ByteArray, compression: Int): Boolean {
        check(state == State.RUNNING) { "the mySPIN session is not running" }
        if (!pendingFrameRequest) return false
        val described = client ?: return false
        val writer = Writer()
            .u8(1)
            .u16(0).u16(0).u16(described.width).u16(described.height)
        if (described.generation != Generation.V1_0) {
            writer.u8(described.pixelFormat)
                .u8(described.endianness)
                // The launcher writes 4 here for raw pixels, never 0.
                .i32(if (compression == COMPRESSION_NONE) COMPRESSION_UNCOMPRESSED else compression)
                .u8(described.compressionLevel)
                .u8(100).u8(100)
        }
        writer.i32(data.size).bytes(data)
        pendingFrameRequest = false
        emit(TYPE_FRAME, writer)
        return true
    }

    /** kind 1 = phone call, 2 = app transition, 3 = inactive; [active] true when it begins. */
    fun sendServerState(kind: Int, active: Boolean) {
        if (state == State.RUNNING) emit(TYPE_SERVER_STATE, Writer().u8(kind).u8(if (active) 1 else 0))
    }

    /** Tells the car the phone's Bluetooth name, in reply to the car's MAC address. */
    fun sendBluetoothName(name: String) {
        if (state != State.RUNNING) return
        if (generation == Generation.V1_0) {
            emit(TYPE_SERVER_CUSTOM_MESSAGE_V10, Writer().u16(1).string(name))
        } else {
            val text = Writer().string(name).build()
            emit(TYPE_SERVER_CUSTOM_DATA, Writer().u8(CUSTOM_KIND_STRING).u16(1).u16(text.size).bytes(text))
        }
    }

    /**
     * Asks the car for its speakers ([control] = [AUDIO_REQUEST]) or gives them back
     * ([AUDIO_RELEASE]). Protocol 1.1 and later only; false when it could not be sent.
     */
    fun sendAudioFocus(requestId: Int, control: Int, audioType: Int): Boolean {
        if (state != State.RUNNING || generation == Generation.V1_0) return false
        emit(TYPE_SERVER_AUDIO, Writer().i32(requestId).u8(control).u8(audioType).u16(0))
        return true
    }

    /**
     * Protocol 1.2 only: the list of apps the car may offer, with this one app in it. Layout per
     * the launcher's generated `AppInformation`: identifier, name, icon id, manufacturer,
     * description, categories, install status, can-launch flag, language.
     */
    private fun sendAppList() {
        val code = language.padEnd(3).take(3).toByteArray(Charsets.US_ASCII)
        emit(
            TYPE_SERVER_APP_INFO,
            Writer()
                .i32(1)
                .string(APP_IDENTIFIER).string(APP_NAME).i32(0).string(APP_MANUFACTURER).string("")
                .u8(1).i32(1)                     // one category
                .u8(APP_INSTALLED_EXECUTABLE).u8(1)
                .bytes(code)
        )
    }

    private fun sendTime(chosen: Generation) {
        val now = nowMillis()
        val utcSeconds = now / 1000
        val localSeconds = (now + utcOffsetMillis(now)) / 1000
        if (chosen == Generation.V1_0) {
            emit(TYPE_SERVER_CUSTOM_DATA_INT_V10, Writer().u16(CUSTOM_INT_DATE_TIME).i64(utcSeconds))
        } else {
            emit(TYPE_SERVER_CUSTOM_DATA, Writer().u8(CUSTOM_KIND_INT).u16(CUSTOM_INT_DATE_TIME).u16(8).i64(utcSeconds))
            emit(TYPE_SERVER_CUSTOM_DATA, Writer().u8(CUSTOM_KIND_INT).u16(CUSTOM_INT_DATE_TIME_LOCAL).u16(8).i64(localSeconds))
        }
    }

    private fun emit(type: Int, writer: Writer) {
        send(Packet(type, writer.build()))
    }

    companion object {
        const val TYPE_CLIENT_PROTOCOL_VERSION = 1
        const val TYPE_SERVER_PROTOCOL_ACK = 2
        const val TYPE_CLIENT_INIT = 11
        const val TYPE_SERVER_INIT_ACK = 12
        const val TYPE_SERVER_INIT = 14
        const val TYPE_CLIENT_INIT_DONE = 17
        const val TYPE_SERVER_INIT_DONE = 18
        const val TYPE_SET_PIXEL_FORMAT = 21
        const val TYPE_PIXEL_FORMAT_ACK = 22
        const val TYPE_SET_COMPRESSION = 23
        const val TYPE_COMPRESSION_ACK = 24
        const val TYPE_FRAME_REQUEST = 25
        const val TYPE_FRAME = 26
        const val TYPE_SET_FRAME_SIZE = 27
        const val TYPE_FRAME_SIZE_ACK = 28
        const val TYPE_SOFTKEY_EVENT = 31
        const val TYPE_TOUCH_EVENT = 33
        const val TYPE_CLIENT_STATE = 35
        const val TYPE_SERVER_STATE = 36
        const val TYPE_POSITION_INFORMATION = 37
        const val TYPE_SET_BLOCK_MODE = 39
        const val TYPE_CLIENT_CUSTOM_MESSAGE_V10 = 43
        const val TYPE_SERVER_CUSTOM_MESSAGE_V10 = 44
        const val TYPE_SERVER_CUSTOM_DATA_INT_V10 = 46
        const val TYPE_PHONE_CALL_STATUS = 51
        const val TYPE_VEHICLE_DATA = 55
        const val TYPE_SERVER_AUDIO = 58
        const val TYPE_CLIENT_AUDIO = 59
        const val TYPE_CLIENT_VOICE_SUPPORT = 61
        const val TYPE_CLIENT_VOICE_STATUS = 63
        const val TYPE_AUDIO_TYPE_RESPONSE = 65
        const val TYPE_CLIENT_CUSTOM_DATA = 67
        const val TYPE_SERVER_CUSTOM_DATA = 68
        const val TYPE_CLIENT_SETTINGS = 69
        const val TYPE_SERVER_APP_INFO = 70
        const val TYPE_CLIENT_REQUEST_APP_ICON = 71
        const val TYPE_CLIENT_START_APP = 73
        const val TYPE_SERVER_APP_STARTED = 74
        const val TYPE_PING = 128
        const val TYPE_PING_ACK = 129

        const val COMPRESSION_NONE = 0
        const val COMPRESSION_JPEG = 1
        const val COMPRESSION_ZLIB = 2
        const val COMPRESSION_UNCOMPRESSED = 4

        const val PIXEL_RGB888 = 1
        const val PIXEL_RGB565 = 2
        const val PIXEL_ARGB8888 = 3
        const val PIXEL_RGBA8888 = 4

        const val SOFTKEY_HOME = 1
        const val SOFTKEY_BACK = 2
        const val SOFTKEY_MENU = 3
        const val SOFTKEY_SEARCH = 4

        const val TOUCH_DOWN = 1
        const val TOUCH_UP = 2
        const val TOUCH_MOVED = 3
        const val TOUCH_CANCELLED = 4

        const val CAPABILITY_INITIATE_PHONE_CALL = 1
        const val CAPABILITY_NAVIGATE_TO = 2
        const val CAPABILITY_SCREEN_COPY = 4
        const val CAPABILITY_POSITION_INFORMATION = 8
        const val CAPABILITY_AUDIO_HANDLING = 16

        const val CLIENT_STATE_MOVING = 1
        const val CLIENT_STATE_NIGHT = 2

        const val SERVER_CAPABILITY_AUDIO = 2
        const val SERVER_CAPABILITY_APP_START = 4

        const val CUSTOM_KIND_STRING = 1
        const val CUSTOM_KIND_INT = 2
        const val CUSTOM_KIND_KEY = 5
        const val CUSTOM_INT_DATE_TIME = 4
        const val CUSTOM_INT_DATE_TIME_LOCAL = 6
        const val CUSTOM_STRING_BT_MAC = 1

        const val AUDIO_REQUEST = 1
        const val AUDIO_RELEASE = 5
        const val AUDIO_TYPE_MAIN = 3
        const val AUDIO_REQUEST_ID_MAIN = 1
        const val AUDIO_STATUS_REJECT = 2
        const val AUDIO_STATUS_OPEN = 3
        const val AUDIO_STATUS_SUSPEND = 4
        const val AUDIO_STATUS_CLOSE = 6

        const val SERVER_STATE_PHONE_CALL = 1
        const val SERVER_STATE_TRANSITION = 2
        const val SERVER_STATE_INACTIVE = 3
        const val SERVER_STATE_LAUNCHER = 4

        const val APP_IDENTIFIER = "app.spinbridge"
        const val APP_NAME = "Disco Auto"
        const val APP_MANUFACTURER = "Disco Auto"
        const val APP_INSTALLED_EXECUTABLE = 3
        const val APP_START_SUCCESS = 2

        const val SERVER_NAME = "mySPIN Android Server"
        const val OS_NAME = "Android"

        private const val MAX_SIDE = 4096

        fun softkeyName(key: Int): String = when (key) {
            SOFTKEY_HOME -> "HOME"
            SOFTKEY_BACK -> "BACK"
            SOFTKEY_MENU -> "MENU"
            SOFTKEY_SEARCH -> "SEARCH"
            else -> "key $key"
        }

        /** Bosch's names for the key codes a head unit may send (protocol 1.2 and later). */
        fun hardKeyName(key: Int): String = when (key) {
            1 -> "PushToTalk"
            2 -> "ScrollUp"
            3 -> "ScrollDown"
            4 -> "PageUp"
            5 -> "PageDown"
            6 -> "ValueUp"
            7 -> "ValueDown"
            8 -> "PhoneLiftReceiver"
            9 -> "PhoneRingOff"
            10 -> "VolumeUp"
            11 -> "VolumeDown"
            12 -> "Ok"
            13 -> "Cancel"
            14 -> "Back"
            20 -> "KnobTickCW"
            21 -> "KnobTickCCW"
            30 -> "DPadUp"
            31 -> "DPadDown"
            32 -> "DPadLeft"
            33 -> "DPadRight"
            else -> "unnamed"
        }

        fun keyActionName(action: Int): String = when (action) {
            1 -> "press"
            2 -> "release"
            3 -> "long press"
            4 -> "click"
            5 -> "double click"
            6 -> "abort"
            else -> "action $action"
        }

        fun clientStateName(kind: Int): String = when (kind) {
            CLIENT_STATE_MOVING -> "moving"
            CLIENT_STATE_NIGHT -> "night"
            3 -> "frame requests suspended"
            4 -> "is visible"
            5 -> "needs A2DP"
            else -> "kind $kind"
        }

        fun voiceSupportName(value: Int): String = when (value) {
            0 -> "not supported"
            1 -> "virtual phone call only"
            2 -> "full hands-free audio"
            3 -> "protocol transport"
            4 -> "second endpoint"
            else -> "unnamed"
        }

        fun voiceStatusName(value: Int): String = when (value) {
            0 -> "unavailable"
            1 -> "idle"
            2 -> "start requested"
            3 -> "active"
            4 -> "end requested"
            else -> "unnamed"
        }

        fun voiceConstraintName(value: Int): String = when (value) {
            0 -> "none"
            1 -> "no hands-free connection"
            2 -> "voice already active"
            3 -> "hands-free audio timed out"
            else -> "unnamed"
        }

        fun phoneCallStatusName(value: Int): String = when (value) {
            1 -> "received"
            2 -> "started"
            3 -> "active"
            4 -> "ended"
            5 -> "waiting"
            6 -> "rejected"
            else -> "unnamed"
        }

        /** The capability bits of ClientInit, by Bosch's names. */
        fun capabilityNames(capabilities: Int): String {
            val names = listOf(
                1 to "InitiatePhoneCall", 2 to "NavigateTo", 4 to "ScreenCopy", 8 to "PositionInformation",
                16 to "AudioHandling", 32 to "PacketExtensions", 64 to "PartialFrameUpdate", 128 to "NoHomeButton",
                256 to "RequiresFocusControl", 512 to "IsTwoWheeler", 1024 to "IsOtherVehicle",
            ).filter { capabilities and it.first != 0 }.map { it.second }
            return if (names.isEmpty()) "none" else names.joinToString("+")
        }

        fun compressionName(value: Int): String = when (value) {
            COMPRESSION_NONE -> "none"
            COMPRESSION_JPEG -> "JPEG"
            COMPRESSION_ZLIB -> "zlib"
            COMPRESSION_UNCOMPRESSED -> "uncompressed"
            else -> "compression($value)"
        }

        fun pixelFormatName(value: Int): String = when (value) {
            PIXEL_RGB888 -> "RGB888"
            PIXEL_RGB565 -> "RGB565"
            PIXEL_ARGB8888 -> "ARGB8888"
            PIXEL_RGBA8888 -> "RGBA8888"
            else -> "pixel($value)"
        }

        fun bytesPerPixel(pixelFormat: Int): Int = when (pixelFormat) {
            PIXEL_RGB565 -> 2
            PIXEL_RGB888 -> 3
            else -> 4
        }
    }
}
