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
import app.spinbridge.picture.PictureHost
import app.spinbridge.picture.PictureStatus

/**
 * Android Auto as the picture that takes over from the phone's screen whenever its head unit
 * server is running. All of the app's Android Auto wording is here.
 */
object AndroidAuto : AlternativePicture {
    private const val PACKAGE = "com.google.android.projection.gearhead"

    /** Android Auto's own settings screen, whose three-dot menu starts and stops the head unit server. */
    private fun settings(): List<Intent> = listOf(
        Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", PACKAGE, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun openSettings() =
        PictureStatus.Action("Open Android Auto settings", PictureStatus.Action.Kind.OPEN, settings())

    override fun availability(): AlternativePicture.Availability = when (HeadUnitServer.look()) {
        HeadUnitServer.State.LISTENING -> AlternativePicture.Availability.AVAILABLE
        HeadUnitServer.State.NOT_RUNNING -> AlternativePicture.Availability.UNAVAILABLE
        HeadUnitServer.State.UNKNOWN -> AlternativePicture.Availability.UNKNOWN
    }

    override fun createSource(context: Context, onStatus: (PictureStatus) -> Unit, host: PictureHost): FrameSource =
        AndroidAutoSource(context, onStatus, host)

    override val menuLabel = "ANDROID AUTO"
    override val menuHint = "See phone app for pairing instructions"

    /** Under the car's menu while Android Auto is the picture on its way. */
    fun carHint(status: PictureStatus): String =
        if (status == CONNECTING) "Connecting to Android Auto..." else "See phone app for pairing instructions"

    /**
     * With the server stopped: how to set Android Auto up, developer mode included. With it
     * running (the owner chose MIRROR for now): that it is ready.
     */
    override fun invitation(): AlternativePicture.Invitation =
        if (HeadUnitServer.look() == HeadUnitServer.State.LISTENING) READY else PAIRING

    private val PAIRING = AlternativePicture.Invitation(
        title = "Android Auto: pairing instructions",
        body = "To have Android Auto on the car's screen instead of your phone's screen:\n\n" +
            "1. Tap the button below to open Android Auto's settings.\n\n" +
            "2. First time only, switch on developer mode: scroll to the bottom, tap Version ten times " +
            "and accept. Three dots appear at the top right.\n\n" +
            "3. Tap the three dots and choose Start head unit server.\n\n" +
            "Android Auto takes over the car's screen a few seconds later. The server keeps running until " +
            "the phone restarts, so step 3 is once per restart and step 2 is once only.",
        action = openSettings(),
    )

    private val READY = AlternativePicture.Invitation(
        title = "Android Auto is ready",
        body = "Its head unit server is running. Tap ANDROID AUTO on the car's screen to switch to it.",
        action = null,
    )

    override val troubleshooting = listOf(
        "Android Auto is expected and the phone's screen appears instead: Android Auto's head unit server is not " +
            "running. Start it from Android Auto's settings, three-dot menu.",
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

    val CONNECTING = PictureStatus(
        showing = false,
        headline = "Connected to the car, waiting for Android Auto",
        detail = "Android Auto should be on the car's screen in a moment.",
        notification = "Connected to the car. Waiting for Android Auto.",
    )

    val NO_PICTURE = PictureStatus(
        showing = false,
        headline = "Connected to the car, waiting for Android Auto",
        detail = "Android Auto is connected but has not sent a picture yet. Unlock the phone: Android Auto may be " +
            "asking something.",
        notification = "Android Auto is connected but has sent no picture. Unlock the phone.",
    )

    val SERVER_GONE = PictureStatus(
        showing = false,
        headline = "Android Auto's server has stopped",
        detail = "If it stays stopped, the menu comes back on the car's screen after about fifteen seconds.",
        notification = "Android Auto's head unit server has stopped.",
    )

    val NOT_ANSWERING = PictureStatus(
        showing = false,
        headline = "Android Auto's server is not answering",
        detail = "Its head unit server is running but takes no connections. Open Android Auto's settings, tap the " +
            "three dots, choose Stop head unit server, then the three dots again and Start head unit server.",
        notification = "Android Auto's head unit server is not answering. Tap for what to do.",
        action = openSettings(),
    )

    val ON_SCREEN = PictureStatus(
        showing = true,
        headline = "Android Auto is on the car's screen",
        detail = "To mirror the phone's own screen instead, stop Android Auto's head unit server from its settings, " +
            "three-dot menu. The car's menu comes back about fifteen seconds later.",
        notification = "Android Auto is on the car's screen.",
        action = openSettings(),
    )

    fun trouble(what: String) = PictureStatus(
        showing = false,
        headline = "Android Auto cannot be shown",
        detail = "Android Auto connected, but $what. The log under Help and log has the details.",
        notification = "Android Auto connected but cannot be shown. Tap for details.",
    )
}
