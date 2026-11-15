// SPDX-License-Identifier: MIT
package app.spinbridge.picture

import android.content.Context
import android.content.ContextWrapper
import app.spinbridge.FrameSink
import app.spinbridge.FrameSource
import app.spinbridge.myspin.MySpinServer
import app.spinbridge.picture.AlternativePicture.Availability.AVAILABLE
import app.spinbridge.picture.AlternativePicture.Availability.UNAVAILABLE
import app.spinbridge.picture.AlternativePicture.Availability.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Which picture the car is shown, and that a replaced picture can no longer reach the car. */
class CarPictureTest {

    // ---- the rule ---------------------------------------------------------------------------

    @Test
    fun thePhonesScreenIsThePictureUntilTheAlternativeIsAvailable() {
        val chooser = CarPicture.Chooser()
        assertFalse(chooser.alternative(showingIt = false, availability = UNAVAILABLE, nowMs = 0))
        assertFalse(chooser.alternative(showingIt = false, availability = UNKNOWN, nowMs = 1_000))
        assertTrue(chooser.alternative(showingIt = false, availability = AVAILABLE, nowMs = 2_000))
    }

    @Test
    fun theAlternativeSurvivesAShortAbsenceAndGivesWayAfterALongOne() {
        val chooser = CarPicture.Chooser(fallBackAfterMs = 15_000)
        assertTrue(chooser.alternative(true, AVAILABLE, 0))
        assertTrue(chooser.alternative(true, UNAVAILABLE, 1_000))      // just gone
        assertTrue(chooser.alternative(true, UNAVAILABLE, 15_999))     // 14.999 s gone
        assertFalse(chooser.alternative(true, UNAVAILABLE, 16_000))    // 15 s gone
    }

    @Test
    fun comingBackResetsTheCountOfHowLongItHasBeenGone() {
        val chooser = CarPicture.Chooser(fallBackAfterMs = 15_000)
        assertTrue(chooser.alternative(true, UNAVAILABLE, 0))
        assertTrue(chooser.alternative(true, AVAILABLE, 10_000))
        assertTrue(chooser.alternative(true, UNAVAILABLE, 20_000))
        assertTrue(chooser.alternative(true, UNAVAILABLE, 34_000))     // 14 s since it went again
        assertFalse(chooser.alternative(true, UNAVAILABLE, 35_000))
    }

    @Test
    fun notKnowingChangesNothing() {
        val chooser = CarPicture.Chooser()
        assertTrue(chooser.alternative(true, UNKNOWN, 0))
        assertTrue(chooser.alternative(true, UNKNOWN, 60_000))
        assertFalse(chooser.alternative(false, UNKNOWN, 61_000))
    }

    @Test
    fun choosingMirrorOverrulesAnAvailableAlternativeUntilItIsChosenAgain() {
        val chooser = CarPicture.Chooser()
        assertTrue(chooser.alternative(false, AVAILABLE, 0))
        chooser.picked(CarMenu.Choice.MIRROR)
        assertFalse(chooser.alternative(true, AVAILABLE, 1_000))
        assertFalse(chooser.alternative(false, AVAILABLE, 2_000))
        assertFalse(chooser.alternative(false, UNKNOWN, 3_000))
        chooser.picked(CarMenu.Choice.ALTERNATIVE)
        assertTrue(chooser.alternative(false, AVAILABLE, 4_000))
    }

    @Test
    fun aChoiceOfMirrorIsForgottenOnceTheAlternativeHasGoneAway() {
        val chooser = CarPicture.Chooser()
        chooser.picked(CarMenu.Choice.MIRROR)
        assertFalse(chooser.alternative(false, AVAILABLE, 0))
        assertFalse(chooser.alternative(false, UNAVAILABLE, 1_000))
        assertTrue(chooser.alternative(false, AVAILABLE, 2_000))       // back: it takes over, as by default
    }

    @Test
    fun anAlternativeWhosePictureIsOnTheCarIsNotDroppedForLookingUnavailable() {
        val chooser = CarPicture.Chooser(fallBackAfterMs = 15_000)
        assertTrue(chooser.alternative(true, UNAVAILABLE, 0, onScreen = true))
        assertTrue(chooser.alternative(true, UNAVAILABLE, 60_000, onScreen = true))
        // Once its picture has gone the clock starts.
        assertTrue(chooser.alternative(true, UNAVAILABLE, 61_000, onScreen = false))
        assertFalse(chooser.alternative(true, UNAVAILABLE, 76_000, onScreen = false))
    }

    // ---- the switching ----------------------------------------------------------------------

