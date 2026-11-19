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

/** Whether the car is shown Android Auto or the logo, and that a replaced picture can no longer reach the car. */
class CarPictureTest {

    // ---- the rule ---------------------------------------------------------------------------

    @Test
    fun theLogoIsThePictureUntilAndroidAutoIsAvailable() {
        val chooser = CarPicture.Chooser()
        assertFalse(chooser.androidAuto(showingIt = false, availability = UNAVAILABLE, nowMs = 0))
        assertFalse(chooser.androidAuto(showingIt = false, availability = UNKNOWN, nowMs = 1_000))
        assertTrue(chooser.androidAuto(showingIt = false, availability = AVAILABLE, nowMs = 2_000))
    }

    @Test
    fun androidAutoSurvivesAShortAbsenceAndGivesWayAfterALongOne() {
        val chooser = CarPicture.Chooser(fallBackAfterMs = 15_000)
        assertTrue(chooser.androidAuto(true, AVAILABLE, 0))
        assertTrue(chooser.androidAuto(true, UNAVAILABLE, 1_000))      // just gone
        assertTrue(chooser.androidAuto(true, UNAVAILABLE, 15_999))     // 14.999 s gone
        assertFalse(chooser.androidAuto(true, UNAVAILABLE, 16_000))    // 15 s gone
    }

    @Test
    fun comingBackResetsTheCountOfHowLongItHasBeenGone() {
        val chooser = CarPicture.Chooser(fallBackAfterMs = 15_000)
        assertTrue(chooser.androidAuto(true, UNAVAILABLE, 0))
        assertTrue(chooser.androidAuto(true, AVAILABLE, 10_000))
        assertTrue(chooser.androidAuto(true, UNAVAILABLE, 20_000))
        assertTrue(chooser.androidAuto(true, UNAVAILABLE, 34_000))     // 14 s since it went again
        assertFalse(chooser.androidAuto(true, UNAVAILABLE, 35_000))
    }

    @Test
    fun notKnowingChangesNothing() {
        val chooser = CarPicture.Chooser()
        assertTrue(chooser.androidAuto(true, UNKNOWN, 0))
        assertTrue(chooser.androidAuto(true, UNKNOWN, 60_000))
        assertFalse(chooser.androidAuto(false, UNKNOWN, 61_000))
    }

    @Test
    fun androidAutoWhosePictureIsOnTheCarIsNotDroppedForLookingUnavailable() {
        val chooser = CarPicture.Chooser(fallBackAfterMs = 15_000)
        assertTrue(chooser.androidAuto(true, UNAVAILABLE, 0, onScreen = true))
        assertTrue(chooser.androidAuto(true, UNAVAILABLE, 60_000, onScreen = true))
        // Once its picture has gone the clock starts.
        assertTrue(chooser.androidAuto(true, UNAVAILABLE, 61_000, onScreen = false))
        assertFalse(chooser.androidAuto(true, UNAVAILABLE, 76_000, onScreen = false))
    }

    // ---- the switching ----------------------------------------------------------------------

    private class FakeSource(override val name: String, val report: (PictureStatus) -> Unit) : FrameSource {
        @Volatile var started = false
        @Volatile var stopped = false
        @Volatile var sink: FrameSink? = null
        val touches = CopyOnWriteArrayList<Int>()
        override fun start(client: MySpinServer.Client, sink: FrameSink) { started = true; this.sink = sink }
        override fun onTouch(touches: List<MySpinServer.Touch>) { this.touches += touches.size }
        override fun stop() { stopped = true }
    }

    private class FakeAndroidAuto : AlternativePicture {
        @Volatile var now = UNAVAILABLE
        @Volatile var failToStart = false
        val made = CopyOnWriteArrayList<FakeSource>()
        val logos = CopyOnWriteArrayList<FakeSource>()
        override fun availability() = now
        override fun createSource(context: Context, onStatus: (PictureStatus) -> Unit): FrameSource {
            check(!failToStart) { "would not start" }
            return FakeSource("Android Auto", onStatus).also { made += it }
        }
        override fun waitingSource(context: Context, onStatus: (PictureStatus) -> Unit): FrameSource =
            FakeSource("the logo", onStatus).also { logos += it }
        override val permissions = emptyList<String>()
    }

    private class Rig(val androidAuto: FakeAndroidAuto = FakeAndroidAuto()) {
        val statuses = CopyOnWriteArrayList<PictureStatus>()
        val frames = CopyOnWriteArrayList<ByteArray>()
        @Volatile var time = 0L
        val sink = FrameSink { data, _ -> frames += data; true }
        val picture = CarPicture(
            context = ContextWrapper(null),
            onStatus = { statuses += it },
            androidAuto = androidAuto,
            lookEveryMs = 5,
            clock = { time },
        )
        val logos get() = androidAuto.logos

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
    fun withoutAndroidAutoTheLogoIsShown() {
        val rig = Rig()
        rig.start()
        assertEquals(1, rig.logos.size)
        assertTrue(rig.logos[0].started)
        rig.picture.stop()
        assertTrue(rig.logos[0].stopped)
    }

