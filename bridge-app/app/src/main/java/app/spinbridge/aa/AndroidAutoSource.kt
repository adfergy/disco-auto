// SPDX-License-Identifier: MIT
package app.spinbridge.aa

import android.content.Context
import android.os.SystemClock
import app.spinbridge.BridgeLog
import app.spinbridge.FrameSink
import app.spinbridge.FrameSource
import app.spinbridge.myspin.MySpinServer
import app.spinbridge.picture.CarCard
import app.spinbridge.picture.PictureStatus
import io.motohub.android.aa.AaInput
import io.motohub.android.aa.AaReceiver
import io.motohub.android.androidauto.AaInputBridge
import io.motohub.android.androidauto.AndroidAutoCapabilityProfile
import io.motohub.android.androidauto.AndroidAutoCapabilityProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android Auto as the car's picture.
 *
 * The receiver lifted from MOTO-HUB (`io.motohub.android.aa`) plays the head unit towards the
 * Android Auto app on this same phone and decodes its H.264 into the Surface it is given. Here
 * that Surface is a [DecodedFrameBridge], which turns each decoded frame into what the car takes;
 * the car's touches go back the other way.
 *
 * Android Auto 17.4 and later only connect when the owner has started its "head unit server"
 * (Android Auto's settings, three-dot menu). No app can start that for them. This source is only
 * used while that server is up ([AndroidAuto.availability]); until Android Auto's picture
 * arrives the car has the logo card ([CarCard]) saying what is happening, and [onStatus] carries
 * what the phone should say.
 */
class AndroidAutoSource(
    context: Context,
    private val onStatus: (PictureStatus) -> Unit,
) : FrameSource {
    override val name = "Android Auto"

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val touchEvents = AtomicInteger()

    /** True while the logo card, not Android Auto's own picture, is on the car. */
    @Volatile private var cardUp = false
    @Volatile private var cardSetAt = 0L

    /** When a picture last arrived that the guard for a session's tail turned away. */
    @Volatile private var turnedAwayAt = 0L

    /** Set with a card put up because a session ended: its last frames may still be on their way. */
    @Volatile private var cardFollowsPicture = false

    /** Non-null while something on this phone stops the picture being shown at all. */
    @Volatile private var trouble: String? = null
    @Volatile private var videoSeen = false

    /**
     * Since when, on the card now showing, the server has been up without a session and a
     * session up without a picture. Background loop only.
     */
    private var timedCard = 0L
    private var listeningSince = 0L
    private var liveSince = 0L
    private val statusLock = Any()
    private var reported: PictureStatus? = null

    /** start() and stop() arrive on different threads; neither may run in the middle of the other. */
    private val lifecycle = Any()
    private var frames: DecodedFrameBridge? = null
    private var receiver: AaReceiver? = null
    @Volatile private var sink: FrameSink? = null
    @Volatile private var client: MySpinServer.Client? = null
    @Volatile private var profile: AndroidAutoCapabilityProfile? = null
    @Volatile private var stopped = false

    override fun start(client: MySpinServer.Client, sink: FrameSink) = synchronized(lifecycle) {
        if (stopped || frames != null) return
        this.client = client
        this.sink = sink
        val chosen = AndroidAutoCapabilityProfiles.forCarScreen(client.width, client.height)
        profile = chosen
        BridgeLog.log(
            "[AA] car screen ${client.width}x${client.height} -> Android Auto source " +
                "${chosen.video.width}x${chosen.video.height} at ${chosen.densityDpi} dpi, " +
                "margins ${chosen.marginWidth}x${chosen.marginHeight}"
        )
        putCardUp(afterPicture = false)
        report(AndroidAuto.CONNECTING)

        val bridge = DecodedFrameBridge(
            videoWidth = chosen.video.width,
            videoHeight = chosen.video.height,
            marginWidth = chosen.marginWidth,
            marginHeight = chosen.marginHeight,
            label = "AA",
            sink = sink,
            client = client,
            onPicture = ::onAndroidAutoPicture,
            onUnreadable = { format ->
                trouble = "this phone's video decoder hands over pictures in a private layout (0x${format.toString(16)})"
                putCardUp(afterPicture = false)
            },
        )
        frames = bridge
        scope.launch { backgroundLoop(bridge, sink) }

        val opened = AaReceiver(
            context = appContext,
            encoderSurface = bridge.surface,
            log = BridgeLog::log,
            // The receiver reports this once in its life, not once per Android Auto session, so
            // the card is taken down by pictures actually arriving (onAndroidAutoPicture) instead.
            onVideoReady = { BridgeLog.log("[AA] first video frame decoded") },
            onSessionEnded = { clean, userExit ->
                BridgeLog.log("[AA] Android Auto session ended (clean=$clean, userExit=$userExit)")
                if (!stopped) {
                    // This also arrives for a connection that never became a session. Only a
                    // picture that was really showing is replaced; a card already up, and how
                    // long it has been up, stay as they are.
                    if (!cardUp) putCardUp(afterPicture = true)
                    bridge.reset()
                }
            },
            mapTouchToSource = { x, y -> x to y },
            capabilityProfile = chosen,
        )
        receiver = opened
        if (!opened.start()) {
            trouble = "the Android Auto receiver could not start"
            putCardUp(afterPicture = false)
        }
        // Nothing here asks Android Auto to connect. The receiver came with code that did (its
        // "self-mode"), by starting Android Auto's wireless set-up services; Android Auto 17.4 and
        // later cannot act on that, and on 18.1 one such request took Android Auto's car process
        // down (15 Nov 2026). The receiver dials the head unit server, and that is all.
    }

    /**
     * The car wants its pictures differently, and has thrown away the one it had. Android Auto
     * only sends video when its screen changes, so the last one goes again in the new form.
     */
    override fun onClientChanged(client: MySpinServer.Client) {
        this.client = client
        frames?.let { bridge ->
            bridge.client = client
            if (!cardUp) scope.launch { runCatching { bridge.refresh() } }
        }
    }

    override fun onTouch(touches: List<MySpinServer.Touch>) {
        val car = client ?: return
        // The logo card is up: there is nothing on it to touch.
        if (cardUp) return
        val active = receiver ?: return
        val surface = profile?.touchSurface ?: return
        touchEvents.addAndGet(touches.size)
        for (touch in touches) {
            val action = when (touch.action) {
                MySpinServer.TOUCH_DOWN -> AaInput.ACTION_DOWN
                MySpinServer.TOUCH_MOVED -> AaInput.ACTION_MOVE
                MySpinServer.TOUCH_UP, MySpinServer.TOUCH_CANCELLED -> AaInput.ACTION_UP
                else -> continue
            }
            // The car reports screen pixels; Android Auto wants its own UI surface.
            val x = (touch.x.toLong() * surface.width / car.width).toInt().coerceIn(0, surface.width - 1)
            val y = (touch.y.toLong() * surface.height / car.height).toInt().coerceIn(0, surface.height - 1)
            active.sendSourceTouch(action, (touch.fingerId and 0x7FFF).toInt(), x, y)
        }
    }

    override fun onSoftkey(key: Int, pressed: Boolean) {
        if (pressed) return   // act on release, as the launcher does
        when (key) {
            MySpinServer.SOFTKEY_HOME -> AaInputBridge.sendKey(AaInput.KEY_HOME)
            MySpinServer.SOFTKEY_BACK -> AaInputBridge.sendKey(AaInput.KEY_BACK)
            MySpinServer.SOFTKEY_SEARCH -> AaInputBridge.sendKey(AaInput.KEY_ASSISTANT)
        }
    }

    override fun onCustomKey(action: Int, key: Int) {
        // Hard keys arrive as press / release / click; act once per click or release. (Head units
        // that speak mySPIN 1.2; the Discovery Sport's 1.0 sends none.)
        if (action != KEY_ACTION_RELEASE && action != KEY_ACTION_CLICK) return
        when (key) {
            KEY_PUSH_TO_TALK -> AaInputBridge.sendKey(AaInput.KEY_ASSISTANT)
            KEY_OK -> AaInputBridge.sendKey(AaInput.KEY_ENTER)
            KEY_BACK, KEY_CANCEL -> AaInputBridge.sendKey(AaInput.KEY_BACK)
            KEY_KNOB_CLOCKWISE, KEY_SCROLL_DOWN -> AaInputBridge.sendScroll(1)
            KEY_KNOB_COUNTERCLOCKWISE, KEY_SCROLL_UP -> AaInputBridge.sendScroll(-1)
            KEY_DPAD_UP -> AaInputBridge.sendKey(AaInput.KEY_UP)
            KEY_DPAD_DOWN -> AaInputBridge.sendKey(AaInput.KEY_DOWN)
            KEY_DPAD_LEFT -> AaInputBridge.sendKey(AaInput.KEY_LEFT)
            KEY_DPAD_RIGHT -> AaInputBridge.sendKey(AaInput.KEY_RIGHT)
        }
    }

    override fun onClientState(kind: Int, value: Int) {
        if (kind == CLIENT_STATE_DAY_NIGHT) receiver?.setNightMode(value != 0)
    }

    /** A picture from Android Auto has just gone to the car: the card has had its day. */
    private fun onAndroidAutoPicture() {
        if (!cardUp) return
        // Frames in the first moments after a session ends are that session's tail, not a new one.
        // (If one turns out to have been a new session's only frame, the loop takes the card down.)
        val now = SystemClock.elapsedRealtime()
        if (cardFollowsPicture && now - cardSetAt < STALE_PICTURE_MS) {
            turnedAwayAt = now
            return
        }
        trouble = null
        cardUp = false
        report(AndroidAuto.ON_SCREEN)
        if (!videoSeen) {
            videoSeen = true
            BridgeLog.log("[AA] Android Auto's picture is on the car's screen")
        }
    }

    /**
     * Four times a second: sends the card when it is new (and once a second regardless, so a car
     * that only now starts asking for pictures gets it), works out what the phone should be
     * saying, and every ten seconds writes how the picture is doing to the log. The card is sent
     * from nowhere else: the things that put it up run on the session's and the receiver's own
     * threads, and sending from there (which hands a frame back to the session) could deadlock
     * against this loop.
     */
    private suspend fun backgroundLoop(bridge: DecodedFrameBridge, sink: FrameSink) {
        val card = CarCard(appContext)
        var lastTaken = sink.framesTaken()
        var lastTouches = 0
        var ticks = 0
        var hadCard = false
        var sentFor = 0L
        while (scope.isActive && !stopped) {
            try {
                // A picture turned away as a session's tail, with a session live once the tail's
                // time has passed, was that session's first: the card comes down after all.
                if (cardUp && cardFollowsPicture && turnedAwayAt >= cardSetAt &&
                    SystemClock.elapsedRealtime() - cardSetAt >= STALE_PICTURE_MS && receiver?.hasLiveSession == true
                ) {
                    trouble = null
                    cardUp = false
                    report(AndroidAuto.ON_SCREEN)
                }
                val up = cardUp
                if (up) {
                    if (sentFor != cardSetAt || ticks % TICKS_PER_SECOND == 0) {
                        sentFor = cardSetAt
                        val status = waitingStatus()
                        report(status)
                        val car = client
                        if (car != null) {
                            val (data, compression) = card.frame(car, AndroidAuto.carMessage(status))
                            // Android Auto's picture may have arrived meanwhile; it wins.
                            if (cardUp) sink.offer(data, compression)
                        }
                    }
                } else if (hadCard) {
                    // After a card, send the picture once more: a card sent just as the picture
                    // arrived may have reached the car after it.
                    bridge.refresh()
                }
                hadCard = up
                ticks++
                if (ticks % (PERFORMANCE_EVERY_S * TICKS_PER_SECOND) == 0) {
                    val performance = bridge.takePerformance()
                    val taken = sink.framesTaken()
                    val touches = touchEvents.get()
                    if (performance.decoded > 0 || videoSeen) {
                        BridgeLog.log(
                            "PERF last $PERFORMANCE_EVERY_S s: Android Auto delivered ${performance.decoded} frames, " +
                                "${performance.converted} converted (%.0f ms and %d KiB each on average), ".format(
                                    performance.averageMillis, performance.averageBytes / 1024
                                ) +
                                (if (taken >= 0 && lastTaken >= 0) "the car took ${taken - lastTaken} = %.1f a second".format((taken - lastTaken) / PERFORMANCE_EVERY_S.toDouble()) else "car frame count unknown") +
                                ", ${touches - lastTouches} touch events"
                        )
                    }
                    lastTaken = taken
                    lastTouches = touches
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                BridgeLog.log("[AA] background loop hiccup: ${failure.javaClass.simpleName}: ${failure.message}")
            }
            delay(1000L / TICKS_PER_SECOND)
        }
    }

    override fun stop() = synchronized(lifecycle) {
        if (stopped) return
        stopped = true
        scope.cancel()
        runCatching { receiver?.stop() }
        receiver = null
        frames?.close()
        frames = null
        sink = null
    }

    private fun putCardUp(afterPicture: Boolean) {
        cardFollowsPicture = afterPicture
        cardSetAt = SystemClock.elapsedRealtime()
        cardUp = true
    }

    /**
     * What the phone should say while the card is up, from the two things that can be known
     * without disturbing Android Auto: whether its server is listening, and whether it has
     * connected. Only the background loop calls this.
     */
    private fun waitingStatus(): PictureStatus {
        trouble?.let { return AndroidAuto.trouble(it) }
        val now = SystemClock.elapsedRealtime()
        if (timedCard != cardSetAt) {
            // A new card: how long things took under the last one says nothing about this one.
            timedCard = cardSetAt
            listeningSince = 0L
            liveSince = 0L
        }
        if (receiver?.hasLiveSession == true) {
            listeningSince = 0L
            if (liveSince == 0L) liveSince = now
            return if (now - liveSince < NO_PICTURE_AFTER_MS) AndroidAuto.CONNECTING else AndroidAuto.NO_PICTURE
        }
        liveSince = 0L
        if (HeadUnitServer.look() == HeadUnitServer.State.LISTENING) {
            if (listeningSince == 0L) listeningSince = now
            // Up and nothing coming of it: the receiver dials within a second or two, and a
            // server in working order answers at once.
            return if (now - listeningSince < NOT_ANSWERING_AFTER_MS) AndroidAuto.CONNECTING else AndroidAuto.NOT_ANSWERING
        }
        // The server has gone. If it stays gone, the waiting logo takes over (CarPicture).
        listeningSince = 0L
        return AndroidAuto.SERVER_GONE
    }

    private fun report(next: PictureStatus) = synchronized(statusLock) {
        if (next == reported) return
        // A picture arriving takes the card down and outranks whatever the card was about to say.
        if (!next.showing && !cardUp) return
        reported = next
        BridgeLog.log("[AA] now: ${next.headline}")
        onStatus(next)
    }

    private companion object {
        private const val CLIENT_STATE_DAY_NIGHT = 2
        private const val PERFORMANCE_EVERY_S = 10
        private const val TICKS_PER_SECOND = 4
        private const val STALE_PICTURE_MS = 400L
        private const val NOT_ANSWERING_AFTER_MS = 12_000L
        private const val NO_PICTURE_AFTER_MS = 10_000L


        // mySPIN hard-key codes and actions (ClientCustomData kind 5), from Bosch's IVI API header.
        private const val KEY_ACTION_RELEASE = 2
        private const val KEY_ACTION_CLICK = 4
        private const val KEY_PUSH_TO_TALK = 1
        private const val KEY_SCROLL_UP = 2
        private const val KEY_SCROLL_DOWN = 3
        private const val KEY_OK = 12
        private const val KEY_CANCEL = 13
        private const val KEY_BACK = 14
        private const val KEY_KNOB_CLOCKWISE = 20
        private const val KEY_KNOB_COUNTERCLOCKWISE = 21
        private const val KEY_DPAD_UP = 30
        private const val KEY_DPAD_DOWN = 31
        private const val KEY_DPAD_LEFT = 32
        private const val KEY_DPAD_RIGHT = 33
    }
}
