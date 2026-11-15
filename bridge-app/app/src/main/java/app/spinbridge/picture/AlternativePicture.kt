// SPDX-License-Identifier: MIT
package app.spinbridge.picture

import android.content.Context
import app.spinbridge.FrameSource

/**
 * A second kind of picture the app can put on the car instead of the phone's own screen.
 * [app.spinbridge.aa.AndroidAuto] is the one the app has.
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

    /**
     * The picture itself. [onStatus] is told, on whatever thread noticed, how it is getting on;
     * [host] is where it passes on a choice the owner makes on the car's menu.
     */
    fun createSource(context: Context, onStatus: (PictureStatus) -> Unit, host: PictureHost): FrameSource

    /** Its name on the car's menu. */
    val menuLabel: String

    /** What the car's menu says underneath while it cannot be chosen. */
    val menuHint: String

    /** What to tell the owner about it, on the phone, while it is not the picture; null to say nothing. */
    fun invitation(): Invitation?

    /** Extra lines for the help screen. */
    val troubleshooting: List<String>

    /** Runtime permissions it needs on top of the app's own. */
    val permissions: List<String>

    /**
     * Foreground-service types (ServiceInfo.FOREGROUND_SERVICE_TYPE_ bits) the car link should
     * hold for it on top of its own, given what the owner has allowed so far. Zero for none.
     */
    fun extraServiceTypes(context: Context): Int = 0

    data class Invitation(val title: String, val body: String, val action: PictureStatus.Action?)
}