    @Test
    fun androidAutoThatIsAvailableAtTheStartIsShownStraightAway() {
        val rig = Rig(FakeAndroidAuto().apply { now = AVAILABLE })
        rig.start()
        assertEquals(0, rig.logos.size)
        assertEquals(1, rig.androidAuto.made.size)
        assertTrue(rig.androidAuto.made[0].started)
        rig.picture.stop()
    }

    @Test
    fun androidAutoTakesOverWhenItAppearsAndHandsBackWhenItHasGone() {
        val rig = Rig()
        val androidAuto = rig.androidAuto
        rig.start()
        assertEquals(1, rig.logos.size)

        androidAuto.now = AVAILABLE
        rig.waitFor("Android Auto to take over") { androidAuto.made.size == 1 && androidAuto.made[0].started }
        assertTrue(rig.logos[0].stopped)

        androidAuto.now = UNAVAILABLE
        Thread.sleep(40)                       // gone, but not for long enough
        assertEquals(1, rig.logos.size)
        rig.time = 60_000                      // now it has been gone a long time
        rig.waitFor("the logo to come back") { rig.logos.size == 2 && rig.logos[1].started }
        assertTrue(androidAuto.made[0].stopped)
        rig.picture.stop()
    }

    @Test
    fun aReplacedPictureCanNoLongerReachTheCarOrThePhone() {
        val rig = Rig()
        val androidAuto = rig.androidAuto
        rig.start()
        val first = rig.logos[0]
        assertTrue(first.sink!!.offer(byteArrayOf(1), 0))
        first.report(STATUS_A)
        assertEquals(1, rig.frames.size)
        assertEquals(listOf(STATUS_A), rig.statuses.toList())

        androidAuto.now = AVAILABLE
        rig.waitFor("Android Auto to take over") { androidAuto.made.size == 1 && androidAuto.made[0].started }
        assertFalse(first.sink!!.offer(byteArrayOf(2), 0))
        first.report(STATUS_B)
        assertEquals(1, rig.frames.size)
        assertEquals(listOf(STATUS_A), rig.statuses.toList())

        assertTrue(androidAuto.made[0].sink!!.offer(byteArrayOf(3), 0))
        androidAuto.made[0].report(STATUS_B)
        assertEquals(2, rig.frames.size)
        assertEquals(listOf(STATUS_A, STATUS_B), rig.statuses.toList())
        rig.picture.stop()
    }

    @Test
    fun androidAutoThatWillNotStartIsLeftAloneAndTheLogoIsShown() {
        val rig = Rig(FakeAndroidAuto().apply { now = AVAILABLE; failToStart = true })
        rig.start()
        assertEquals(1, rig.logos.size)
        assertTrue(rig.logos[0].started)
        Thread.sleep(40)                       // and it is not tried again every second
        assertEquals(1, rig.logos.size)
        assertEquals(0, rig.androidAuto.made.size)
        rig.picture.stop()
    }

    @Test
    fun androidAutoOnScreenSurvivesLookingUnavailable() {
        val rig = Rig(FakeAndroidAuto().apply { now = AVAILABLE })
        rig.start()
        rig.androidAuto.made[0].report(STATUS_B)   // its picture is on the car
        rig.androidAuto.now = UNAVAILABLE
        rig.time = 600_000
        Thread.sleep(40)
        assertEquals(0, rig.logos.size)
        assertFalse(rig.androidAuto.made[0].stopped)
        rig.picture.stop()
    }

    @Test
    fun touchesGoToWhicheverPictureIsShowing() {
        val rig = Rig(FakeAndroidAuto().apply { now = AVAILABLE })
        rig.start()
        rig.picture.onTouch(listOf(MySpinServer.Touch(MySpinServer.TOUCH_DOWN, 1, 2, 0L)))
        assertEquals(listOf(1), rig.androidAuto.made[0].touches.toList())
        rig.picture.stop()
    }

    @Test
    fun afterStopNothingStartsAgain() {
        val rig = Rig()
        rig.start()
        rig.picture.stop()
        assertTrue(rig.logos[0].stopped)
        rig.androidAuto.now = AVAILABLE
        Thread.sleep(40)
        assertEquals(0, rig.androidAuto.made.size)
        assertFalse(rig.logos[0].sink!!.offer(byteArrayOf(9), 0))
    }

    @Test
    fun startingTwiceOrAfterStopDoesNothing() {
        val rig = Rig()
        rig.start()
        rig.start()
        assertEquals(1, rig.logos.size)
        rig.picture.stop()
        rig.start()
        assertEquals(1, rig.logos.size)
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
