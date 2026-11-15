// SPDX-License-Identifier: MIT
package app.spinbridge

import app.spinbridge.picture.PictureStatus
import java.util.concurrent.CopyOnWriteArraySet

/** Where the connection stands, for the phone's screen and the notification. */
object LinkStatus {
    enum class State {
        /** Not plugged into a car. */
        NOT_CONNECTED,

        /** The USB link is open; the car has not finished saying hello. */
        CONNECTING_TO_CAR,

        /** The car is ready. What it is being shown is in [picture]. */
        CONNECTED,
    }

    @Volatile var state = State.NOT_CONNECTED
        private set

    /** What the car called itself, while connected. */
    @Volatile var car: String? = null
        private set

    /** What the car is being shown, and what (if anything) the owner has to do about it. */
    @Volatile var picture: PictureStatus? = null
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun set(next: State, carName: String? = car) {
        val changed = next != state || carName != car || (next != State.CONNECTED && picture != null)
        state = next
        car = if (next == State.NOT_CONNECTED) null else carName
        if (next != State.CONNECTED) picture = null
        if (changed) listeners.forEach { it() }
    }

    /** The car is connected and this is what it is being shown. */
    fun show(status: PictureStatus) {
        if (state == State.CONNECTED && picture == status) return
        state = State.CONNECTED
        picture = status
        listeners.forEach { it() }
    }

    /** Called on whatever thread changed the state. */
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
}
