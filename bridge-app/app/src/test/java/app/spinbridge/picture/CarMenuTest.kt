// SPDX-License-Identifier: MIT
package app.spinbridge.picture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the car's menu puts its buttons, and what a touch on it means. */
class CarMenuTest {
    private val mirror = CarMenu.Option(CarMenu.Choice.MIRROR, "MIRROR", enabled = true)
    private val greyed = CarMenu.Option(CarMenu.Choice.ALTERNATIVE, "OTHER", enabled = false)
    private val offered = greyed.copy(enabled = true)
    private val onItsWay = offered.copy(current = true)

    private fun centre(box: CarMenu.Box) = ((box.left + box.right) / 2).toInt() to ((box.top + box.bottom) / 2).toInt()

    @Test
    fun twoButtonsSitSideBySideInsideTheDiscoverySportsScreen() {
        val boxes = CarMenu.buttons(671, 424, 2)
        assertEquals(2, boxes.size)
        assertTrue(boxes[0].left >= 0 && boxes[1].right <= 671)
        assertTrue(boxes[0].right < boxes[1].left)
        assertEquals(boxes[0].top, boxes[1].top)
        assertTrue(boxes[0].bottom < 424 * 0.75f)                    // room left under them for the hint
        // Centred as a pair.
        assertEquals(boxes[0].left, 671 - boxes[1].right, 0.01f)
    }

    @Test
    fun oneButtonSitsInTheMiddle() {
        val box = CarMenu.buttons(671, 424, 1).single()
        assertEquals(671 / 2f, (box.left + box.right) / 2, 0.01f)
    }

    @Test
    fun aTouchOnAButtonThatCanBeChosenIsThatChoice() {
        val content = CarMenu.Content(listOf(mirror, offered), "hint")
        val boxes = CarMenu.buttons(671, 424, 2)
        val (x0, y0) = centre(boxes[0])
        val (x1, y1) = centre(boxes[1])
        assertEquals(CarMenu.Choice.MIRROR, CarMenu.choiceAt(671, 424, content, x0, y0))
        assertEquals(CarMenu.Choice.ALTERNATIVE, CarMenu.choiceAt(671, 424, content, x1, y1))
    }

    @Test
    fun aGreyedButtonAndOneAlreadyOnItsWayCannotBeChosen() {
        val boxes = CarMenu.buttons(671, 424, 2)
        val (x1, y1) = centre(boxes[1])
        assertEquals(null, CarMenu.choiceAt(671, 424, CarMenu.Content(listOf(mirror, greyed), "hint"), x1, y1))
        assertEquals(null, CarMenu.choiceAt(671, 424, CarMenu.Content(listOf(mirror, onItsWay), "hint"), x1, y1))
    }

    @Test
    fun aTouchOffTheButtonsIsNothing() {
        val content = CarMenu.Content(listOf(mirror, offered), "hint")
        assertEquals(null, CarMenu.choiceAt(671, 424, content, 335, 40))      // on the logo
        assertEquals(null, CarMenu.choiceAt(671, 424, content, 335, 400))     // on the hint
        val boxes = CarMenu.buttons(671, 424, 2)
        val between = ((boxes[0].right + boxes[1].left) / 2).toInt()
        assertEquals(null, CarMenu.choiceAt(671, 424, content, between, centre(boxes[0]).second))
    }

    @Test
    fun theButtonsScaleWithTheScreen() {
        val small = CarMenu.buttons(671, 424, 2)
        val large = CarMenu.buttons(1342, 848, 2)
        assertEquals(small[0].left * 2, large[0].left, 0.01f)
        assertEquals(small[1].bottom * 2, large[1].bottom, 0.01f)
    }
}
