// SPDX-License-Identifier: MIT
package app.spinbridge.touch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import app.spinbridge.BridgeLog
import app.spinbridge.myspin.MySpinServer

/**
 * The accessibility service that turns a touch on the car's screen into the same touch on the
 * phone's. It asks Android for the ability to perform gestures and nothing else: it receives no
 * accessibility events and cannot read what is on the screen. It only ever acts on touches
 * handed to it by [CarTouch], which happens only while the phone's screen is on the car.
 *
 * A finger on the car is followed as it moves, not replayed afterwards: its path is sent to
 * Android in short pieces, each one continuing the last, so a drag on the car scrolls the phone
 * under the finger. One finger at a time; a second finger on the car is ignored.
 */
class TouchService : AccessibilityService() {
    private val thread = HandlerThread("car-touch")
    private lateinit var handler: Handler

    // Everything below is used on the service's own thread only.
    private var finger: Long? = null
    private var reached: CarTouch.Point? = null
    private var wanted: CarTouch.Point? = null
    private var lifting = false
    private var held: GestureDescription.StrokeDescription? = null
    private var inFlight = false
    private var sentAt = 0L
    private var gestures = 0
    private var pieces = 0
    private var cancelled = 0
    private var complaints = 0
    private var heardAt = 0L
    private var cameDown: CarTouch.Point? = null
    private var cameDownAt = 0L

    override fun onCreate() {
        super.onCreate()
        thread.start()
        handler = Handler(thread.looper)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        BridgeLog.attach(applicationContext)
        BridgeLog.log("touch: the owner has switched on touch from the car's screen")
        CarTouch.service = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (CarTouch.service === this) CarTouch.service = null
        BridgeLog.log("touch: touch from the car's screen has been switched off ($gestures touches passed on)")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (CarTouch.service === this) CarTouch.service = null
        thread.quitSafely()
        super.onDestroy()
    }

    /** No events are asked for, and none are used. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** From [CarTouch], on whatever thread the car's touches arrive on. */
    internal fun carTouches(touches: List<MySpinServer.Touch>, carWidth: Int, carHeight: Int) {
        handler.post {
            // The phone's screen as it is turned (or folded) right now.
            val screen = try {
                getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            } catch (failure: Exception) {
                if (complaints++ < 3) BridgeLog.log("touch: the phone's screen size could not be read (${failure.javaClass.simpleName}: ${failure.message})")
                return@post
            }
            for (touch in touches) take(touch, carWidth, carHeight, screen.width(), screen.height())
            heardAt = SystemClock.uptimeMillis()
            send()
        }
    }

    /** From [CarTouch]: the phone's screen has left the car. Whatever is held there is let go. */
    internal fun release() {
        handler.post { lift("the phone's screen left the car") }
    }

    /** Ends a touch the car has not ended itself, where it has got to. */
    private fun lift(why: String) {
        if (finger == null || lifting) return
        BridgeLog.log("touch: letting go of a touch the car did not end ($why)")
        lifting = true
        send()
    }

    /**
     * A finger the car has said nothing about for [QUIET_LIMIT_MS] is taken to have gone: a lost
     * "up" must not leave the phone's screen held down.
     */
    private val quietCheck = object : Runnable {
        override fun run() {
            if (finger == null) return
            if (SystemClock.uptimeMillis() - heardAt >= QUIET_LIMIT_MS) lift("nothing from the car for ${QUIET_LIMIT_MS / 1000} s")
            if (finger != null) handler.postDelayed(this, QUIET_CHECK_MS)
        }
    }

