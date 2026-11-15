// SPDX-License-Identifier: MIT
package app.spinbridge.touch

import app.spinbridge.myspin.MySpinServer

/**
 * Touches on the car's screen, passed to the phone while the phone's screen is what the car is
 * showing. Android lets one app touch another only through an accessibility service the owner
 * has switched on, so this is the meeting point between the picture source, which knows what the
 * car was touched on, and [TouchService], which may or may not be there.
 */
object CarTouch {
    data class Point(val x: Float, val y: Float)

    /** The service, while the owner has it switched on. */
    @Volatile internal var service: TouchService? = null

    /** The piece of a touch most recently passed on, on the phone's screen, and until when it counts. */
    @Volatile private var echoFrom = Point(0f, 0f)
    @Volatile private var echoTo = Point(0f, 0f)
    @Volatile private var echoUntil = 0L

    /** Whether touches on the car's screen can be passed on. */
    val available: Boolean get() = service != null

    /** From the picture source, on the session's thread. Returns at once. */
    fun onCarTouches(touches: List<MySpinServer.Touch>, carWidth: Int, carHeight: Int) {
        service?.carTouches(touches, carWidth, carHeight)
    }

    /** The phone's screen is no longer on the car: a touch still held there is let go. */
    fun release() {
        service?.release()
    }

    /** From the service: a piece of a touch has gone to the phone's screen, from [from] to [to]. */
    internal fun passedOn(from: Point, to: Point, nowMs: Long, lastsMs: Long) {
        echoFrom = from
        echoTo = to
        echoUntil = nowMs + lastsMs + ECHO_AFTER_MS
    }

    /**
     * Whether a touch the phone's own screen reports at ([screenX], [screenY]) is one this app
     * has just put there. Only the self-test needs to ask: there the "car's screen" is on the
     * phone, so a touch passed on can land on it again.
     */
    fun isEcho(screenX: Float, screenY: Float, nowMs: Long): Boolean =
        nowMs < echoUntil && distanceToPiece(screenX, screenY, echoFrom, echoTo) < ECHO_WITHIN_PX

    internal fun distanceToPiece(x: Float, y: Float, from: Point, to: Point): Float {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = dx * dx + dy * dy
        val along = if (length == 0f) 0f else (((x - from.x) * dx + (y - from.y) * dy) / length).coerceIn(0f, 1f)
        val nearX = from.x + along * dx
        val nearY = from.y + along * dy
        return kotlin.math.hypot(x - nearX, y - nearY)
    }

    private const val ECHO_AFTER_MS = 250L
    private const val ECHO_WITHIN_PX = 40f

    /**
     * Where on the phone's screen a touch at ([carX], [carY]) on the car's falls.
     *
     * The phone's screen is shown in the middle of the car's, as large as fits and in its own
     * shape, with black beside or above it. A touch in the black is nowhere on the phone: null,
     * or with [clamp] the nearest point on the phone's edge (a finger that slides off the picture
     * is still dragging).
     */
    fun toPhone(
        carWidth: Int, carHeight: Int, phoneWidth: Int, phoneHeight: Int,
        carX: Int, carY: Int, clamp: Boolean = false,
    ): Point? {
        if (carWidth <= 0 || carHeight <= 0 || phoneWidth <= 0 || phoneHeight <= 0) return null
        val scale = minOf(carWidth.toFloat() / phoneWidth, carHeight.toFloat() / phoneHeight)
        val left = (carWidth - phoneWidth * scale) / 2f
        val top = (carHeight - phoneHeight * scale) / 2f
        // The middle of the car's pixel, in the phone's own pixels.
        val x = (carX + 0.5f - left) / scale
        val y = (carY + 0.5f - top) / scale
        val inside = x >= 0f && x < phoneWidth && y >= 0f && y < phoneHeight
        if (!inside && !clamp) return null
        return Point(x.coerceIn(0f, phoneWidth - 1f), y.coerceIn(0f, phoneHeight - 1f))
    }
}
