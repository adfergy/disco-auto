// SPDX-License-Identifier: MIT
package app.spinbridge.picture

import android.content.Intent

/**
 * What a picture source says about itself, in words for the owner: the phone's status card and
 * the notification show exactly this, so each kind of picture brings its own wording.
 */
data class PictureStatus(
    /** True while Android Auto's picture, not the logo card, is on the car's screen. */
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
    /** A button that opens the first of [intents] this phone can open. */
    data class Action(val label: String, val intents: List<Intent> = emptyList()) {
        // Intent has no equals of its own; two actions with the same words are the same action.
        override fun equals(other: Any?) = other is Action && other.label == label
        override fun hashCode() = label.hashCode()
    }
}
