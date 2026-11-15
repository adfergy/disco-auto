// SPDX-License-Identifier: MIT
package app.spinbridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.Settings
import app.spinbridge.aa.AndroidAuto
import app.spinbridge.picture.CarPicture
import app.spinbridge.picture.PictureStatus
import app.spinbridge.myspin.MySpinServer
import app.spinbridge.myspin.MySpinSession
import app.spinbridge.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Owns the USB accessory and the mySPIN session for as long as the phone is plugged into the car.
 * A foreground service, so the link survives the screen turning off and the app leaving the
 * foreground.
 */
class BridgeService : Service() {

    /**
     * Everything that belongs to one plug-in. Kept together so that an old connection finishing
     * its shutdown late (a head unit that drops and re-enumerates USB does exactly this) releases
     * only its own descriptor and wake lock, never those of the connection that replaced it.
     */
    private inner class Link(val descriptor: ParcelFileDescriptor, val source: FrameSource) {
        lateinit var session: MySpinSession
        val wakeLock: PowerManager.WakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spinbridge:session")
            .apply { acquire(12 * 60 * 60 * 1000L) }
        private var released = false

        /** Main thread only. Safe to call more than once. */
        fun release() {
            if (released) return
            released = true
            runCatching { source.stop() }
            runCatching { session.close() }
            runCatching { descriptor.close() }
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var link: Link? = null
    private var positionLines = 0

    /** The service types the running link was started with, kept for a repeated attach. */
    private var foregroundTypes: Int? = null

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                BridgeLog.log("USB accessory detached")
                link?.session?.close()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BridgeLog.attach(this)
        createChannel()
        val filter = IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(detachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // The export flag does not exist before Android 13; a system broadcast needs none.
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(detachReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ATTACH -> {
                // A second attach while the link is up must not put the notification back to
                // "connecting": it keeps saying what is true.
                startInForeground(if (link?.session?.isOpen == true) status else "Connecting to the car")
                val accessory = accessoryFrom(intent)
                if (accessory == null) {
                    BridgeLog.log("start request carried no USB accessory")
                    if (link == null) endService("No USB accessory")
                } else {
                    attach(accessory)
                }
            }
            ACTION_STOP -> {
                BridgeLog.log("stop requested")
                val current = link
                if (current != null && current.session.isOpen) current.session.close() else endService("Stopped")
            }
            else -> if (link == null) endService("Idle")
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun accessoryFrom(intent: Intent): UsbAccessory? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }

    private fun attach(accessory: UsbAccessory) {
        link?.let { existing ->
            if (existing.session.isOpen) {
                BridgeLog.log("already connected; ignoring a second attach")
                return
            }
            // The previous connection has ended but its cleanup has not run yet. Do it now, so the
            // accessory is free to be reopened and its late cleanup finds nothing left to do.
            BridgeLog.log("releasing the previous connection before reconnecting")
            existing.release()
            link = null
        }
        val usb = getSystemService(UsbManager::class.java)
        BridgeLog.log(describe(accessory))
        if (!usb.hasPermission(accessory)) {
            BridgeLog.log("no permission for this accessory; open Disco Auto and press Connect")
            endService("USB permission needed")
            return
        }
        val opened = try {
            usb.openAccessory(accessory)
        } catch (failure: Exception) {
            BridgeLog.log("openAccessory failed: ${failure.javaClass.simpleName}: ${failure.message}")
            null
        }
        if (opened == null) {
            BridgeLog.log("the accessory could not be opened (another app may hold it)")
            endService("Could not open the USB accessory")
            return
        }

        lateinit var created: Link
        created = Link(opened, CarPicture(this, onStatus = { status -> main.post { pictureChanged(created, status) } }))
        created.session = MySpinSession(
            input = FileInputStream(opened.fileDescriptor),
            output = FileOutputStream(opened.fileDescriptor),
            listener = SessionListener(created),
            log = BridgeLog::log,
            // The launcher gives the car 5 s to start the handshake. While we are still learning
            // how this head unit behaves, waiting costs nothing and a log of a long wait is worth
            // more than a tidy disconnect; unplugging ends the session.
            handshakeTimeoutMillis = 0,
        )
        link = created
        LinkStatus.set(LinkStatus.State.CONNECTING_TO_CAR)
        setStatus("Connecting to the car")
        created.session.start()
    }

    /** Main thread. What the car is being shown over [owner], or what it is waiting for, changed. */
    private fun pictureChanged(owner: Link, status: PictureStatus) {
        if (link !== owner || !owner.session.isOpen) return
        LinkStatus.show(status)
        setStatus(status.notification)
    }

    private inner class SessionListener(private val owner: Link) : MySpinSession.Listener {
        private val frames: FrameSource get() = owner.source

        override fun onRunning(client: MySpinServer.Client) {
            BridgeLog.log("connected to \"${client.name}\" ${client.width}x${client.height}, mySPIN ${client.version}")
            main.post {
                if (link !== owner) return@post
                // What the car is shown, and so what the phone says, comes from the picture
                // source as soon as it has started (pictureChanged).
                LinkStatus.set(LinkStatus.State.CONNECTING_TO_CAR, client.name)
                setStatus("Connected to the car")
            }
            val active = owner.session
            frames.start(client, object : FrameSink {
                override fun offer(data: ByteArray, compression: Int) = active.offerFrame(data, compression)
                override fun framesTaken() = active.stats.framesSent
            })
        }

        override fun onTouch(touches: List<MySpinServer.Touch>) = frames.onTouch(touches)

        override fun onSoftkey(key: Int, pressed: Boolean) {
            BridgeLog.log("softkey $key ${if (pressed) "pressed" else "released"}")
            frames.onSoftkey(key, pressed)
        }

        override fun onClientState(kind: Int, value: Int) {
            BridgeLog.log(
                when (kind) {
                    1 -> "car is ${if (value != 0) "moving" else "stationary"}"
                    2 -> "car display is in ${if (value != 0) "night" else "day"} mode"
                    else -> "car state kind=$kind value=$value"
                }
            )
            frames.onClientState(kind, value)
        }

        override fun onFrameFormatChanged(client: MySpinServer.Client) {
            BridgeLog.log("frame format now: ${client.describe()}")
            frames.onClientChanged(client)
        }

        override fun onCustomString(type: Int, value: String) {
            if (type == MySpinServer.CUSTOM_STRING_BT_MAC) {
                BridgeLog.log("the car sent its Bluetooth address (not logged); replying with this phone's Bluetooth name")
                val name = bluetoothName()
                owner.session.sendBluetoothName(name)
            } else {
                BridgeLog.log("car info string $type: \"$value\"")
            }
        }

        override fun onCustomKey(action: Int, key: Int) {
            BridgeLog.log("car hard key $key action $action")
            frames.onCustomKey(action, key)
        }

        override fun onAudioStatus(requestId: Long, status: Int, result: Int) {
            val meaning = when (status) {
                MySpinServer.AUDIO_STATUS_OPEN -> "open (the car gave us its speakers)"
                MySpinServer.AUDIO_STATUS_REJECT -> "rejected"
                MySpinServer.AUDIO_STATUS_SUSPEND -> "suspended"
                MySpinServer.AUDIO_STATUS_CLOSE -> "closed"
                else -> "status $status"
            }
            BridgeLog.log("car audio answer for request $requestId: $meaning, result $result")
        }

        override fun onPosition(nmea: String) {
            // Counted, not written down: where the car is has no business in a log that may be shared.
            if (positionLines++ == 0) BridgeLog.log("the car is sending its GPS position (not logged)")
        }

        override fun onClosed(error: Throwable?) {
            main.post {
                owner.release()
                if (link === owner) {
                    link = null
                    endService(error?.message?.let { "Disconnected: $it" } ?: "Disconnected")
                } else {
                    BridgeLog.log("an earlier connection finished closing; the current one is unaffected")
                }
            }
        }
    }

    private fun bluetoothName(): String {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.name }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        runCatching { Settings.Secure.getString(contentResolver, "bluetooth_name") }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        runCatching { Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        return Build.MODEL
    }

    private fun endService(finalStatus: String) {
        LinkStatus.set(LinkStatus.State.NOT_CONNECTED)
        setStatus(finalStatus)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(detachReceiver) }
        link?.release()
        link = null
        // However the service came to end, nothing is connected any more.
        LinkStatus.set(LinkStatus.State.NOT_CONNECTED)
        super.onDestroy()
    }

    private fun setStatus(text: String) {
        status = text
        BridgeLog.log("status: $text")
        if (link != null) {
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Car connection", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun startInForeground(text: String) {
        val built = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android Auto may need a service type of its own on top of the car
            // link's. It only names one it can honour: Android refuses a type whose permission
            // has not been granted.
            val connected = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            val wanted = connected or AndroidAuto.extraServiceTypes(this)
            // A second attach while a link is up keeps the types that link started with; otherwise
            // fall back to the car link alone, which matters more than anything added to it.
            val running = foregroundTypes?.takeIf { link != null }
            val attempts = (if (running != null) listOf(running) else emptyList()) + listOf(wanted, connected)
            for ((index, types) in attempts.distinct().withIndex()) {
                try {
                    startForeground(NOTIFICATION_ID, built, types)
                    foregroundTypes = types
                    if (types != wanted && running == null) {
                        BridgeLog.log("foreground service running with fewer service types than wanted (0x${types.toString(16)} of 0x${wanted.toString(16)})")
                    }
                    break
                } catch (failure: RuntimeException) {
                    // A refused type is a SecurityException on some Android versions and an
                    // IllegalStateException (ForegroundServiceTypeException) on others.
                    BridgeLog.log("foreground start with service types 0x${types.toString(16)} was refused (${failure.javaClass.simpleName}: ${failure.message})")
                    if (index == attempts.distinct().lastIndex) throw failure
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, built)
        }
    }

    private fun notification(text: String): Notification {
        // Whatever it says, the phone's own screen is where the owner finds what to do about it.
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Disco Auto")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val ACTION_ATTACH = "app.spinbridge.action.ATTACH"
        const val ACTION_STOP = "app.spinbridge.action.STOP"
        private const val CHANNEL_ID = "car-connection"
        private const val NOTIFICATION_ID = 1

        /** One line for the main screen. */
        @Volatile var status: String = "Not connected"
            private set

        fun attach(context: Context, accessory: UsbAccessory) {
            context.startForegroundService(
                Intent(context, BridgeService::class.java)
                    .setAction(ACTION_ATTACH)
                    .putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
            )
        }

        fun describe(accessory: UsbAccessory): String =
            "USB accessory: manufacturer=\"${accessory.manufacturer}\" model=\"${accessory.model}\" " +
                "description=\"${accessory.description}\" version=\"${accessory.version}\" uri=\"${accessory.uri}\""
    }
}
