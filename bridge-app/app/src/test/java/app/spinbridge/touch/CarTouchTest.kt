// SPDX-License-Identifier: MIT
package app.spinbridge.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a touch on the car's screen falls on the phone's, for a phone held either way. */
class CarTouchTest {
    private val carWidth = 671
    private val carHeight = 424

    @Test
    fun theMiddleOfTheCarsScreenIsTheMiddleOfAnUprightPhone() {
        val point = CarTouch.toPhone(carWidth, carHeight, 1080, 2400, 335, 212)!!
        assertEquals(540f, point.x, 4f)
        assertEquals(1200f, point.y, 4f)
    }

    @Test
    fun anUprightPhoneFillsTheHeightAndLeavesBlackAtTheSides() {
        // 2400 tall into 424 leaves the phone about 191 car pixels wide, from 240 to 431.
        assertNull(CarTouch.toPhone(carWidth, carHeight, 1080, 2400, 100, 200))
        assertNull(CarTouch.toPhone(carWidth, carHeight, 1080, 2400, 600, 200))
        val top = CarTouch.toPhone(carWidth, carHeight, 1080, 2400, 335, 0)!!
        val bottom = CarTouch.toPhone(carWidth, carHeight, 1080, 2400, 335, 423)!!
        assertEquals(0f, top.y, 6f)
        assertEquals(2400f, bottom.y, 6f)
        val leftEdge = CarTouch.toPhone(carWidth, carHeight, 1080, 2400, 241, 200)!!
        val rightEdge = CarTouch.toPhone(carWidth, carHeight, 1080, 2400, 430, 200)!!
        assertEquals(0f, leftEdge.x, 8f)
        assertEquals(1080f, rightEdge.x, 8f)
    }

    @Test
    fun aPhoneOnItsSideFillsTheWidthAndLeavesBlackAboveAndBelow() {
        // 2400 wide into 671 leaves the phone about 302 car pixels tall, from 61 to 363.
        assertNull(CarTouch.toPhone(carWidth, carHeight, 2400, 1080, 300, 20))
        assertNull(CarTouch.toPhone(carWidth, carHeight, 2400, 1080, 300, 400))
        val left = CarTouch.toPhone(carWidth, carHeight, 2400, 1080, 0, 212)!!
        val right = CarTouch.toPhone(carWidth, carHeight, 2400, 1080, 670, 212)!!
        assertEquals(0f, left.x, 6f)
        assertEquals(2400f, right.x, 6f)
        assertEquals(540f, left.y, 4f)
    }

    @Test
    fun aFingerThatSlidesIntoTheBlackIsHeldAtThePhonesEdge() {
        val held = CarTouch.toPhone(carWidth, carHeight, 1080, 2400, 20, 212, clamp = true)!!
        assertEquals(0f, held.x, 0.01f)
        assertEquals(1200f, held.y, 4f)
        val other = CarTouch.toPhone(carWidth, carHeight, 1080, 2400, 660, 212, clamp = true)!!
        assertEquals(1079f, other.x, 0.01f)
    }

    @Test
    fun everyTouchInsideThePictureLandsOnThePhone() {
        for (phone in listOf(1080 to 2400, 2400 to 1080, 904 to 2316, 1812 to 2176)) {
            for (x in 0 until carWidth step 7) for (y in 0 until carHeight step 7) {
                val point = CarTouch.toPhone(carWidth, carHeight, phone.first, phone.second, x, y) ?: continue
                assert(point.x >= 0f && point.x <= phone.first - 1f) { "x off the phone: $point" }
                assert(point.y >= 0f && point.y <= phone.second - 1f) { "y off the phone: $point" }
            }
        }
    }

    @Test
    fun aScreenTheShapeOfTheCarsIsUsedEdgeToEdge() {
        assertNotNull(CarTouch.toPhone(carWidth, carHeight, 1342, 848, 0, 0))
        assertNotNull(CarTouch.toPhone(carWidth, carHeight, 1342, 848, 670, 423))
    }

    @Test
    fun aTouchJustPassedOnIsRecognisedWhereItLandedAndNotElsewhere() {
        val from = CarTouch.Point(500f, 1000f)
        val to = CarTouch.Point(500f, 1400f)
        CarTouch.passedOn(from, to, nowMs = 10_000, lastsMs = 100)
        assertTrue(CarTouch.isEcho(505f, 1200f, nowMs = 10_050))        // on its path, while it lasts
        assertTrue(CarTouch.isEcho(500f, 1400f, nowMs = 10_300))        // at its end, just after
        assertFalse(CarTouch.isEcho(500f, 1400f, nowMs = 10_400))       // too late to be it
        assertFalse(CarTouch.isEcho(700f, 1200f, nowMs = 10_050))       // somewhere else
        assertFalse(CarTouch.isEcho(500f, 900f, nowMs = 10_050))        // before its start
    }

    @Test
    fun distanceIsToTheNearestPointOfThePiece() {
        val from = CarTouch.Point(0f, 0f)
        val to = CarTouch.Point(100f, 0f)
        assertEquals(30f, CarTouch.distanceToPiece(50f, 30f, from, to), 0.01f)
        assertEquals(50f, CarTouch.distanceToPiece(-30f, 40f, from, to), 0.01f)
        assertEquals(10f, CarTouch.distanceToPiece(110f, 0f, from, to), 0.01f)
        assertEquals(5f, CarTouch.distanceToPiece(3f, 4f, from, from), 0.01f)        // a dot
    }

    @Test
    fun nonsenseSizesGiveNothing() {
        assertNull(CarTouch.toPhone(0, 424, 1080, 2400, 1, 1))
        assertNull(CarTouch.toPhone(671, 424, 0, 2400, 1, 1))
    }
}
