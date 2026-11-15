// SPDX-License-Identifier: MIT
package app.spinbridge.aa

import io.motohub.android.aa.AaReceiver
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Whether Android Auto's head unit server is up, found out without touching it.
 *
 * The obvious way to ask - open its port and close it again - must never be used. Android Auto
 * takes every connection for a car arriving, and connections that go away again without the
 * Android Auto handshake can leave the server deaf: its port stays open, later connections queue
 * up unanswered, and nothing works until the driver stops and starts it by hand. (Seen on
 * 15 Nov 2026 with Android Auto 18.1, after two such knocks a second apart.)
 *
 * So this asks the phone instead. A socket that tries to take the same port number for itself is
 * refused exactly when something is listening there, and Android Auto never hears of it.
 */
object HeadUnitServer {
    enum class State {
        /** Something is listening on the port: the server has been started. */
        LISTENING,

        /** Nothing is listening: the driver has to start the server. */
        NOT_RUNNING,

        /** The phone would not say. */
        UNKNOWN,
    }

    fun look(): State = try {
        Socket().use { socket ->
            // With address reuse on, only a listener refuses the bind (not a connection left over
            // on the port), and Android Auto can still take the port in the instant this holds it.
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(LOOPBACK, AaReceiver.HEAD_UNIT_SERVER_PORT))
        }
        State.NOT_RUNNING
    } catch (_: BindException) {
        State.LISTENING
    } catch (_: Exception) {
        State.UNKNOWN
    }

    /** The address the receiver dials. */
    private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
}
