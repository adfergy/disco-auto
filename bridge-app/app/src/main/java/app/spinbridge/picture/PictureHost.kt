// SPDX-License-Identifier: MIT
package app.spinbridge.picture

/**
 * What a picture source can ask of, and learn from, whatever put it on the car. Every call
 * returns at once: a source may make them from the session's own thread.
 */
interface PictureHost {
    /** The owner picked a kind of picture on the car's menu that is not this source's own. */
    fun chosen(choice: CarMenu.Choice)

    /** The other picture (Android Auto) as a button on the menu, as things stand; null if it has none. */
    fun alternativeOption(): CarMenu.Option?

    /** What the menu says underneath while that button cannot be chosen; null if there is nothing to say. */
    fun alternativeHint(): String?
}
