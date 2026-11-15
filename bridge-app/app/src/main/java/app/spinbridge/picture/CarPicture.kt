// SPDX-License-Identifier: MIT
package app.spinbridge.picture

import android.content.Context
import app.spinbridge.BridgeLog
import app.spinbridge.FrameSink
import app.spinbridge.FrameSource
import app.spinbridge.aa.AndroidAuto
import app.spinbridge.myspin.MySpinServer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * What the car is shown. By default that is the phone's own screen ([ScreenCastSource]); when an
 * [AlternativePicture] (Android Auto) is available, it takes over whenever it becomes available and
 * hands back when it has been unavailable for a while. Nothing has to be set anywhere: the choice
 * follows what is possible, looked at once a second. The owner can overrule it from the menu the
 * car shows when it has no picture ([CarMenu]): MIRROR while the other picture is on its way, and
 * the other picture again afterwards.
 *
 * The session calls [start] and the input callbacks with its own lock held. The picture sources
 * hand frames back to the session from their own threads, never from inside their start() or
 * stop(), so the lock here and the session's are never taken in opposite orders; and after
 * [start] the session's thread never waits for the lock here at all.
 */
class CarPicture(
    private val context: Context,
    private val onStatus: (PictureStatus) -> Unit,
    private val alternative: AlternativePicture? = AndroidAuto,
    private val phoneScreen: (Context, (PictureStatus) -> Unit, PictureHost) -> FrameSource = ::ScreenCastSource,
    private val lookEveryMs: Long = 1_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : FrameSource {
    override val name: String get() = current?.source?.name ?: "nothing yet"

    private class Showing(val source: FrameSource, val alternative: Boolean, val gate: Gate) {
        /** Whether a real picture, not the menu, was on the car at this source's last report. */
        @Volatile var onScreen = false
    }

    /** Lets a source's frames through until the moment it is replaced. */
    private class Gate(private val sink: FrameSink) : FrameSink {
        @Volatile var open = true
        override fun offer(data: ByteArray, compression: Int): Boolean = open && sink.offer(data, compression)
        override fun framesTaken(): Int = sink.framesTaken()
    }

    private val lock = Any()
    private val chooser = Chooser()
    private val choices = LinkedBlockingQueue<CarMenu.Choice>()
    @Volatile private var client: MySpinServer.Client? = null
    private var sink: FrameSink? = null
    private var watcher: Thread? = null
    private var stopped = false
    @Volatile private var current: Showing? = null
    @Volatile private var lastSeen = AlternativePicture.Availability.UNKNOWN

    /** Set once the other picture has failed to start: it is left alone for the rest of this connection. */
    @Volatile private var alternativeBroken = false

    private val host = object : PictureHost {
        override fun chosen(choice: CarMenu.Choice) {
            // Acted on by the watcher's thread: a source may be calling from the session's.
            if (alternative != null) choices.offer(choice)
        }

        override fun alternativeOption(): CarMenu.Option? = alternative?.let {
            CarMenu.Option(
                CarMenu.Choice.ALTERNATIVE, it.menuLabel,
                enabled = !alternativeBroken && lastSeen == AlternativePicture.Availability.AVAILABLE,
            )
        }

        override fun alternativeHint(): String? = alternative?.menuHint
    }

    override fun start(client: MySpinServer.Client, sink: FrameSink) {
        synchronized(lock) {
            if (stopped || this.sink != null) return
            this.client = client
            this.sink = sink
            show(alternative != null && chooser.alternative(false, look(), clock()))
            if (alternative != null) {
                watcher = Thread({ watch() }, "picture-choice").apply { isDaemon = true; start() }
            }
        }
    }

    private fun look(): AlternativePicture.Availability {
        val seen = when {
            alternative == null || alternativeBroken -> AlternativePicture.Availability.UNAVAILABLE
            else -> try { alternative.availability() } catch (_: Exception) { AlternativePicture.Availability.UNKNOWN }
        }
        lastSeen = seen
        return seen
    }

    private fun watch() {
        try {
            while (true) {
                // A choice made on the car's menu is acted on at once; otherwise look once a second.
                val choice = choices.poll(lookEveryMs, TimeUnit.MILLISECONDS)
                val available = look()
                synchronized(lock) {
                    if (stopped) return
                    if (choice != null) chooser.picked(choice)
                    val showing = current
                    val now = showing?.alternative == true
                    val next = chooser.alternative(now, available, clock(), onScreen = now && showing?.onScreen == true)
                    if (next != now) show(next)
                }
            }
        } catch (_: InterruptedException) {
            // stop() asked for this.
        }
    }

    /** Lock held. Replaces whatever is showing. */
    private fun show(alternativeWanted: Boolean) {
        val car = client ?: return
        val out = sink ?: return
        current?.let { old ->
            old.gate.open = false
            runCatching { old.source.stop() }
        }
        current = null
        if (alternativeWanted && alternative != null && begin(car, out, viaAlternative = true)) return
        if (alternativeWanted) {
            // It would not start. Leave it be until the next connection and show the phone's screen.
            alternativeBroken = true
            lastSeen = AlternativePicture.Availability.UNAVAILABLE
        }
        begin(car, out, viaAlternative = false)
    }

    /** Lock held. Creates and starts one kind of picture; false if it would not. */
    private fun begin(car: MySpinServer.Client, out: FrameSink, viaAlternative: Boolean): Boolean {
        val gate = Gate(out)
        var mine: Showing? = null
        val report: (PictureStatus) -> Unit = { status ->
            val me = mine
            if (me != null && current === me) {
                me.onScreen = status.showing
                onStatus(status)
            }
        }
        return try {
            val source = if (viaAlternative) alternative!!.createSource(context, report, host) else phoneScreen(context, report, host)
            val showing = Showing(source, viaAlternative, gate)
            mine = showing
            current = showing
            BridgeLog.log("the car's picture is now: ${source.name}")
            source.start(car, gate)
            true
        } catch (failure: Exception) {
            BridgeLog.log("${if (viaAlternative) "the other picture" else "the phone's screen"} could not start: ${failure.javaClass.simpleName}: ${failure.message}")
            gate.open = false
            current?.let { broken -> runCatching { broken.source.stop() } }
            current = null
            false
        }
    }

    /** No lock: the session's thread must never wait here behind a change of picture. */
    override fun onClientChanged(client: MySpinServer.Client) {
        this.client = client
        current?.source?.onClientChanged(client)
    }

    override fun onTouch(touches: List<MySpinServer.Touch>) { current?.source?.onTouch(touches) }
    override fun onSoftkey(key: Int, pressed: Boolean) { current?.source?.onSoftkey(key, pressed) }
    override fun onClientState(kind: Int, value: Int) { current?.source?.onClientState(kind, value) }
    override fun onCustomKey(action: Int, key: Int) { current?.source?.onCustomKey(action, key) }

    override fun stop() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            watcher?.interrupt()
            watcher = null
            current?.let { last ->
                last.gate.open = false
                runCatching { last.source.stop() }
            }
            current = null
            sink = null
        }
    }

    /**
     * The rule for which picture to show. The alternative takes over as soon as it is available.
     * It gives way only after being plainly unavailable for [fallBackAfterMs] (whatever makes it
     * available may be stopped and started again in a few seconds, and the owner should not be
     * asked to share their screen in between), and never while its picture is on the car, which
     * says more about whether it works than any looking from outside. The owner choosing MIRROR
     * overrules it until they choose otherwise or it goes away.
     */
    internal class Chooser(private val fallBackAfterMs: Long = 15_000L) {
        private var unavailableSince = -1L
        private var mirrorPicked = false

        fun picked(choice: CarMenu.Choice) {
            mirrorPicked = choice == CarMenu.Choice.MIRROR
        }

        fun alternative(
            showingIt: Boolean,
            availability: AlternativePicture.Availability,
            nowMs: Long,
            onScreen: Boolean = false,
        ): Boolean {
            val seen = if (availability == AlternativePicture.Availability.UNAVAILABLE && showingIt && onScreen) {
                AlternativePicture.Availability.UNKNOWN
            } else {
                availability
            }
            return when (seen) {
                AlternativePicture.Availability.AVAILABLE -> {
                    unavailableSince = -1L
                    !mirrorPicked
                }
                AlternativePicture.Availability.UNKNOWN -> {
                    unavailableSince = -1L
                    showingIt && !mirrorPicked
                }
                AlternativePicture.Availability.UNAVAILABLE -> {
                    // Gone: the next time it appears it takes over again, as it does by default.
                    mirrorPicked = false
                    if (!showingIt) {
                        unavailableSince = -1L
                        false
                    } else {
                        if (unavailableSince < 0) unavailableSince = nowMs
                        nowMs - unavailableSince < fallBackAfterMs
                    }
                }
            }
        }
    }
}
