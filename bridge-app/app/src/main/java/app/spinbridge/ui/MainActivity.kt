// SPDX-License-Identifier: MIT
package app.spinbridge.ui

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.spinbridge.Brand
import app.spinbridge.BridgeLog
import app.spinbridge.BridgeService
import app.spinbridge.BuildInfo
import app.spinbridge.LinkStatus
import app.spinbridge.R
import app.spinbridge.aa.AndroidAuto
import app.spinbridge.aa.HeadUnitServer
import app.spinbridge.picture.PictureStatus
import app.spinbridge.selftest.SelfTestActivity
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The phone's screen: the logo, where things stand, and the three steps to Android Auto on the
 * car, each ticked off as it is done. Whether Android Auto's head unit server is running is
 * looked at once a second while this screen is in front, without touching the server
 * ([HeadUnitServer.look]). The log and the self-test ([DiagnosticsActivity]) are reached by
 * pressing and holding the version line, or over adb. Plain views built in code.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var actionButton: TextView
    private lateinit var steps: LinearLayout
    private lateinit var serverStep: Step
    private lateinit var carStep: Step
    private var resumed = false
    private var onAction: (() -> Unit)? = null

    /** What the last look at Android Auto's head unit server found. */
    @Volatile private var server = HeadUnitServer.State.UNKNOWN
    private var looker: ScheduledExecutorService? = null

    private val statusListener: () -> Unit = { main.post { if (resumed) refresh() } }

    private val permissionReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val accessory: UsbAccessory? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                } else {
                    intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            BridgeLog.log("USB permission ${if (granted) "granted" else "denied"}")
            if (granted && accessory != null) BridgeService.attach(applicationContext, accessory)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeLog.attach(applicationContext)
        setContentView(buildContent())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
        }
        requestMissingPermissions()
        handleExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        LinkStatus.addListener(statusListener)
        refresh()
        // Off the main thread: it is a (local, harmless) socket call.
        looker = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay({
                val seen = HeadUnitServer.look()
                if (seen != server) {
                    server = seen
                    main.post { if (resumed) refresh() }
                }
            }, 0, 1, TimeUnit.SECONDS)
        }
    }

    override fun onPause() {
        resumed = false
        looker?.shutdownNow()
        looker = null
        LinkStatus.removeListener(statusListener)
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(permissionReceiver) }
        super.onDestroy()
    }

    /**
     * For driving the app over adb without tapping at the screen:
     * `am start -n <package>/app.spinbridge.ui.MainActivity --ez selftest true` opens the
     * self-test and `--ez diagnostics true` the help and log screen. In a debuggable build only,
     * `--es demo waiting|connecting|showing|off` puts this screen into the state it has with a
     * car connected, so its wording can be looked at without one.
     */
    private fun handleExtras(intent: Intent?) {
        intent ?: return
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && LinkStatus.car == null) {
            when (intent.getStringExtra(EXTRA_DEMO)) {
                "waiting" -> LinkStatus.show(AndroidAuto.WAITING)
                "connecting" -> LinkStatus.show(AndroidAuto.CONNECTING)
                "showing" -> LinkStatus.show(AndroidAuto.ON_SCREEN)
                "off" -> LinkStatus.set(LinkStatus.State.NOT_CONNECTED)
            }
        }
        if (intent.getBooleanExtra(EXTRA_SELF_TEST, false)) {
            intent.removeExtra(EXTRA_SELF_TEST)
            startActivity(Intent(this, SelfTestActivity::class.java))
        }
        if (intent.getBooleanExtra(EXTRA_DIAGNOSTICS, false)) {
            intent.removeExtra(EXTRA_DIAGNOSTICS)
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
    }

    // ---- what is shown -----------------------------------------------------------------------

    private fun refresh() {
        val state = LinkStatus.state
        val picture = LinkStatus.picture
        val colour: Int
        val headline: String
        val detail: String
        var action: Pair<String, () -> Unit>? = null
        when {
            state == LinkStatus.State.CONNECTED && picture != null -> {
                colour = if (picture.showing) GOOD else AMBER
                headline = picture.headline
                detail = picture.detail
                action = picture.action?.let { it.label to { open(it) } }
            }
            state == LinkStatus.State.NOT_CONNECTED && carIsOffering() -> {
                // Plugged in, but Android's "open with" question was dismissed or never came.
                colour = AMBER
                headline = "Plugged in, not connected yet"
                detail = "Tap Connect, then allow Disco Auto to use the car's USB connection."
                action = "Connect" to ::connect
            }
            state == LinkStatus.State.NOT_CONNECTED -> {
                colour = GREY
                headline = "Not connected to a car"
                detail = "Follow the steps below."
            }
            else -> {
                colour = AMBER
                headline = "Connecting to the car…"
                detail = ""
            }
        }
        (statusDot.background as GradientDrawable).setColor(colour)
        statusText.text = headline
        detailText.text = detail
        detailText.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        onAction = action?.second
        actionButton.text = action?.first ?: ""
        actionButton.visibility = if (action == null) View.GONE else View.VISIBLE

        // With Android Auto on the car there is nothing left to do.
        steps.visibility = if (picture?.showing == true) View.GONE else View.VISIBLE
        val serverRunning = server == HeadUnitServer.State.LISTENING
        serverStep.done(serverRunning)
        serverStep.body.text = if (serverRunning) SERVER_RUNNING else SERVER_STEPS
        serverStep.extra.visibility = if (serverRunning) View.GONE else View.VISIBLE
        carStep.done(state != LinkStatus.State.NOT_CONNECTED)
    }

    private fun carIsOffering(): Boolean =
        runCatching { getSystemService(UsbManager::class.java).accessoryList?.isNotEmpty() == true }.getOrDefault(false)

    private fun open(action: PictureStatus.Action) {
        for (intent in action.intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, "That could not be opened on this phone.", Toast.LENGTH_LONG).show()
    }

    /** For when the phone is already plugged in and Android's "open with" question was dismissed. */
    private fun connect() {
        val usb = getSystemService(UsbManager::class.java)
        val accessory = usb.accessoryList?.firstOrNull()
        if (accessory == null) {
            Toast.makeText(this, "The car is not offering a connection on this cable", Toast.LENGTH_LONG).show()
            return
        }
        BridgeLog.log("Connect: found " + BridgeService.describe(accessory))
        if (usb.hasPermission(accessory)) {
            BridgeService.attach(applicationContext, accessory)
        } else {
            val reply = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            usb.requestPermission(accessory, reply)
        }
    }

    /** One numbered step, whose number turns into a tick once it is done. */
    private inner class Step(number: Int, title: String, body: String) {
        private val badge = text(number.toString(), 15f, Brand.GROUND, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Brand.INK) }
        }
        private val label = number.toString()
        val body: TextView = text(body, 15f, BODY)

        /** Anything under the body that only matters while the step is not done. */
        val extra = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }

        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(badge, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(12) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 16f, HEADING, bold = true))
                addView(this@Step.body, spaced(top = 4))
                addView(extra, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        fun done(yes: Boolean) {
            badge.text = if (yes) "✓" else label
            (badge.background as GradientDrawable).setColor(if (yes) GOOD else Brand.INK)
        }
    }

    private fun buildContent(): View {
        val header = FrameLayout(this).apply {
            setBackgroundColor(Brand.GROUND)
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.logo)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "Disco Auto"
                },
                FrameLayout.LayoutParams(MATCH_PARENT, dp(132), Gravity.CENTER).apply {
                    leftMargin = dp(28)
                    rightMargin = dp(28)
                }
            )
        }

        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREY) }
        }
        statusText = text("", 17f, HEADING, bold = true)
        detailText = text("", 15f, BODY)
        actionButton = pill("") { onAction?.invoke() }
        val status = card().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(statusDot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(10) })
                addView(statusText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
            addView(detailText, spaced(top = 8))
            addView(actionButton, spaced(top = 14))
        }

        serverStep = Step(1, "Start Android Auto's head unit server", SERVER_STEPS).apply {
            extra.addView(pill(AndroidAuto.OPEN_SETTINGS.label) { open(AndroidAuto.OPEN_SETTINGS) }, spaced(top = 12))
            extra.addView(text(NO_THREE_DOTS, 14f, DIM), spaced(top = 10))
        }
        carStep = Step(
            2, "Plug the phone into the car",
            "Use the car's InControl Apps USB socket. If the car's screen does not change, tap InControl Apps on it. " +
                "Android Auto then appears by itself."
        )
        val soundStep = Step(
            3, "Sound",
            "Pair the phone with the car over Bluetooth and choose Bluetooth as the car's audio source. " +
                "The steering wheel's track buttons then work too."
        )
        steps = card().apply {
            addView(text("Getting Android Auto on the car", 17f, HEADING, bold = true))
            addView(serverStep.view, spaced(top = 16))
            addView(carStep.view, spaced(top = 18))
            addView(soundStep.view, spaced(top = 18))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(status, cardParams(top = 14))
            addView(steps, cardParams(top = 12))
            addView(
                text("Disco Auto ${BuildInfo.VERSION}", 12f, DIM).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(16), 0, dp(18))
                    // Out of the way on purpose: the log and the self-test are for chasing problems.
                    setOnLongClickListener {
                        startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java))
                        true
                    }
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            )
        }

        return ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            isFillViewport = true
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // The green runs up under the status bar; the list ends clear of the navigation bar.
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                header.setPadding(bars.left, bars.top + dp(10), bars.right, dp(10))
                content.setPadding(0, 0, 0, bars.bottom)
                insets
            }
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = dp(18).toFloat()
        }
    }

    private fun cardParams(top: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        leftMargin = dp(14)
        rightMargin = dp(14)
        topMargin = dp(top)
    }

    private fun spaced(top: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun text(value: String, sizeSp: Float, colour: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(colour)
        setLineSpacing(0f, 1.15f)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun pill(label: String, action: () -> Unit) = text(label, 16f, Brand.GROUND, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(13), dp(18), dp(13))
        background = GradientDrawable().apply {
            setColor(Brand.INK)
            cornerRadius = dp(26).toFloat()
        }
        isClickable = true
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun requestMissingPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            // To tell the car this phone's Bluetooth name, which it asks for.
            add(Manifest.permission.BLUETOOTH_CONNECT)
            addAll(AndroidAuto.permissions)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    companion object {
        const val EXTRA_SELF_TEST = "selftest"
        const val EXTRA_DIAGNOSTICS = "diagnostics"
        const val EXTRA_DEMO = "demo"
        private const val ACTION_USB_PERMISSION = "app.spinbridge.action.USB_PERMISSION_MAIN"

        private const val SERVER_STEPS =
            "Tap the button below. In Android Auto's settings, tap the three dots at the top right and choose " +
                "Start head unit server. Do this again whenever the phone has been restarted."
        private const val SERVER_RUNNING = "Running. It stays running until the phone restarts."
        private const val NO_THREE_DOTS =
            "No three dots? Scroll to the bottom of Android Auto's settings, tap Version ten times and tap OK. " +
                "You only need to do this once."

        val BACKGROUND = Color.rgb(0x0C, 0x12, 0x0E)
        val CARD = Color.rgb(0x17, 0x20, 0x1A)
        val HEADING = Brand.INK
        val BODY = Color.rgb(0xD3, 0xDA, 0xD2)
        val DIM = Color.rgb(0x93, 0xA0, 0x95)
        val GOOD = Color.rgb(0x8F, 0xD1, 0x4F)
        val AMBER = Color.rgb(0xFF, 0xB3, 0x4D)
        val GREY = Color.rgb(0x7C, 0x86, 0x7E)
    }
}