    private class FakeSource(
        override val name: String,
        val report: (PictureStatus) -> Unit,
        val host: PictureHost,
    ) : FrameSource {
        @Volatile var started = false
        @Volatile var stopped = false
        @Volatile var sink: FrameSink? = null
        val touches = CopyOnWriteArrayList<Int>()
        override fun start(client: MySpinServer.Client, sink: FrameSink) { started = true; this.sink = sink }
        override fun onTouch(touches: List<MySpinServer.Touch>) { this.touches += touches.size }
        override fun stop() { stopped = true }
    }

    private class FakeAlternative : AlternativePicture {
        @Volatile var now = UNAVAILABLE
        val made = CopyOnWriteArrayList<FakeSource>()
        override fun availability() = now
        @Volatile var failToStart = false
        override fun createSource(context: Context, onStatus: (PictureStatus) -> Unit, host: PictureHost): FrameSource {
            check(!failToStart) { "would not start" }
            return FakeSource("the alternative", onStatus, host).also { made += it }
        }
        override val menuLabel = "OTHER"
        override val menuHint = "See phone app"
        override fun invitation(): AlternativePicture.Invitation? = null
        override val troubleshooting = emptyList<String>()
        override val permissions = emptyList<String>()
    }

    private class Rig(val alternative: FakeAlternative?) {
        val phoneScreens = CopyOnWriteArrayList<FakeSource>()
        val statuses = CopyOnWriteArrayList<PictureStatus>()
        val frames = CopyOnWriteArrayList<ByteArray>()
        @Volatile var time = 0L
        val sink = FrameSink { data, _ -> frames += data; true }
        val picture = CarPicture(
            context = ContextWrapper(null),
            onStatus = { statuses += it },
            alternative = alternative,
            phoneScreen = { _, report, host -> FakeSource("the phone's screen", report, host).also { phoneScreens += it } },
            lookEveryMs = 5,
            clock = { time },
        )

        fun start() = picture.start(CLIENT, sink)

        fun waitFor(what: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 3_000
            while (!condition()) {
                assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
                Thread.sleep(2)
            }
        }
    }

    @Test
    fun withNoAlternativeThePhonesScreenIsShown() {
        val rig = Rig(alternative = null)
        rig.start()
        assertEquals(1, rig.phoneScreens.size)
        assertTrue(rig.phoneScreens[0].started)
        rig.picture.stop()
        assertTrue(rig.phoneScreens[0].stopped)
    }

    @Test
    fun anAlternativeThatIsAvailableAtTheStartIsShownStraightAway() {
        val alternative = FakeAlternative().apply { now = AVAILABLE }
        val rig = Rig(alternative)
        rig.start()
        assertEquals(0, rig.phoneScreens.size)
        assertEquals(1, alternative.made.size)
        assertTrue(alternative.made[0].started)
        rig.picture.stop()
    }

    @Test
    fun theAlternativeTakesOverWhenItAppearsAndHandsBackWhenItHasGone() {
        val alternative = FakeAlternative()
        val rig = Rig(alternative)
        rig.start()
        assertEquals(1, rig.phoneScreens.size)

        alternative.now = AVAILABLE
        rig.waitFor("the alternative to take over") { alternative.made.size == 1 && alternative.made[0].started }
        assertTrue(rig.phoneScreens[0].stopped)

        alternative.now = UNAVAILABLE
        Thread.sleep(40)                       // gone, but not for long enough
        assertEquals(1, rig.phoneScreens.size)
        rig.time = 60_000                      // now it has been gone a long time
        rig.waitFor("the phone's screen to come back") { rig.phoneScreens.size == 2 && rig.phoneScreens[1].started }
        assertTrue(alternative.made[0].stopped)
        rig.picture.stop()
    }

    @Test
    fun aReplacedPictureCanNoLongerReachTheCarOrThePhone() {
        val alternative = FakeAlternative()
        val rig = Rig(alternative)
        rig.start()
        val first = rig.phoneScreens[0]
        assertTrue(first.sink!!.offer(byteArrayOf(1), 0))
        first.report(STATUS_A)
        assertEquals(1, rig.frames.size)
        assertEquals(listOf(STATUS_A), rig.statuses.toList())

        alternative.now = AVAILABLE
        rig.waitFor("the alternative to take over") { alternative.made.size == 1 && alternative.made[0].started }
        assertFalse(first.sink!!.offer(byteArrayOf(2), 0))
        first.report(STATUS_B)
        assertEquals(1, rig.frames.size)
        assertEquals(listOf(STATUS_A), rig.statuses.toList())

        assertTrue(alternative.made[0].sink!!.offer(byteArrayOf(3), 0))
        alternative.made[0].report(STATUS_B)
        assertEquals(2, rig.frames.size)
        assertEquals(listOf(STATUS_A, STATUS_B), rig.statuses.toList())
        rig.picture.stop()
    }

