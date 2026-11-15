// SPDX-License-Identifier: MIT
package app.spinbridge

import app.spinbridge.myspin.MySpinServer

/** Where encoded frames go: the mySPIN session's one-slot cache. False once the session is over. */
fun interface FrameSink {
    fun offer(data: ByteArray, compression: Int): Boolean

    /** How many frames the car has actually taken so far, or -1 when this sink cannot tell. */
    fun framesTaken(): Int = -1
}

/**
 * Something that paints the car's screen and reacts to its input. Every callback
 * arrives on a session thread, with the session's lock held, and must return quickly.
 */
interface FrameSource {
    val name: String

    /** The handshake is done; start producing frames at [client]'s size into [sink]. */
    fun start(client: MySpinServer.Client, sink: FrameSink)

    /** The car changed pixel format, compression or size mid-session. */
    fun onClientChanged(client: MySpinServer.Client) {}
    fun onTouch(touches: List<MySpinServer.Touch>) {}
    fun onSoftkey(key: Int, pressed: Boolean) {}
    fun onClientState(kind: Int, value: Int) {}
    fun onCustomKey(action: Int, key: Int) {}
    fun stop()
}
