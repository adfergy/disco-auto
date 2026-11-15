// SPDX-License-Identifier: MIT
package app.spinbridge.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import app.spinbridge.BuildInfo
import app.spinbridge.LinkStatus
import app.spinbridge.R
import app.spinbridge.aa.AndroidAuto
import app.spinbridge.picture.CastPrompt
import app.spinbridge.picture.CastService
import app.spinbridge.picture.PictureStatus
import app.spinbridge.picture.ScreenCastSource
import app.spinbridge.selftest.SelfTestActivity
import app.spinbridge.touch.CarTouch

/**
 * The phone's screen: the logo and where things stand. Until a car is connected that is all
 * there is to say. Once one is, it says what the car is being shown and what, if anything, the
 * owner has to do, in the picture source's own words. The log and the self-test are one tap away
 * in [DiagnosticsActivity]. Plain views built in code.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val castPrompt = CastPrompt(this)
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var actionButton: TextView
    private lateinit var connectedCards: LinearLayout
    private var resumed = false
    private var action: PictureStatus.Action? = null

    /** True while Android's own permission questions are up: its screen-sharing one waits its turn. */
    private var permissionsAsked = false

    private val statusListener: () -> Unit = { main.post { if (resumed) refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeLog.attach(applicationContext)
        setContentView(buildContent())
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
        if (!permissionsAsked) castPrompt.onResume()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionsAsked = false
        if (resumed) castPrompt.onResume()
    }

    override fun onDestroy() {
        castPrompt.onDestroy()
        super.onDestroy()
    }

    override fun onPause() {
        resumed = false
        castPrompt.onPause()
        LinkStatus.removeListener(statusListener)
        super.onPause()
    }

    @Deprecated("The platform's own result callback; this app has no AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!castPrompt.onActivityResult(requestCode, resultCode, data)) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    /**
     * For driving the app over adb without tapping at the screen:
     * `am start -n <package>/app.spinbridge.ui.MainActivity --ez selftest true` opens the
     * self-test and `--ez diagnostics true` the help and log screen. In a debuggable build only,
     * `--es demo waiting` and `--es demo showing` put this screen into the state it has with a
     * car connected, so its wording can be looked at without one.
     */
    private fun handleExtras(intent: Intent?) {
        intent ?: return
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && LinkStatus.car == null) {
            when (intent.getStringExtra(EXTRA_DEMO)) {
                "waiting" -> LinkStatus.show(ScreenCastSource.WAITING)
                "showing" -> LinkStatus.show(ScreenCastSource.SHOWING)
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
        var offered: PictureStatus.Action? = null
        when {
            state == LinkStatus.State.CONNECTED && picture != null -> {
                colour = if (picture.showing) GOOD else AMBER
                headline = picture.headline
                detail = picture.detail
                offered = picture.action
            }
            state == LinkStatus.State.NOT_CONNECTED -> {
                colour = GREY
                headline = "Not connected to a car"
                detail = "Plug the phone into the car's InControl Apps USB socket."
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
        action = offered
        actionButton.text = offered?.label ?: ""
        actionButton.visibility = if (offered == null) View.GONE else View.VISIBLE

        // What to do in the car only means something once there is a car.
        val connected = state == LinkStatus.State.CONNECTED && picture != null
        val phoneScreenIsThePicture = offered?.kind == PictureStatus.Action.Kind.ALLOW_CAST ||
            offered?.kind == PictureStatus.Action.Kind.STOP_CAST
        // Rebuilt each time: what Android Auto has to say changes with its state.
        connectedCards.removeAllViews()
        if (connected) buildConnectedCards(phoneScreenIsThePicture)
        connectedCards.visibility = if (connected) View.VISIBLE else View.GONE
    }

    private fun buildConnectedCards(phoneScreenIsThePicture: Boolean) {
        if (phoneScreenIsThePicture) connectedCards.addView(touchCard(), cardParams(top = 12))
        // Android Auto, offered while the phone's own screen is the one in use.
        val invitation = if (phoneScreenIsThePicture) AndroidAuto.invitation() else null
        if (invitation != null) {
            connectedCards.addView(
                titled(invitation.title, invitation.body).apply {
                    invitation.action?.let { offered ->
                        addView(pill(offered.label) { perform(offered) }, spaced(top = 12))
                    }
                },
                cardParams(top = 12)
            )
        }
        connectedCards.addView(
            titled(
                "If the car's screen shows nothing",
                "Tap InControl Apps on the car's screen. To have it open by itself every time you plug in, " +
                    "switch on Auto Load InControl Apps in the car's settings."
            ),
            cardParams(top = 12)
        )
        connectedCards.addView(
            titled(
                "Sound",
                "Sound plays through the car over Bluetooth: pair the phone with the car and choose Bluetooth as " +
                    "the car's audio source. The steering wheel's track buttons work the same way."
            ),
            cardParams(top = 12)
        )
    }

    /**
     * Whether touches on the car's screen work the phone while it is mirrored, and how to make
     * them. Android allows that only through an accessibility service the owner switches on, and
     * expects to be told plainly what it is for before they do.
     */
    private fun touchCard(): LinearLayout =
        if (CarTouch.available) {
            titled(
                "Touch from the car's screen: on",
                "While your phone's screen is on the car, touching the car's screen works the phone in the same " +
                    "place. One finger at a time. Use it only when it is safe to."
            )
        } else {
            titled(
                "Touch from the car's screen: off",
                "To work the phone from the car's screen while mirroring, switch on Disco Auto touch in Android's " +
                    "Accessibility settings (under Installed apps or Downloaded apps).\n\n" +
                    "Disco Auto uses that permission only to pass your touches on the car's screen to the phone. " +
                    "It reads nothing from your screen.\n\n" +
                    "If Android says the setting is restricted: open Disco Auto's App info, tap the three dots, " +
                    "choose Allow restricted settings, and try again."
            ).apply {
                addView(pill("Open Accessibility settings") {
                    try {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "That could not be opened on this phone.", Toast.LENGTH_LONG).show()
                    }
                }, spaced(top = 12))
            }
        }

    private fun perform(offered: PictureStatus.Action) {
        when (offered.kind) {
            PictureStatus.Action.Kind.ALLOW_CAST -> castPrompt.ask()
            PictureStatus.Action.Kind.STOP_CAST -> CastService.stop(this)
            PictureStatus.Action.Kind.OPEN -> {
                for (intent in offered.intents) {
                    try {
                        startActivity(intent)
                        return
                    } catch (_: Exception) {
                    }
                }
                Toast.makeText(this, "That could not be opened on this phone.", Toast.LENGTH_LONG).show()
            }
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
        actionButton = pill("") { action?.let(::perform) }
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

        connectedCards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(status, cardParams(top = 14))
            addView(connectedCards, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(
                text("Help and log", 15f, HEADING, bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(14), dp(16), dp(6))
                    setOnClickListener { startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java)) }
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
            )
            addView(
                text("Disco Auto ${BuildInfo.VERSION}", 12f, DIM).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(18) }
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

    private fun titled(title: String, body: String): LinearLayout = card().apply {
        addView(text(title, 17f, HEADING, bold = true))
        addView(text(body, 15f, BODY), spaced(top = 8))
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
        if (wanted.isNotEmpty()) {
            permissionsAsked = true
            requestPermissions(wanted.toTypedArray(), 1)
        }
    }

    companion object {
        const val EXTRA_SELF_TEST = "selftest"
        const val EXTRA_DIAGNOSTICS = "diagnostics"
        const val EXTRA_DEMO = "demo"

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