    @Test
    fun mirrorChosenOnTheCarsMenuReplacesTheAlternativeAndItsButtonBringsItBack() {
        val alternative = FakeAlternative().apply { now = AVAILABLE }
        val rig = Rig(alternative)
        rig.start()
        val other = alternative.made[0]
        other.host.chosen(CarMenu.Choice.MIRROR)
        rig.waitFor("the phone's screen to take over") { rig.phoneScreens.size == 1 && rig.phoneScreens[0].started }
        assertTrue(other.stopped)

        // On the phone's-screen menu the other picture is now a button that can be chosen.
        val mirror = rig.phoneScreens[0]
        rig.waitFor("the other picture's button to be offered") { mirror.host.alternativeOption()?.enabled == true }
        assertEquals("OTHER", mirror.host.alternativeOption()!!.label)
        mirror.host.chosen(CarMenu.Choice.ALTERNATIVE)
        rig.waitFor("the alternative to come back") { alternative.made.size == 2 && alternative.made[1].started }
        assertTrue(mirror.stopped)
        rig.picture.stop()
    }

    @Test
    fun theOtherPicturesButtonIsGreyedWhileItIsNotAvailable() {
        val alternative = FakeAlternative()
        val rig = Rig(alternative)
        rig.start()
        val option = rig.phoneScreens[0].host.alternativeOption()!!
        assertFalse(option.enabled)
        assertEquals("See phone app", rig.phoneScreens[0].host.alternativeHint())
        rig.picture.stop()
    }

    @Test
    fun withoutAnAlternativeThereIsNoSecondButton() {
        val rig = Rig(alternative = null)
        rig.start()
        assertEquals(null, rig.phoneScreens[0].host.alternativeOption())
        assertEquals(null, rig.phoneScreens[0].host.alternativeHint())
        rig.picture.stop()
    }

    @Test
    fun anAlternativeThatWillNotStartIsLeftAloneAndThePhonesScreenIsShown() {
        val alternative = FakeAlternative().apply { now = AVAILABLE; failToStart = true }
        val rig = Rig(alternative)
        rig.start()
        assertEquals(1, rig.phoneScreens.size)
        assertTrue(rig.phoneScreens[0].started)
        Thread.sleep(40)                       // and it is not tried again every second
        assertEquals(1, rig.phoneScreens.size)
        assertEquals(0, alternative.made.size)
        assertFalse(rig.phoneScreens[0].host.alternativeOption()!!.enabled)
        rig.picture.stop()
    }

    @Test
    fun anAlternativeOnScreenSurvivesLookingUnavailable() {
        val alternative = FakeAlternative().apply { now = AVAILABLE }
        val rig = Rig(alternative)
        rig.start()
        alternative.made[0].report(STATUS_B)   // its picture is on the car
        alternative.now = UNAVAILABLE
        rig.time = 600_000
        Thread.sleep(40)
        assertEquals(0, rig.phoneScreens.size)
        assertFalse(alternative.made[0].stopped)
        rig.picture.stop()
    }

    @Test
    fun touchesGoToWhicheverPictureIsShowing() {
        val alternative = FakeAlternative().apply { now = AVAILABLE }
        val rig = Rig(alternative)
        rig.start()
        rig.picture.onTouch(listOf(MySpinServer.Touch(MySpinServer.TOUCH_DOWN, 1, 2, 0L)))
        assertEquals(listOf(1), alternative.made[0].touches.toList())
        rig.picture.stop()
    }

    @Test
    fun afterStopNothingStartsAgain() {
        val alternative = FakeAlternative()
        val rig = Rig(alternative)
        rig.start()
        rig.picture.stop()
        assertTrue(rig.phoneScreens[0].stopped)
        alternative.now = AVAILABLE
        Thread.sleep(40)
        assertEquals(0, alternative.made.size)
        assertFalse(rig.phoneScreens[0].sink!!.offer(byteArrayOf(9), 0))
    }

    @Test
    fun startingTwiceOrAfterStopDoesNothing() {
        val rig = Rig(alternative = null)
        rig.start()
        rig.start()
        assertEquals(1, rig.phoneScreens.size)
        rig.picture.stop()
        rig.start()
        assertEquals(1, rig.phoneScreens.size)
    }

    private companion object {
        val CLIENT = MySpinServer.Client(
            major = 1, minor = 0, build = 3, generation = MySpinServer.Generation.V1_0, name = "Test car",
            width = 671, height = 424, physicalWidthMm = 0, physicalHeightMm = 0, capabilities = 5,
            pixelFormat = MySpinServer.PIXEL_RGB565, endianness = 2, overrideCompression = 2,
            supportedCompressions = 0, compressionLevel = 0, customData = emptyList(), nonTouch = emptyList(),
        )
        val STATUS_A = PictureStatus(false, "a", "", "a")
        val STATUS_B = PictureStatus(true, "b", "", "b")
    }
}
