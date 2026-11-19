// SPDX-License-Identifier: MIT
package app.spinbridge.picture

import android.content.Context
import app.spinbridge.BridgeLog
import app.spinbridge.FrameSink
import app.spinbridge.FrameSource
import app.spinbridge.myspin.MySpinServer

/**
 * The car's picture while there is no Android Auto to show: the logo and [message], sent once a
 * second (so a car that only starts asking for pictures late still gets it). The phone is told
 * [status], which says what to do.
 *
 * Frames go to the session from this source's own thread, never from inside [start] or [stop].
 */
class WaitingSource(
    context: Context,
    private val onStatus: (PictureStatus) -> Unit,
    private val status: PictureStatus,
    private val message: String,
) : FrameSource {
    override val name = "the Disco Auto logo"

    private val appContext = context.applicationContext
    private val lock = Object()
    @Volatile private var client: MySpinServer.Client? = null
    private var thread: Thread? = null
    private var stopped = false

    override fun start(client: MySpinServer.Client, sink: FrameSink) {
        synchronized(lock) {
            if (stopped || thread != null) return
            this.client = client
            thread = Thread({ run(sink) }, "waiting-card").apply { isDaemon = true; start() }
        }
        onStatus(status)
    }

    private fun run(sink: FrameSink) {
        val card = CarCard(appContext)
        while (true) {
            synchronized(lock) { if (stopped) return }
            val car = client
            if (car != null) {
                try {
                    val (data, compression) = card.frame(car, message)
                    synchronized(lock) { if (stopped) return }
                    if (!sink.offer(data, compression)) return
                } catch (failure: Exception) {
                    BridgeLog.log("the logo card could not be drawn (${failure.javaClass.simpleName}: ${failure.message})")
                }
            }
            synchronized(lock) {
                if (stopped) return
                lock.wait(EVERY_MS)
            }
        }
    }

    /** The car threw its picture away to take a new form: send the card again straight away. */
    override fun onClientChanged(client: MySpinServer.Client) {
        this.client = client
        synchronized(lock) { lock.notifyAll() }
    }

    override fun stop() {
        synchronized(lock) {
            stopped = true
            lock.notifyAll()
        }
    }

    private companion object {
        const val EVERY_MS = 1_000L
    }
}
