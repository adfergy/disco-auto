// SPDX-License-Identifier: MIT
package app.spinbridge.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.spinbridge.BridgeLog
import app.spinbridge.BridgeService
import app.spinbridge.aa.AndroidAuto
import app.spinbridge.selftest.SelfTestActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Behind "Help and log" on the main screen: what to try when nothing appears on the car, the
 * session log (what the car sent, what was answered, what went wrong) with a way to send it, the
 * self-test against a simulated head unit, and connecting by hand.
 */
class DiagnosticsActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private var refreshQueued = false

    private val logListener: () -> Unit = {
        if (!refreshQueued) {
            refreshQueued = true
            main.postDelayed(::refresh, 250)
        }
    }

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
    }

    override fun onResume() {
        super.onResume()
        BridgeLog.addListener(logListener)
        refresh()
    }

    override fun onPause() {
        BridgeLog.removeListener(logListener)
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(permissionReceiver) }
        super.onDestroy()
    }

    private fun buildContent(): LinearLayout {
        val pad = dp(14)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MainActivity.BACKGROUND)
            setOnApplyWindowInsetsListener { target, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                target.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
                insets
            }
        }
        root.addView(label("Help and log", 22f, MainActivity.HEADING, bold = true))
        val help = listOf(
            "Nothing on the car's screen at all: tap InControl Apps on the car, try another cable (it must carry " +
                "data), then Connect below.",
            "The car shows the Disco Auto logo: the phone is waiting for you. Its first screen says what for.",
            "No sound: pick Bluetooth as the car's audio source.",
        ) + AndroidAuto.troubleshooting
        root.addView(
            label("If it does not work:\n" + help.joinToString("\n") { "• $it" }, 14f, MainActivity.BODY)
                .apply { setPadding(0, dp(8), 0, dp(10)) }
        )
        statusView = label("", 14f, MainActivity.HEADING, bold = true).apply { setPadding(0, 0, 0, dp(8)) }
        root.addView(statusView)

        val rowParams = { LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) } }
        root.addView(row(
            button("Share log") { saveLog(share = true) },
            button("Save log") { saveLog(share = false) },
            button("Clear") { BridgeLog.clear() },
        ), rowParams())
        root.addView(row(
            button("Connect") { connect() },
            button("Stop") { startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_STOP)) },
            button("Self-test") { startActivity(Intent(this, SelfTestActivity::class.java)) },
        ), rowParams())

        root.addView(
            label("Built with Kotlin and kotlinx.coroutines, Apache License 2.0: apache.org/licenses/LICENSE-2.0", 11.5f, MainActivity.DIM)
                .apply { setPadding(0, dp(10), 0, 0) }
        )

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(MainActivity.BODY)
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        return root
    }

    private fun row(vararg buttons: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        buttons.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (index > 0) leftMargin = dp(8)
            })
        }
    }

    private fun button(label: String, action: () -> Unit): TextView =
        label(label, 15f, MainActivity.HEADING, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = GradientDrawable().apply {
                setColor(MainActivity.CARD)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), MainActivity.DIM)
            }
            isClickable = true
            setOnClickListener { action() }
        }

    private fun label(value: String, sizeSp: Float, colour: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(colour)
        setLineSpacing(0f, 1.15f)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun refresh() {
        refreshQueued = false
        statusView.text = BridgeService.status
        val atBottom = logScroll.scrollY + logScroll.height >= logView.height - dp(48)
        logView.text = BridgeLog.snapshot(SHOWN_LINES).joinToString("\n")
        if (atBottom) logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** For when the phone is already plugged in and Android's "open with" prompt was dismissed. */
    private fun connect() {
        val usb = getSystemService(UsbManager::class.java)
        val accessory = usb.accessoryList?.firstOrNull()
        if (accessory == null) {
            BridgeLog.log("Connect: no USB accessory is attached (is the phone in the InControl Apps socket?)")
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

    /** Writes the whole log to Download/DiscoAuto, where any file manager or `adb pull` finds it. */
    private fun saveLog(share: Boolean) {
        val text = BridgeLog.snapshot().joinToString("\n")
        val name = "discoauto-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val saved: Uri? = try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DiscoAuto")
            }
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.also { uri ->
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }
        } catch (failure: Exception) {
            BridgeLog.log("saving the log failed: ${failure.message}")
            null
        }
        if (saved == null) {
            Toast.makeText(this, "Could not save the log", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Saved Download/DiscoAuto/$name", Toast.LENGTH_LONG).show()
        if (share) {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, saved)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Share the Disco Auto log"
                )
            )
        }
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "app.spinbridge.action.USB_PERMISSION"
        const val SHOWN_LINES = 400
    }
}
