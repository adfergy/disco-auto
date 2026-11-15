// SPDX-License-Identifier: MIT
package app.spinbridge.picture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.spinbridge.BridgeLog
import app.spinbridge.ui.MainActivity

/**
 * Holds Android's screen share for as long as the phone's screen is on the car. Android will only
 * hand a share to an app with a foreground service of this kind running, and only after the owner
 * has said yes to its own prompt; this service is started with that answer and ends with the share.
 */
class CastService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null

    private val ended = object : MediaProjection.Callback() {
        override fun onStop() {
            projection = null
            finish("the screen share ended")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_STOP -> {
                BridgeLog.log("casting: stop requested")
                val current = projection
                if (current != null) current.stop() else finish("nothing to stop")
            }
            else -> if (projection == null) finish("nothing to do")
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun begin(intent: Intent) {
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (failure: RuntimeException) {
            BridgeLog.log("casting: Android refused the screen-sharing service (${failure.javaClass.simpleName}: ${failure.message})")
            stopSelf()
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val answer: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(EXTRA_ANSWER, Intent::class.java)
            else intent.getParcelableExtra(EXTRA_ANSWER)
        if (answer == null || resultCode != Activity.RESULT_OK) {
            finish("started without the owner's answer")
            return
        }
        // A second yes while a share is running replaces it.
        projection?.let { old ->
            old.unregisterCallback(ended)
            runCatching { old.stop() }
        }
        val granted = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, answer)
        } catch (failure: Exception) {
            BridgeLog.log("casting: Android did not hand over the screen share (${failure.javaClass.simpleName}: ${failure.message})")
            null
        }
        if (granted == null) {
            projection = null
            finish("no screen share")
            return
        }
        granted.registerCallback(ended, main)
        projection = granted
        BridgeLog.log("casting: the owner allowed the phone's screen to be shown")
        if (!ScreenCast.deliver(granted)) {
            BridgeLog.log("casting: nothing is waiting for the screen any more")
            granted.stop()
        }
    }

    private fun finish(why: String) {
        BridgeLog.log("casting service stopping: $why")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        projection?.let { left ->
            left.unregisterCallback(ended)
            runCatching { left.stop() }
        }
        projection = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen on the car", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 11, Intent(this, CastService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Disco Auto")
            .setContentText("Your phone's screen is being shown on the car.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop mirroring", stop).build())
            .build()
    }

    companion object {
        private const val ACTION_START = "app.spinbridge.action.CAST_START"
        private const val ACTION_STOP = "app.spinbridge.action.CAST_STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_ANSWER = "answer"
        private const val CHANNEL_ID = "screen-cast"
        private const val NOTIFICATION_ID = 2

        /** Called with the owner's yes, from the activity that put the question. */
        fun start(context: Context, resultCode: Int, answer: Intent) {
            context.startForegroundService(
                Intent(context, CastService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_ANSWER, answer)
            )
        }

        /** Ends the share, if there is one. */
        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, CastService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
