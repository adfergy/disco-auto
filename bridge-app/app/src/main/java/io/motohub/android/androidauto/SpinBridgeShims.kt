// SPDX-License-Identifier: MIT
// SpinBridge: minimal stand-ins for the MOTO-HUB types that the lifted `io.motohub.android.aa`
// receiver refers to. MOTO-HUB's own versions carry motorcycle-dashboard logic (learned T-Box
// geometry, screen margins, a rider-facing start-up screen) that has no meaning for a car head
// unit with a fixed, known screen. Only the members the receiver actually reads are kept.
package io.motohub.android.androidauto

data class DisplayGeometry(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "A display has a positive size" }
    }
}

/** Every coded source the Android Auto protocol defines, with the density MOTO-HUB pairs it with. */
enum class AndroidAutoVideoPreset(val source: DisplayGeometry, val densityDpi: Int) {
    LANDSCAPE_800X480(DisplayGeometry(800, 480), 160),
    LANDSCAPE_1280X720(DisplayGeometry(1280, 720), 160),
    LANDSCAPE_1920X1080(DisplayGeometry(1920, 1080), 240),
    LANDSCAPE_2560X1440(DisplayGeometry(2560, 1440), 320),
    LANDSCAPE_3840X2160(DisplayGeometry(3840, 2160), 480),
    PORTRAIT_720X1280(DisplayGeometry(720, 1280), 240),
    PORTRAIT_1080X1920(DisplayGeometry(1080, 1920), 240),
    PORTRAIT_1440X2560(DisplayGeometry(1440, 2560), 320),
    PORTRAIT_2160X3840(DisplayGeometry(2160, 3840), 480),
}

enum class AndroidAutoCapabilitySource { FALLBACK, USER_OVERRIDE, CAR_SCREEN }

/**
 * What the receiver tells Android Auto about the screen it is drawing for.
 *
 * [marginWidth] and [marginHeight] are coded pixels Android Auto must leave empty (split evenly
 * between the two sides), which is how a screen that is not exactly one of the coded sizes gets a
 * correctly shaped picture: Android Auto lays out inside what is left.
 */
data class AndroidAutoCapabilityProfile(
    val videoPreset: AndroidAutoVideoPreset,
    val source: AndroidAutoCapabilitySource,
    val touchEnabled: Boolean = true,
    val marginWidth: Int = 0,
    val marginHeight: Int = 0,
    val densityOverride: Int? = null,
) {
    val video: DisplayGeometry get() = videoPreset.source
    val densityDpi: Int get() = densityOverride ?: videoPreset.densityDpi

    /** The area Android Auto draws in and reports touches for. */
    val touchSurface: DisplayGeometry
        get() = DisplayGeometry(
            (video.width - marginWidth).coerceAtLeast(1),
            (video.height - marginHeight).coerceAtLeast(1)
        )
}

object AndroidAutoCapabilityProfiles {
    fun fallback(): AndroidAutoCapabilityProfile =
        AndroidAutoCapabilityProfile(AndroidAutoVideoPreset.LANDSCAPE_800X480, AndroidAutoCapabilitySource.FALLBACK)

    /**
     * The profile for a car screen of [width] x [height]: the smallest landscape source that
     * covers it, with margins that give Android Auto the screen's own shape.
     */
    fun forCarScreen(width: Int, height: Int, densityDpi: Int? = null): AndroidAutoCapabilityProfile {
        val preset = listOf(
            AndroidAutoVideoPreset.LANDSCAPE_800X480,
            AndroidAutoVideoPreset.LANDSCAPE_1280X720,
            AndroidAutoVideoPreset.LANDSCAPE_1920X1080,
        ).firstOrNull { it.source.width >= width && it.source.height >= height }
            ?: AndroidAutoVideoPreset.LANDSCAPE_1920X1080
        val coded = preset.source
        // Shrink one axis of the coded frame until what is left has the screen's aspect ratio.
        val codedAspect = coded.width.toDouble() / coded.height
        val screenAspect = width.toDouble() / height
        val (marginWidth, marginHeight) = if (codedAspect > screenAspect) {
            (coded.width - Math.round(coded.height * screenAspect).toInt()).coerceAtLeast(0) to 0
        } else {
            0 to (coded.height - Math.round(coded.width / screenAspect).toInt()).coerceAtLeast(0)
        }
        return AndroidAutoCapabilityProfile(
            videoPreset = preset,
            source = AndroidAutoCapabilitySource.CAR_SCREEN,
            marginWidth = marginWidth and 1.inv(),
            marginHeight = marginHeight and 1.inv(),
            densityOverride = densityDpi,
        )
    }
}

/** The few choices the receiver is given here that MOTO-HUB makes elsewhere, or differently. */
object AndroidAutoRuntime {
    /**
     * Which H.264 decoder Android Auto's video goes through. MOTO-HUB prefers the phone's
     * hardware decoder, because it feeds a hardware encoder. Here every frame is read back on the
     * CPU, and a hardware decoder is free to hand over frames in a private layout that cannot be:
     * on the Samsung SM-F976B, reading a frame from `c2.qti.avc.decoder` aborted the whole app
     * (15 Nov 2026). Android's own software decoder always gives ordinary YUV, and an 800x480
     * stream costs it next to nothing, so that is the default.
     */
    @Volatile var preferHardwareDecoder = false

    /**
     * Whether the receiver also listens on 127.0.0.1:5288 for Android Auto to dial in. That is
     * MOTO-HUB's "self-mode", which needs Android Auto to be asked to connect; Android Auto 17.4
     * and later cannot be asked, and the asking is not done here at all (see AndroidAutoSource),
     * so by default the port is not opened: the receiver only dials Android Auto's head unit
     * server.
     */
    @Volatile var listenForAndroidAutoDialIn = false

    /**
     * How long the receiver stays on a connection that Android Auto's head unit server has not
     * answered, before giving it up and dialling again. MOTO-HUB gives up after three requests two
     * seconds apart. A server that is merely busy (still clearing up after the last car) answers
     * late, and by then finds three requests where it expects one, or a connection already
     * abandoned; and connections abandoned unanswered are what was seen to leave the server deaf
     * (15 Nov 2026). So one request is sent and the answer is waited for. Zero restores
     * MOTO-HUB's behaviour.
     */
    @Volatile var versionResponsePatienceMs = 60_000L
}
