// SPDX-License-Identifier: MIT
package app.spinbridge.picture

import android.media.projection.MediaProjection
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Where the three parties to showing the phone's screen meet: the picture source that wants the
 * screen, the activity that puts Android's question to the owner, and the service Android
 * requires to hold the answer. Android asks afresh every time, so this holds nothing for long.
 */
object ScreenCast {
    interface Taker {
        /** The owner said yes. Called on the main thread; the taker owns [projection] from here. */
        fun onProjection(projection: MediaProjection)
    }

    /** Why the question is to be put. */
    enum class Turn {
        /** A picture source has just started waiting. */
        UNPROMPTED,

        /** The owner asked for it, on the car's screen. */
        ASKED_FOR,
    }

    /** What became of the owner asking, on the car, for the question to be put. */
    enum class Asked {
        /** One of the app's screens is in front on the phone and will put it now. */
        WILL_ASK,

        /** Android's question is already on the phone's screen. */
        ALREADY_ASKING,

        /** None of the app's screens is in front, and nothing else is allowed to put it. */
        NO_SCREEN,
    }

    private val lock = Any()
    private var taker: Taker? = null
    private var due: Turn? = null
    private var screensInFront = 0
    private var dialogsUp = 0
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** True while a picture source is waiting for the owner to allow the screen to be shown. */
    val wanted: Boolean get() = synchronized(lock) { taker != null }

    /**
     * A source wants the screen. With [askStraightAway] the question is put as soon as one of the
     * app's screens is in front; without it (the owner has just stopped a share) only when they
     * ask for it.
     */
    fun want(taker: Taker, askStraightAway: Boolean) {
        synchronized(lock) {
            this.taker = taker
            due = if (askStraightAway) Turn.UNPROMPTED else null
        }
        listeners.forEach { it() }
    }

    fun withdraw(taker: Taker) {
        synchronized(lock) {
            if (this.taker !== taker) return
            this.taker = null
            due = null
        }
        listeners.forEach { it() }
    }

    /** The owner touched the car's screen to have the question put now. */
    fun askAgain(taker: Taker): Asked {
        val outcome = synchronized(lock) {
            if (this.taker !== taker) return Asked.NO_SCREEN
            when {
                dialogsUp > 0 -> Asked.ALREADY_ASKING
                else -> {
                    due = Turn.ASKED_FOR
                    if (screensInFront > 0) Asked.WILL_ASK else Asked.NO_SCREEN
                }
            }
        }
        listeners.forEach { it() }
        return outcome
    }

    /**
     * The question that is due, once: whoever is handed it must put it. Null when none is due.
     */
    fun takeTurnToAsk(): Turn? = synchronized(lock) {
        if (taker == null) return null
        due.also { due = null }
    }

    /** One of the app's screens that can put the question came to the front, or left it. */
    fun screenInFront(inFront: Boolean) = synchronized(lock) {
        screensInFront = (screensInFront + if (inFront) 1 else -1).coerceAtLeast(0)
    }

    /** Android's question went up on the phone's screen, or came down. */
    fun dialogUp(up: Boolean) = synchronized(lock) {
        dialogsUp = (dialogsUp + if (up) 1 else -1).coerceAtLeast(0)
    }

    /** From the service holding the share. False when nobody is waiting for it any more. */
    fun deliver(projection: MediaProjection): Boolean {
        val waiting = synchronized(lock) {
            due = null
            taker.also { taker = null }
        } ?: return false
        listeners.forEach { it() }
        waiting.onProjection(projection)
        return true
    }

    /** Called on whatever thread changed things. */
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
}
