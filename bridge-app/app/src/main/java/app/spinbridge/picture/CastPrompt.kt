// SPDX-License-Identifier: MIT
package app.spinbridge.picture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import app.spinbridge.BridgeLog

/**
 * Puts Android's "share your screen?" question on behalf of one of the app's screens and passes
 * the answer on. The question is Android's own and cannot be skipped or answered for the owner:
 * it is asked once each time a picture source starts waiting, and again whenever they tap to.
 *
 * The activity forwards its onResume, onPause and onActivityResult.
 */
class CastPrompt(private val activity: Activity, private val unpromptedLimit: Int = Int.MAX_VALUE) {
    private var resumed = false
    private var showing = false
    private var unprompted = 0
    private val listener: () -> Unit = { activity.runOnUiThread { if (resumed) askIfDue() } }

    fun onResume() {
        if (resumed) return
        resumed = true
        ScreenCast.screenInFront(true)
        ScreenCast.addListener(listener)
        askIfDue()
    }

    fun onPause() {
        if (!resumed) return
        resumed = false
        ScreenCast.screenInFront(false)
        ScreenCast.removeListener(listener)
    }

    /**
     * Puts the question when one is due. One the owner asked for is always put. One that is due
     * only because a picture source started waiting is put at most [unpromptedLimit] times in
     * this screen's life, for a screen that starts a new wait every time it comes back into view.
     */
    private fun askIfDue() {
        if (showing) return
        when (ScreenCast.takeTurnToAsk()) {
            ScreenCast.Turn.ASKED_FOR -> ask()
            ScreenCast.Turn.UNPROMPTED -> if (unprompted < unpromptedLimit) {
                unprompted++
                ask()
            }
            null -> Unit
        }
    }

    /** Puts the question now. */
    fun ask() {
        if (showing) return
        val manager = activity.getSystemService(MediaProjectionManager::class.java)
        // On Android 14 and later, ask for the whole screen rather than offering a choice of one
        // app: the car is to show what the phone shows.
        val question =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
        try {
            showing = true
            ScreenCast.dialogUp(true)
            @Suppress("DEPRECATION")
            activity.startActivityForResult(question, REQUEST_CODE)
        } catch (failure: Exception) {
            showing = false
            ScreenCast.dialogUp(false)
            BridgeLog.log("casting: Android's screen-sharing prompt could not be opened (${failure.javaClass.simpleName}: ${failure.message})")
        }
    }

    /** The screen is going away for good: a question still up no longer has anyone to answer to. */
    fun onDestroy() {
        if (showing) {
            showing = false
            ScreenCast.dialogUp(false)
        }
        onPause()
    }

    /** True when the result was this prompt's. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE) return false
        if (showing) {
            showing = false
            ScreenCast.dialogUp(false)
        }
        if (resultCode == Activity.RESULT_OK && data != null) {
            CastService.start(activity.applicationContext, resultCode, data)
        } else {
            BridgeLog.log("casting: the owner did not allow the phone's screen to be shown")
        }
        return true
    }

    private companion object {
        const val REQUEST_CODE = 5201
    }
}
