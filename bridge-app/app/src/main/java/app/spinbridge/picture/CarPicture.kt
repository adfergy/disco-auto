// SPDX-License-Identifier: MIT
package app.spinbridge.picture

import android.content.Context
import app.spinbridge.BridgeLog
import app.spinbridge.FrameSink
import app.spinbridge.FrameSource
import app.spinbridge.aa.AndroidAuto
import app.spinbridge.myspin.MySpinServer

/**
 * What the car is shown: Android Auto whenever it is available, and otherwise the Disco Auto logo
 * with a line pointing at the phone ([AlternativePicture.waitingSource]). Nothing has to be set
 * anywhere: the choice follows what is possible, looked at once a second.
 *
 * The session calls [start] and the input callbacks with its own lock held. The picture sources
 * hand frames back to the session from their own threads, never from inside their start() or
 * stop(), so the lock here and the session's are never taken in opposite orders; and after
 * [start] the session's thread never waits for the lock here at all.
 */
class CarPicture(
    private val context: Context,
    private val onStatus: (PictureStatus) -> Unit,
    private val androidAuto: AlternativePicture = AndroidAuto,
    private val lookEveryMs: Long = 1_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : FrameSource {
    override val name: String get() = current?.source?.name ?: "nothing yet"

    private class Showing(val source: FrameSource, val androidAuto: Boolean, val gate: Gate) {
        /** Whether Android Auto's own picture, not a card, was on the car at this source's last report. */
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
    @Volatile private var client: MySpinServer.Client? = null
    private var sink: FrameSink? = null
    private var watcher: Thread? = null
    private var stopped = false
    @Volatile private var current: Showing? = null

    /** Set once Android Auto has failed to start: it is left alone for the rest of this connection. */
    @Volatile private var androidAutoBroken = false

    override fun start(client: MySpinServer.Client, sink: FrameSink) {
        synchronized(lock) {
            if (stopped || this.sink != null) return
            this.client = client
            this.sink = sink
            show(chooser.androidAuto(false, look(), clock()))
            watcher = Thread({ watch() }, "picture-choice").apply { isDaemon = true; start() }
        }
    }

    private fun look(): AlternativePicture.Availability =
        if (androidAutoBroken) {
            AlternativePicture.Availability.UNAVAILABLE
        } else {
            try { androidAuto.availability() } catch (_: Exception) { AlternativePicture.Availability.UNKNOWN }
        }

    private fun watch() {
        try {
            while (true) {
                Thread.sleep(lookEveryMs)
                val available = look()
                synchronized(lock) {
                    if (stopped) return
                    val showing = current
                    val now = showing?.androidAuto == true
                    val next = chooser.androidAuto(now, available, clock(), onScreen = now && showing?.onScreen == true)
                    if (next != now) show(next)
                }
            }
        } catch (_: InterruptedException) {
            // stop() asked for this.
        }
    }

    /** Lock held. Replaces whatever is showing. */
    private fun show(androidAutoWanted: Boolean) {
        val car = client ?: return
        val out = sink ?: return
        current?.let { old ->
            old.gate.open = false
            runCatching { old.source.stop() }
        }
        current = null
        if (androidAutoWanted && begin(car, out, androidAuto = true)) return
        if (androidAutoWanted) {
            // It would not start. Leave it be until the next connection.
            androidAutoBroken = true
        }
        begin(car, out, androidAuto = false)
    }

    /** Lock held. Creates and starts one kind of picture; false if it would not. */
    private fun begin(car: MySpinServer.Client, out: FrameSink, androidAuto: Boolean): Boolean {
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
            val source = if (androidAuto) {
                this.androidAuto.createSource(context, report)
            } else {
                this.androidAuto.waitingSource(context, report)
            }
            val showing = Showing(source, androidAuto, gate)
            mine = showing
            current = showing
            BridgeLog.log("the car's picture is now: ${source.name}")
            source.start(car, gate)
            true
        } catch (failure: Exception) {
            BridgeLog.log("${if (androidAuto) "Android Auto" else "the logo card"} could not start: ${failure.javaClass.simpleName}: ${failure.message}")
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
     * The rule for when to show Android Auto. It takes over as soon as it is available. It gives
     * way to the logo only after being plainly unavailable for [fallBackAfterMs] (its server may
     * be stopped and started again in a few seconds), and never while its picture is on the car,
     * which says more about whether it works than any looking from outside.
     */
    internal class Chooser(private val fallBackAfterMs: Long = 15_000L) {
        private var unavailableSince = -1L

        fun androidAuto(
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
                    true
                }
                AlternativePicture.Availability.UNKNOWN -> {
                    unavailableSince = -1L
                    showingIt
                }
                AlternativePicture.Availability.UNAVAILABLE -> {
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
