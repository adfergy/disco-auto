// SPDX-License-Identifier: MIT
package app.spinbridge.picture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import app.spinbridge.BridgeLog
import app.spinbridge.FrameEncoder
import app.spinbridge.FrameSink
import app.spinbridge.FrameSource
import app.spinbridge.myspin.MySpinServer
import app.spinbridge.touch.CarTouch

/**
 * The phone's own screen as the car's picture, through Android's screen sharing.
 *
 * Android asks the owner each time, on the phone; until they say yes, and again whenever the
 * share ends (they stop it, or the phone locks), the car has the menu ([CarMenu]), whose MIRROR
 * button has the question put again. The share is taken into a picture twice the car's size, in
 * the car's shape (Android fits the phone's screen into it, turned whichever way the phone is),
 * and each change is scaled down and sent on, at most ten a second. Once the phone's screen is
 * showing, touches on the car's screen are passed to the phone if the owner has switched on the
 * touch service ([CarTouch]); without it this shows and does not control.
 *
 * Everything after [start] happens on this source's own thread, so nothing here hands a frame to
 * the session from inside one of the session's own calls.
 */
class ScreenCastSource(
    context: Context,
    private val onStatus: (PictureStatus) -> Unit,
    private val host: PictureHost? = null,
) : FrameSource, ScreenCast.Taker {
    override val name = "the phone's screen"

    private val appContext = context.applicationContext
    private val lock = Any()
    private var started = false
    private var stopped = false
    private val thread = HandlerThread("screen-cast")
    private lateinit var handler: Handler

    @Volatile private var client: MySpinServer.Client? = null
    @Volatile private var sink: FrameSink? = null

    /** True while the phone's screen, not the menu, is on the car. Written on this source's thread. */
    @Volatile private var showing = false

    // Own thread only from here.
    private val menu = CarMenu(appContext)
    private var menuShown: CarMenu.Content? = null
    private var passingHint: String? = null
    private var passingHintUntil = 0L
    private val encoder = FrameEncoder()
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var pending: Image? = null
    private var conversionQueued = false
    private var lastConvertedAt = 0L
    private var captured: Bitmap? = null
    private var capturedWidth = 0
    private var scaled: Bitmap? = null
    private var frames = 0
    private var screenLock: PowerManager.WakeLock? = null

    private val shareEnded = object : MediaProjection.Callback() {
        override fun onStop() = ended(askAgainStraightAway = false)
    }

    override fun start(client: MySpinServer.Client, sink: FrameSink) {
        synchronized(lock) {
            if (stopped || started) return
            started = true
            this.client = client
            this.sink = sink
            thread.start()
            handler = Handler(thread.looper)
        }
        onStatus(WAITING)
        handler.post(cardTick)
        ScreenCast.want(this, askStraightAway = true)
    }

    /**
     * The car wants its pictures differently. It has thrown away the one it had, and a screen
     * that is not changing would never send another, so the last one goes again in the new form.
     */
    override fun onClientChanged(client: MySpinServer.Client) {
        this.client = client
        if (synchronized(lock) { started && !stopped }) {
            handler.post { if (showing) runCatching { sendCaptured() } else sendMenu() }
        }
    }

    /**
     * A touch on the car's screen, on the session's thread. With the menu up it may be a choice.
     * With the phone's screen up it is a touch on the phone, if the owner has switched that on
     * ([CarTouch]); otherwise nothing.
     */
    override fun onTouch(touches: List<MySpinServer.Touch>) {
        if (showing) {
            val car = client ?: return
            CarTouch.onCarTouches(touches, car.width, car.height)
            return
        }
        val up = touches.lastOrNull { it.action == MySpinServer.TOUCH_UP } ?: return
        if (synchronized(lock) { started && !stopped }) handler.post { touchedMenu(up.x, up.y) }
    }

    override fun stop() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            if (!started) return
        }
        ScreenCast.withdraw(this)
        handler.post {
            val share = projection
            release()
            // Ending the share is what stops the service that holds it.
            runCatching { share?.stop() }
            thread.quitSafely()
        }
    }

    /** Main thread: the owner said yes. */
    override fun onProjection(projection: MediaProjection) {
        val live = synchronized(lock) { started && !stopped }
        if (!live || !handler.post { begin(projection) }) runCatching { projection.stop() }
    }

    private fun begin(share: MediaProjection) {
        val car = client
        if (car == null || synchronized(lock) { stopped }) {
            runCatching { share.stop() }
            return
        }
        try {
            val width = car.width * CAPTURE_SCALE
            val height = car.height * CAPTURE_SCALE
            val images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
            images.setOnImageAvailableListener({ onImage(it) }, handler)
            // Android wants to be able to tell us the share has ended before it lets it begin.
            share.registerCallback(shareEnded, handler)
            projection = share
            reader = images
            display = share.createVirtualDisplay(
                "Disco Auto", width, height, appContext.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, images.surface, null, handler
            )
            keepScreenOn(true)
            BridgeLog.log("casting: sharing the phone's screen as ${width}x$height for a ${car.width}x${car.height} car screen")
        } catch (failure: Exception) {
            BridgeLog.log("casting: could not start showing the screen (${failure.javaClass.simpleName}: ${failure.message})")
            release()
            runCatching { share.stop() }
            ended(askAgainStraightAway = false)
        }
    }

    /** The share is over, by the owner's hand or Android's: back to the menu, and to waiting. */
    private fun ended(askAgainStraightAway: Boolean) {
        val wasShowing = showing
        release()
        if (synchronized(lock) { stopped }) return
        if (wasShowing) BridgeLog.log("casting: the screen share ended after $frames pictures")
        onStatus(WAITING)
        handler.removeCallbacks(cardTick)
        handler.post(cardTick)
        ScreenCast.want(this, askAgainStraightAway)
        // stop() may have run between the check above and here, and found nothing to withdraw.
        if (synchronized(lock) { stopped }) ScreenCast.withdraw(this)
    }

    private fun release() {
        // A finger still down on the car's screen has nothing to be down on any more.
        if (showing) CarTouch.release()
        keepScreenOn(false)
        handler.removeCallbacks(convert)
        conversionQueued = false
        runCatching { pending?.close() }
        pending = null
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        runCatching { projection?.unregisterCallback(shareEnded) }
        projection = null
        captured?.recycle()
        captured = null
        capturedWidth = 0
        scaled?.recycle()
        scaled = null
        showing = false
    }

    /** Once a second until the screen is being shown: the menu, for a car that asks late. */
    private val cardTick = object : Runnable {
        override fun run() {
            if (showing || synchronized(lock) { stopped }) return
            sendMenu()
            handler.postDelayed(this, CARD_EVERY_MS)
        }
    }

    /** What the menu says now: MIRROR, the other picture, and a hint. */
    private fun menuContent(): CarMenu.Content {
        val other = host?.alternativeOption()
        val hint = passingHint?.takeIf { SystemClock.elapsedRealtime() < passingHintUntil }
            ?: when {
                other == null -> HINT
                other.enabled -> HINT_CHOOSE
                else -> host?.alternativeHint() ?: HINT
            }
        return CarMenu.Content(listOfNotNull(CarMenu.Option(CarMenu.Choice.MIRROR, "MIRROR", enabled = true), other), hint)
    }

    private fun sendMenu() {
        if (showing) return
        val car = client ?: return
        val target = sink ?: return
        try {
            val content = menuContent()
            val (data, compression) = menu.frame(car, content)
            menuShown = content
            if (!showing) target.offer(data, compression)
        } catch (failure: Exception) {
            BridgeLog.log("casting: the menu could not be drawn (${failure.javaClass.simpleName}: ${failure.message})")
        }
    }

    /** Own thread: the owner lifted a finger at ([x], [y]) on the car's screen while the menu was up. */
    private fun touchedMenu(x: Int, y: Int) {
        if (showing) return
        val car = client ?: return
        val content = menuShown ?: return
        when (CarMenu.choiceAt(car.width, car.height, content, x, y)) {
            CarMenu.Choice.MIRROR -> {
                // Android's question can only be put by one of the app's screens, on the phone.
                passingHint = when (ScreenCast.askAgain(this)) {
                    ScreenCast.Asked.WILL_ASK, ScreenCast.Asked.ALREADY_ASKING -> "Tap Start on your phone"
                    ScreenCast.Asked.NO_SCREEN -> "Open Disco Auto on your phone, then tap MIRROR"
                }
                passingHintUntil = SystemClock.elapsedRealtime() + PASSING_HINT_MS
                BridgeLog.log("casting: MIRROR touched on the car's screen ($passingHint)")
                sendMenu()
            }
            CarMenu.Choice.ALTERNATIVE -> host?.chosen(CarMenu.Choice.ALTERNATIVE)
            null -> Unit
        }
    }

    /** Android has a new picture of the screen. Keep only the newest, and convert at a steady pace. */
    private fun onImage(source: ImageReader) {
        if (source !== reader) return
        val newest = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return
        runCatching { pending?.close() }
        pending = newest
        if (!conversionQueued) {
            conversionQueued = true
            val wait = (lastConvertedAt + MIN_INTERVAL_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            handler.postDelayed(convert, wait)
        }
    }

    private val convert = Runnable {
        conversionQueued = false
        val image = pending
        pending = null
        if (image != null) {
            try {
                send(image)
            } catch (failure: Exception) {
                BridgeLog.log("casting: a picture could not be converted (${failure.javaClass.simpleName}: ${failure.message})")
            } finally {
                runCatching { image.close() }
            }
            lastConvertedAt = SystemClock.elapsedRealtime()
        }
    }

    private fun send(image: Image) {
        val plane = image.planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        val buffer = plane.buffer
        buffer.rewind()
        // The last row may come without its padding; a row short of the full picture is not missed.
        val rows = minOf(image.height, buffer.remaining() / plane.rowStride)
        if (rows <= 0 || rowPixels < image.width) return

        var whole = captured
        if (whole == null || whole.width != rowPixels || whole.height != rows) {
            whole?.recycle()
            whole = Bitmap.createBitmap(rowPixels, rows, Bitmap.Config.ARGB_8888)
            captured = whole
        }
        whole.copyPixelsFromBuffer(buffer)
        capturedWidth = image.width
        sendCaptured()
    }

    /** Scales the last picture taken of the screen to the car's size and hands it over. */
    private fun sendCaptured() {
        val car = client ?: return
        val target = sink ?: return
        val whole = captured ?: return
        if (capturedWidth <= 0) return

        var small = scaled
        if (small == null || small.width != car.width || small.height != car.height) {
            small?.recycle()
            small = Bitmap.createBitmap(car.width, car.height, Bitmap.Config.ARGB_8888)
            scaled = small
        }
        val canvas = Canvas(small)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(whole, Rect(0, 0, capturedWidth, whole.height), Rect(0, 0, car.width, car.height), smooth)

        val (data, compression) = encoder.encode(small, car)
        if (!target.offer(data, compression)) return
        frames++
        if (!showing) {
            showing = true
            BridgeLog.log("casting: the phone's screen is on the car's screen (first picture ${data.size} bytes)")
            onStatus(SHOWING)
        }
    }

    /**
     * A screen that has switched itself off has nothing to show, so it is kept on, dimmed, for as
     * long as it is being shown. The power button still turns it off.
     */
    @Suppress("DEPRECATION")
    private fun keepScreenOn(on: Boolean) {
        try {
            if (on) {
                if (screenLock == null) {
                    screenLock = appContext.getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "discoauto:screen")
                        .apply { setReferenceCounted(false) }
                }
                screenLock?.acquire(MAX_SCREEN_ON_MS)
            } else {
                screenLock?.let { if (it.isHeld) it.release() }
            }
        } catch (failure: Exception) {
            BridgeLog.log("casting: the screen could not be kept on (${failure.javaClass.simpleName}: ${failure.message})")
        }
    }

    companion object {
        private const val CAPTURE_SCALE = 2
        private const val MAX_IMAGES = 4
        private const val MIN_INTERVAL_MS = 100L
        private const val CARD_EVERY_MS = 1_000L
        private const val MAX_SCREEN_ON_MS = 12 * 60 * 60 * 1000L
        private const val PASSING_HINT_MS = 8_000L

        /** Under the menu when there is nothing more particular to say. */
        const val HINT = "See phone app for instructions"
        private const val HINT_CHOOSE = "Choose what to show"

        val WAITING = PictureStatus(
            showing = false,
            headline = "Connected to the car",
            detail = "To mirror your phone's screen on the car, tap Start mirroring, then Start in Android's prompt. " +
                "MIRROR on the car's screen does the same.",
            notification = "Connected to the car. Tap to mirror your phone's screen on it.",
            action = PictureStatus.Action("Start mirroring", PictureStatus.Action.Kind.ALLOW_CAST),
        )

        val SHOWING = PictureStatus(
            showing = true,
            headline = "Your phone's screen is on the car's screen",
            detail = "Turn the phone on its side to fill the car's screen. It stops when you unplug, lock the phone or tap Stop mirroring. " +
                "Whether touching the car's screen works the phone is below.",
            notification = "Your phone's screen is on the car's screen.",
            action = PictureStatus.Action("Stop mirroring", PictureStatus.Action.Kind.STOP_CAST),
        )
    }
}
