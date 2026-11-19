// SPDX-License-Identifier: MIT
package app.spinbridge.picture

import android.content.Context
import app.spinbridge.FrameSource

/**
 * A picture the app puts on the car whenever it can: [app.spinbridge.aa.AndroidAuto] is the one
 * the app has. While it cannot, the car shows the logo ([WaitingSource]). An interface so that
 * [CarPicture]'s switching can be tested without Android Auto.
 */
interface AlternativePicture {
    enum class Availability {
        /** It could be shown right now. */
        AVAILABLE,

        /** It could not. */
        UNAVAILABLE,

        /** There is no telling. */
        UNKNOWN,
    }

    /** Whether it could be shown right now. Must have no side effects: it is asked every second. */
    fun availability(): Availability

    /** The picture itself. [onStatus] is told, on whatever thread noticed, how it is getting on. */
    fun createSource(context: Context, onStatus: (PictureStatus) -> Unit): FrameSource

    /** The car's picture while it is not available. */
    fun waitingSource(context: Context, onStatus: (PictureStatus) -> Unit): FrameSource

    /** Runtime permissions it needs on top of the app's own. */
    val permissions: List<String>

    /**
     * Foreground-service types (ServiceInfo.FOREGROUND_SERVICE_TYPE_ bits) the car link should
     * hold for it on top of its own, given what the owner has allowed so far. Zero for none.
     */
    fun extraServiceTypes(context: Context): Int = 0
}
