// SPDX-License-Identifier: MIT
package app.spinbridge.selftest

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.spinbridge.BridgeLog
import app.spinbridge.FrameSource
import app.spinbridge.LinkStatus
import app.spinbridge.picture.CarPicture
import app.spinbridge.picture.CastPrompt
import app.spinbridge.touch.CarTouch
import app.spinbridge.myspin.MySpinServer
import app.spinbridge.myspin.MySpinSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.concurrent.thread

/**
 * Runs the real session code against [LoopbackCar] and shows, on the phone, the frames the car
 * would have drawn. Touching the picture sends the touch back through the simulated car, so the
 * mark that appears has been all the way round the loop. It opens as a copy of the Discovery
 * Sport's head unit (671x424, protocol 1.0.3, zlib RGB565); the other buttons are generic head
 * units for the protocol revisions this car does not speak.
 */
class SelfTestActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val castPrompt = CastPrompt(this, unpromptedLimit = 1)
    private lateinit var picture: ImageView
    private lateinit var caption: TextView
    private lateinit var note: TextView

    /** What the picture source last said about itself. */
    @Volatile private var pictureSays = ""
    private var visible = false

    /** A car being plugged in ends the self-test: the real link gets everything to itself. */
    private val linkListener: () -> Unit = { main.post { if (visible) followLink() } }

    private var car: LoopbackCar? = null
    private var session: MySpinSession? = null
    private var source: FrameSource? = null
    private var generation = MySpinServer.Generation.V1_0
    private var discoverySport = true
    private var startedAt = 0L
    @Volatile private var frameWidth = SPORT_WIDTH
    @Volatile private var frameHeight = SPORT_HEIGHT
    @Volatile private var lastBytes = 0
    @Volatile private var carPixelFormat = MySpinServer.PIXEL_RGB565
    @Volatile private var carEndianness = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeLog.attach(applicationContext)
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setOnApplyWindowInsetsListener { target, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                target.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
                insets
            }
        }
        caption = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
        }
        root.addView(caption)
        picture = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnTouchListener { _, event -> forwardTouch(event) }
        }
        root.addView(picture, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad; bottomMargin = pad })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Disco Sport") { restart(MySpinServer.Generation.V1_0, sport = true) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.5f))
            for (choice in MySpinServer.Generation.entries) {
                addView(button(choice.label) { restart(choice, sport = false) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Home key") { key(MySpinServer.SOFTKEY_HOME) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("Back key") { key(MySpinServer.SOFTKEY_BACK) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("Leave 6 s") { car?.leaveScreen(6_000) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("Share screen") { castPrompt.ask() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.2f))
        })
        note = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
        root.addView(note)
        setContentView(root)
    }

    /**
     * Debuggable builds only: lets the simulated car be told, over adb, to report touches of its
     * own, so that what the app does with a car's touches can be tried without a finger (or
     * adb's own make-believe finger) on this screen at the same time.
     * `am broadcast -a app.spinbridge.debug.CAR_TOUCH --es do "swipe 400 380 400 300 800"`;
     * also `tap x y` and `hold x y ms`. The numbers are the car's own pixels.
     */
    private val debugTouches = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val words = intent.getStringExtra("do")?.trim()?.split(Regex("\\s+")) ?: return
            val active = car ?: return
            thread(name = "debug-car-touch", isDaemon = true) {
                try {
                    val n = words.drop(1).map { it.toInt() }
                    when (words[0]) {
                        "tap" -> {
                            active.touch(MySpinServer.TOUCH_DOWN, n[0], n[1], 0)
                            Thread.sleep(60)
                            active.touch(MySpinServer.TOUCH_UP, n[0], n[1], 0)
                        }
                        "hold" -> {
                            active.touch(MySpinServer.TOUCH_DOWN, n[0], n[1], 0)
                            Thread.sleep(n[2].toLong())
                            active.touch(MySpinServer.TOUCH_UP, n[0], n[1], 0)
                        }
                        "swipe" -> {
                            val steps = (n[4] / 25).coerceAtLeast(1)
                            active.touch(MySpinServer.TOUCH_DOWN, n[0], n[1], 0)
                            for (step in 1..steps) {
                                Thread.sleep(25)
                                active.touch(
                                    MySpinServer.TOUCH_MOVED,
                                    n[0] + (n[2] - n[0]) * step / steps, n[1] + (n[3] - n[1]) * step / steps, 0
                                )
                            }
                            active.touch(MySpinServer.TOUCH_UP, n[2], n[3], 0)
                        }
                    }
                } catch (failure: Exception) {
                    BridgeLog.log("self-test: debug touch \"${words.joinToString(" ")}\" failed: ${failure.message}")
                }
            }
        }
    }
    private var debugTouchesOn = false

    override fun onStart() {
        super.onStart()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            registerReceiver(debugTouches, IntentFilter(ACTION_DEBUG_CAR_TOUCH), Context.RECEIVER_EXPORTED)
            debugTouchesOn = true
        }
        visible = true
        LinkStatus.addListener(linkListener)
        followLink()
        main.post(captionTick)
    }

    override fun onResume() {
        super.onResume()
        castPrompt.onResume()
    }

    override fun onPause() {
        castPrompt.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (debugTouchesOn) {
            runCatching { unregisterReceiver(debugTouches) }
            debugTouchesOn = false
        }
        visible = false
        LinkStatus.removeListener(linkListener)
        main.removeCallbacks(captionTick)
        stopLoop()
        super.onStop()
    }

    override fun onDestroy() {
        castPrompt.onDestroy()
        super.onDestroy()
    }

    @Deprecated("The platform's own result callback; this app has no AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!castPrompt.onActivityResult(requestCode, resultCode, data)) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    /**
     * Runs the simulated car only while no real one is connected. With a real car on the cable
     * the two would both be asking for the phone's screen, or for whatever else the car is shown.
     */
    private fun followLink() {
        if (LinkStatus.state == LinkStatus.State.NOT_CONNECTED) {
            note.text = "A simulated car screen inside the phone: it checks the app, not the car. " +
                "It shows what the car would be shown; touch the picture to send the touch through."
            if (session == null) restart(generation, discoverySport)
        } else {
            stopLoop()
            pictureSays = ""
            note.text = "The phone is connected to a car, so the self-test is off. Unplug to use it."
        }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun restart(choice: MySpinServer.Generation, sport: Boolean) {
        stopLoop()
        generation = choice
        discoverySport = sport
        startedAt = SystemClock.elapsedRealtime()
        val width = if (sport) SPORT_WIDTH else CAR_WIDTH
        val height = if (sport) SPORT_HEIGHT else CAR_HEIGHT
        BridgeLog.log(
            "self-test: simulated ${width}x$height head unit speaking mySPIN ${choice.label}" +
                if (sport) ", behaving as the Discovery Sport's did" else ""
        )

        if (LinkStatus.state != LinkStatus.State.NOT_CONNECTED) return
        pictureSays = ""
        val frames: FrameSource = CarPicture(this, onStatus = { status -> pictureSays = status.headline })
        val simulated = LoopbackCar(
            width = width,
            height = height,
            generation = choice,
            // The real head unit took a picture about every 120 ms, and none until the apps screen was opened.
            frameIntervalMillis = if (sport) 110 else 40,
            onFrame = ::showFrame,
            log = BridgeLog::log,
            discoverySport = sport,
            opensScreenAfterMillis = if (sport) 2_500 else 0,
        )
        var mine: MySpinSession? = null
        val opened = MySpinSession(
            input = simulated.phoneInput,
            output = simulated.phoneOutput,
            log = BridgeLog::log,
            listener = object : MySpinSession.Listener {
                override fun onRunning(client: MySpinServer.Client) {
                    carPixelFormat = client.pixelFormat
                    carEndianness = client.endianness
                    // This listener's own session, not whichever one the activity has moved on to.
                    val active = mine ?: return
                    frames.start(client, object : app.spinbridge.FrameSink {
                        override fun offer(data: ByteArray, compression: Int) = active.offerFrame(data, compression)
                        override fun framesTaken() = active.stats.framesSent
                    })
                }
                override fun onTouch(touches: List<MySpinServer.Touch>) = frames.onTouch(touches)
                override fun onSoftkey(key: Int, pressed: Boolean) = frames.onSoftkey(key, pressed)
                override fun onClientState(kind: Int, value: Int) = frames.onClientState(kind, value)
                override fun onFrameFormatChanged(client: MySpinServer.Client) = frames.onClientChanged(client)
                override fun onCustomKey(action: Int, key: Int) = frames.onCustomKey(action, key)
                override fun onCustomString(type: Int, value: String) {
                    BridgeLog.log("self-test: car string $type = \"$value\"")
                    if (type == MySpinServer.CUSTOM_STRING_BT_MAC) session?.sendBluetoothName("Self-test phone")
                }
                override fun onClosed(error: Throwable?) {
                    frames.stop()
                }
            },
        )
        mine = opened
        car = simulated
        session = opened
        source = frames
        simulated.start()
        opened.start()
    }

    private fun stopLoop() {
        session?.close()
        source?.stop()
        car?.close()
        session = null
        source = null
        car = null
    }

    /** Called on the simulated car's thread for every frame it receives. */
    private fun showFrame(data: ByteArray, compression: Int, width: Int, height: Int) {
        val decoded: Bitmap = when (compression) {
            MySpinServer.COMPRESSION_JPEG -> BitmapFactory.decodeByteArray(data, 0, data.size)
            MySpinServer.COMPRESSION_ZLIB -> inflateRgb565(data, width, height)
            else -> null
        } ?: return
        frameWidth = width
        frameHeight = height
        lastBytes = data.size
        main.post { picture.setImageBitmap(decoded) }
    }

    /**
     * What the Discovery Sport's head unit does with a frame: inflate it and treat the bytes as
     * little-endian RGB565. Null for any other pixel format, which this preview does not draw.
     */
    private fun inflateRgb565(data: ByteArray, width: Int, height: Int): Bitmap? {
        if (carPixelFormat != MySpinServer.PIXEL_RGB565 || carEndianness != 2) return null
        val raw = ByteArray(width * height * 2)
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            var filled = 0
            while (filled < raw.size && !inflater.finished()) {
                val count = inflater.inflate(raw, filled, raw.size - filled)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                filled += count
            }
            if (filled != raw.size) {
                BridgeLog.log("self-test: frame inflated to $filled bytes, expected ${raw.size}")
                return null
            }
        } catch (failure: Exception) {
            BridgeLog.log("self-test: frame would not inflate: ${failure.message}")
            return null
        } finally {
            inflater.end()
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN))
        }
    }

    private val captionTick = object : Runnable {
        override fun run() {
            val frames = car?.framesReceived ?: 0
            val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
            val rate = if (seconds > 0.5) frames / seconds else 0.0
            caption.text = (if (discoverySport) "Discovery Sport copy, " else "") +
                "mySPIN ${generation.label}  frames $frames  ${"%.1f".format(rate)} fps  last ${lastBytes / 1024} KiB" +
                (if (pictureSays.isEmpty()) "" else "\n$pictureSays")
            main.postDelayed(this, 500)
        }
    }

    private fun forwardTouch(event: MotionEvent): Boolean {
        // A touch passed on from the "car" can land on this same picture: that is its own echo,
        // not another touch on the car.
        if (CarTouch.isEcho(event.rawX, event.rawY, SystemClock.uptimeMillis())) return true
        val active = car ?: return false
        val viewWidth = picture.width.toFloat()
        val viewHeight = picture.height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return false
        // FIT_CENTER: uniform scale, centred.
        val scale = minOf(viewWidth / frameWidth, viewHeight / frameHeight)
        val left = (viewWidth - frameWidth * scale) / 2
        val top = (viewHeight - frameHeight * scale) / 2
        fun send(action: Int, pointer: Int) {
            val x = ((event.getX(pointer) - left) / scale).toInt()
            val y = ((event.getY(pointer) - top) / scale).toInt()
            active.touch(action, x, y, event.getPointerId(pointer))
        }
        // Every finger is forwarded, so the two-finger step can be tried on the phone too.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> send(MySpinServer.TOUCH_DOWN, event.actionIndex)
            MotionEvent.ACTION_MOVE -> for (pointer in 0 until event.pointerCount) send(MySpinServer.TOUCH_MOVED, pointer)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> send(MySpinServer.TOUCH_UP, event.actionIndex)
            MotionEvent.ACTION_CANCEL -> for (pointer in 0 until event.pointerCount) send(MySpinServer.TOUCH_CANCELLED, pointer)
        }
        return true
    }

    private fun key(key: Int) {
        car?.softkey(key, pressed = true)
        car?.softkey(key, pressed = false)
    }

    private companion object {
        private const val ACTION_DEBUG_CAR_TOUCH = "app.spinbridge.debug.CAR_TOUCH"
        private const val CAR_WIDTH = 800
        private const val CAR_HEIGHT = 480

        /** The picture size the Discovery Sport's head unit asked for. */
        private const val SPORT_WIDTH = 671
        private const val SPORT_HEIGHT = 424
    }
}
