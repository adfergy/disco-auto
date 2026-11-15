// SPDX-License-Identifier: MIT
package app.spinbridge.picture

import android.content.Intent

/**
 * What a picture source says about itself, in words for the owner: the phone's status card and
 * the notification show exactly this, so each kind of picture brings its own wording.
 */
data class PictureStatus(
    /** True while a real picture, not the logo card, is on the car's screen. */
    val showing: Boolean,
    /** A few words: the status card's heading. */
    val headline: String,
    /** A sentence or two under it: what is happening, or what to do. */
    val detail: String,
    /** One line for the notification. */
    val notification: String,
    /** Something to tap on the phone that moves things along, if there is such a thing. */
    val action: Action? = null,
) {
    data class Action(val label: String, val kind: Kind, val intents: List<Intent> = emptyList()) {
        enum class Kind {
            /** Put Android's "share your screen?" question. */
            ALLOW_CAST,

            /** Stop showing the phone's screen. */
            STOP_CAST,

            /** Open the first of [intents] that this phone can open. */
            OPEN,
        }

        // Intent has no equals of its own; two actions with the same words are the same action.
        override fun equals(other: Any?) = other is Action && other.label == label && other.kind == kind
        override fun hashCode() = label.hashCode() * 31 + kind.hashCode()
    }
}
