// SPDX-License-Identifier: MIT
package app.spinbridge.aa

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.Settings
import app.spinbridge.FrameSource
import app.spinbridge.picture.AlternativePicture
import app.spinbridge.picture.PictureStatus
import app.spinbridge.picture.WaitingSource

/**
 * Android Auto as the car's picture, shown whenever its head unit server is running. All of the
 * app's Android Auto wording is here.
 */
object AndroidAuto : AlternativePicture {
    private const val PACKAGE = "com.google.android.projection.gearhead"

    /** Android Auto's own settings screen, whose three-dot menu starts and stops the head unit server. */
    private fun settings(): List<Intent> = listOf(
        Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", PACKAGE, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    /** The button that opens Android Auto's settings. */
    val OPEN_SETTINGS = PictureStatus.Action("Open Android Auto settings", settings())

    override fun availability(): AlternativePicture.Availability = when (HeadUnitServer.look()) {
        HeadUnitServer.State.LISTENING -> AlternativePicture.Availability.AVAILABLE
        HeadUnitServer.State.NOT_RUNNING -> AlternativePicture.Availability.UNAVAILABLE
        HeadUnitServer.State.UNKNOWN -> AlternativePicture.Availability.UNKNOWN
    }

    override fun createSource(context: Context, onStatus: (PictureStatus) -> Unit): FrameSource =
        AndroidAutoSource(context, onStatus)

    override fun waitingSource(context: Context, onStatus: (PictureStatus) -> Unit): FrameSource =
        WaitingSource(context, onStatus, WAITING, CAR_FOLLOW_PHONE)

    /** On the car's screen, under the logo, whenever the owner has something to do on the phone. */
    const val CAR_FOLLOW_PHONE = "Follow the instructions on your phone to get Android Auto running"

    /** Under the logo on the car's screen while Android Auto is on its way. */
    fun carMessage(status: PictureStatus): String =
        if (status == CONNECTING) "Connecting to Android Auto\u2026" else CAR_FOLLOW_PHONE

    val troubleshooting = listOf(
        "The car shows the Disco Auto logo: Android Auto's head unit server is not running. Start it from " +
            "Android Auto's settings, three-dot menu.",
        "The phone says Android Auto's server is not answering: in the same menu choose Stop head unit server, " +
            "then Start head unit server.",
    )

    // Android Auto's assistant listens through this app while it is on the car's screen.
    override val permissions = listOf(Manifest.permission.RECORD_AUDIO)

    /** The microphone type is what lets the assistant hear the driver while the app is in the background. */
    override fun extraServiceTypes(context: Context): Int =
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

    /** Connected to the car, and Android Auto's head unit server is not running. */
    val WAITING = PictureStatus(
        showing = false,
        headline = "Connected to the car. Now start Android Auto",
        detail = "Android Auto's head unit server is not running. Start it as shown below and Android Auto " +
            "appears on the car's screen by itself.",
        notification = "Connected to the car. Start Android Auto's head unit server.",
    )

    val CONNECTING = PictureStatus(
        showing = false,
        headline = "Connecting to Android Auto",
        detail = "Android Auto should be on the car's screen in a moment.",
        notification = "Connected to the car. Waiting for Android Auto.",
    )

    val NO_PICTURE = PictureStatus(
        showing = false,
        headline = "Waiting for Android Auto",
        detail = "Android Auto is connected but has not sent a picture yet. Unlock the phone: Android Auto may be " +
            "asking you something.",
        notification = "Android Auto is connected but has sent no picture. Unlock the phone.",
    )

    val SERVER_GONE = PictureStatus(
        showing = false,
        headline = "Android Auto's server has stopped",
        detail = "Start it again from Android Auto's settings: three dots, Start head unit server.",
        notification = "Android Auto's head unit server has stopped.",
        action = OPEN_SETTINGS,
    )

    val NOT_ANSWERING = PictureStatus(
        showing = false,
        headline = "Android Auto's server is not answering",
        detail = "Open Android Auto's settings, tap the three dots and choose Stop head unit server. Then tap the " +
            "three dots again and choose Start head unit server.",
        notification = "Android Auto's head unit server is not answering. Tap for what to do.",
        action = OPEN_SETTINGS,
    )

    val ON_SCREEN = PictureStatus(
        showing = true,
        headline = "Android Auto is on the car's screen",
        detail = "Use it from the car's screen. You can put the phone away.",
        notification = "Android Auto is on the car's screen.",
    )

    fun trouble(what: String) = PictureStatus(
        showing = false,
        headline = "Android Auto cannot be shown",
        detail = "Android Auto connected, but $what.",
        notification = "Android Auto connected but cannot be shown. Tap for details.",
    )
}