    private fun take(touch: MySpinServer.Touch, carWidth: Int, carHeight: Int, phoneWidth: Int, phoneHeight: Int) {
        when (touch.action) {
            MySpinServer.TOUCH_DOWN -> {
                if (finger != null) return                         // one finger at a time
                // A finger that comes down in the black beside the picture is not on the phone.
                val at = CarTouch.toPhone(carWidth, carHeight, phoneWidth, phoneHeight, touch.x, touch.y) ?: return
                finger = touch.fingerId
                reached = null
                wanted = at
                lifting = false
                cameDown = at
                cameDownAt = SystemClock.uptimeMillis()
                pieces = 0
                handler.removeCallbacks(quietCheck)
                handler.postDelayed(quietCheck, QUIET_CHECK_MS)
            }
            MySpinServer.TOUCH_MOVED -> if (touch.fingerId == finger && !lifting) {
                wanted = CarTouch.toPhone(carWidth, carHeight, phoneWidth, phoneHeight, touch.x, touch.y, clamp = true) ?: wanted
            }
            MySpinServer.TOUCH_UP, MySpinServer.TOUCH_CANCELLED -> if (touch.fingerId == finger && !lifting) {
                wanted = CarTouch.toPhone(carWidth, carHeight, phoneWidth, phoneHeight, touch.x, touch.y, clamp = true) ?: wanted
                lifting = true
            }
        }
    }

    /**
     * Sends the next piece of the finger's path, if there is one to send and the last has
     * finished: from where the phone's touch has got to, to where the car's finger is now.
     */
    private fun send() {
        if (inFlight || finger == null) return
        val to = wanted ?: return
        val from = reached ?: to                                   // the first piece is a dot where the finger came down
        if (reached != null && from == to && !lifting) return      // resting: Android keeps the touch held
        val now = SystemClock.uptimeMillis()
        val duration = if (reached == null) FIRST_MS else (now - sentAt).coerceIn(SHORTEST_MS, LONGEST_MS)
        val continues = !lifting
        val accepted = try {
            val path = Path().apply {
                moveTo(from.x, from.y)
                lineTo(to.x, to.y)
            }
            val piece = held?.continueStroke(path, 0, duration, continues)
                ?: GestureDescription.StrokeDescription(path, 0, duration, continues)
            inFlight = true
            CarTouch.passedOn(from, to, now, duration)
            val sent = dispatchGesture(GestureDescription.Builder().addStroke(piece).build(), finished, handler)
            if (sent) held = if (continues) piece else null
            sent
        } catch (failure: Exception) {
            BridgeLog.log("touch: a touch could not be passed on (${failure.javaClass.simpleName}: ${failure.message})")
            false
        }
        if (!accepted) {
            if (complaints++ < 3) BridgeLog.log("touch: Android would not take a touch from the car's screen")
            letGo()
            return
        }
        reached = to
        sentAt = now
        pieces++
        if (!continues) {
            finger = null
            gestures++
            // The first few in full, then now and again: enough to see in the log that it works.
            if (gestures <= 5 || gestures % 50 == 0) {
                val from0 = cameDown
                BridgeLog.log(
                    "touch: #$gestures passed on, from ${from0?.x?.toInt()},${from0?.y?.toInt()} to " +
                        "${to.x.toInt()},${to.y.toInt()} on the phone's screen in $pieces pieces over ${now - cameDownAt} ms"
                )
            }
        }
    }

    private val finished = object : GestureResultCallback() {
        override fun onCompleted(gesture: GestureDescription?) {
            inFlight = false
            if (finger == null) letGo() else send()
        }

        // Android stops a gesture when something else touches the screen: the phone's owner wins.
        override fun onCancelled(gesture: GestureDescription?) {
            if (cancelled++ < 5) BridgeLog.log("touch: Android stopped a touch part-way (something else touched the screen)")
            letGo()
        }
    }

    private fun letGo() {
        finger = null
        reached = null
        wanted = null
        lifting = false
        held = null
        inFlight = false
        handler.removeCallbacks(quietCheck)
    }

    private companion object {
        /**
         * How long the dot that starts a touch lasts, and the bounds on each later piece. A piece
         * is given the time the finger took over it, but never long: on a slow phone the pieces
         * then play back faster than they were drawn, so the phone's touch catches the finger up
         * instead of falling further behind it through a long drag.
         */
        const val FIRST_MS = 10L
        const val SHORTEST_MS = 10L
        const val LONGEST_MS = 40L

        /** How long a finger may rest with no word from the car before it is taken to have gone. */
        const val QUIET_LIMIT_MS = 15_000L
        const val QUIET_CHECK_MS = 2_000L
    }
}
