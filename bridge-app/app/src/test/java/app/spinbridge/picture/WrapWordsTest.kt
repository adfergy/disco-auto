// SPDX-License-Identifier: MIT
package app.spinbridge.picture

import app.spinbridge.aa.AndroidAuto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The car's information card must never run a line off the edge of the screen. */
class WrapWordsTest {
    /** Ten units a character: close enough to a real font for counting. */
    private val measure: (String) -> Float = { it.length * 10f }

    @Test
    fun aLineThatFitsIsLeftAlone() {
        assertEquals(listOf("Waiting for Android Auto"), wrapWords("Waiting for Android Auto", 240f, measure))
    }

    @Test
    fun aLongLineBreaksAtSpacesAndLosesNothing() {
        val text = "On the phone, open Disco Auto and tap Open Android Auto settings."
        val lines = wrapWords(text, 300f, measure)
        assertTrue(lines.size > 1)
        assertTrue(lines.all { measure(it) <= 300f })
        assertEquals(text, lines.joinToString(" "))
    }

    @Test
    fun aWordWiderThanTheRoomKeepsALineToItself() {
        assertEquals(listOf("a", "extraordinarily", "b"), wrapWords("a extraordinarily b", 50f, measure))
    }

    @Test
    fun nothingInNothingOut() {
        assertEquals(emptyList<String>(), wrapWords("", 100f, measure))
        assertEquals(emptyList<String>(), wrapWords("   ", 100f, measure))
    }

    @Test
    fun theCarsMessageWrapsOntoAScreenTooNarrowForIt() {
        val lines = wrapWords(AndroidAuto.CAR_FOLLOW_PHONE, 300f, measure)
        assertTrue(lines.size > 1)
        assertTrue(lines.all { measure(it) <= 300f })
        assertEquals(AndroidAuto.CAR_FOLLOW_PHONE, lines.joinToString(" "))
    }
}
