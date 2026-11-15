// SPDX-License-Identifier: MIT
package app.spinbridge.aa

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import app.spinbridge.BridgeLog
import app.spinbridge.FrameEncoder
import app.spinbridge.FrameSink
import app.spinbridge.myspin.MySpinServer
import java.io.ByteArrayOutputStream

/**
 * The end of the video pipeline: a Surface for an H.264 decoder to render into, and behind it the
 * conversion of each decoded frame into what the car takes.
 *
 * The Surface belongs to an ImageReader in YUV 4:2:0, the one output format every Android decoder
 * is required to support for this. Each frame is repacked to NV21 and JPEG-encoded directly from
 * YUV, so the fast case (a JPEG-speaking car whose screen is exactly the video size) never touches
 * RGB at all. Any other car (the Discovery Sport wants zlib-packed RGB565 at 671x424) takes the
 * slow path: margins cut off, scaled to the screen, then encoded however the car asked.
 *
 * [marginWidth] and [marginHeight] are coded pixels the video source left empty around its
 * picture. Frames are converted no more often than the car can take them: a head unit that draws
 * eight pictures a second gains nothing from thirty conversions.
 */
class DecodedFrameBridge(
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val marginWidth: Int,
    private val marginHeight: Int,
    private val label: String,
    private val sink: FrameSink,
    client: MySpinServer.Client,
    /** Called on the frame thread each time a decoded picture has been handed to the car. */
    private val onPicture: (() -> Unit)? = null,
    /** Called once if the decoder turns out to give frames in a layout that cannot be read. */
    private val onUnreadable: ((format: Int) -> Unit)? = null,
) {
    /**
     * Counts since the last [takePerformance], for the log. [decoded] is how many times the
     * decoder said a frame was ready; frames it produced faster than they could be collected
     * count once.
     */
    class Performance(val decoded: Int, val converted: Int, val averageMillis: Double, val averageBytes: Int)

    @Volatile var client: MySpinServer.Client = client
    @Volatile var framesDelivered = 0
        private set

    private val thread = HandlerThread("frames-$label").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader: ImageReader =
        ImageReader.newInstance(videoWidth, videoHeight, ImageFormat.YUV_420_888, MAX_IMAGES)

    val surface: Surface get() = reader.surface

    // Frame-thread only.
    private var nv21 = ByteArray(0)
    private val jpeg = ByteArrayOutputStream(128 * 1024)
    private val encoder = FrameEncoder()
    private var base: Bitmap? = null        // the last picture, scaled to the car's screen
    private var haveBase = false
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var failuresLogged = 0
    private var pending: Image? = null
    private var lastConvertAt = 0L
    private var closed = false
    private var formatLogged = false
    private var unreadableReported = false

    private val performanceLock = Any()
    private var decodedCount = 0
    private var convertedCount = 0
    private var convertNanos = 0L
    private var convertedBytes = 0L

    private val drainPending = Runnable {
        val image = pending ?: return@Runnable
        pending = null
        convert(image)
    }

    init {
        reader.setOnImageAvailableListener({ source -> onImage(source) }, handler)
    }

    private fun onImage(source: ImageReader) {
        if (closed) return
        synchronized(performanceLock) { decodedCount++ }
        val image = try {
            source.acquireLatestImage()
        } catch (failure: Exception) {
            logFailure("could not take a decoded frame", failure)
            null
        } ?: return
        // Not more often than the car can use: keep only the newest frame and convert it when
        // the interval is up, so the last frame of a burst is never lost.
        pending?.close()
        pending = null
        handler.removeCallbacks(drainPending)
        val wait = MIN_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastConvertAt)
        if (wait > 0) {
            pending = image
            handler.postDelayed(drainPending, wait)
        } else {
            convert(image)
        }
    }

    private fun convert(image: Image) {
        val startedAt = SystemClock.elapsedRealtime()
        val startedNanos = System.nanoTime()
        lastConvertAt = startedAt
        try {
            val car = client
            val encoded = encode(image, car) ?: return
            synchronized(performanceLock) {
                convertedCount++
                convertNanos += System.nanoTime() - startedNanos
                convertedBytes += encoded.first.size
            }
            if (++framesDelivered == 1) {
                BridgeLog.log(
                    "[$label] first frame for the car: decoded ${image.width}x${image.height} " +
                        "(crop ${image.cropRect.width()}x${image.cropRect.height()}, " +
                        "pixel strides ${image.planes[0].pixelStride}/${image.planes[1].pixelStride}), " +
                        "${encoded.first.size} bytes ${MySpinServer.compressionName(encoded.second)}"
                )
            }
            if (sink.offer(encoded.first, encoded.second)) onPicture?.invoke()
        } catch (failure: Exception) {
            logFailure("frame conversion failed", failure)
        } finally {
            image.close()
        }
    }

    private fun logFailure(what: String, failure: Exception) {
        if (failuresLogged++ < 5) BridgeLog.log("[$label] $what: ${failure.javaClass.simpleName}: ${failure.message}")
    }

    /**
     * Whether the frame's pixels may be asked for. A hardware decoder can hand over frames in a
     * layout private to the phone's chipset; asking for the planes of one of those does not fail
     * politely, it aborts the process inside the framework (seen with `c2.qti.avc.decoder` on a
     * Samsung SM-F976B). So only the ordinary YUV layouts are touched.
     */
    private fun readable(image: Image): Boolean {
        val format = try {
            image.hardwareBuffer?.use { it.format }
        } catch (_: Exception) {
            null
        }
        if (!formatLogged) {
            formatLogged = true
            BridgeLog.log(
                "[$label] decoded frames arrive as image format ${image.format}, buffer format " +
                    (format?.let { "0x${it.toString(16)}" } ?: "unknown")
            )
        }
        // No buffer to ask (older paths): trust the reader's own YUV_420_888.
        if (format == null || format in READABLE_FORMATS) return true
        if (!unreadableReported) {
            unreadableReported = true
            BridgeLog.log("[$label] the decoder gives frames in a private layout (0x${format.toString(16)}) that cannot be read; no picture will reach the car from it")
            onUnreadable?.invoke(format)
        }
        return false
    }

    private fun encode(image: Image, car: MySpinServer.Client): Pair<ByteArray, Int>? {
        if (!readable(image)) return null
        val crop = image.cropRect
        val width = crop.width() and 1.inv()
        val height = crop.height() and 1.inv()
        if (width <= 0 || height <= 0) return null
        val needed = Nv21.size(width, height)
        if (nv21.size != needed) nv21 = ByteArray(needed)
        val planes = image.planes
        Nv21.convert(
            Nv21.Plane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride),
            Nv21.Plane(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride),
            Nv21.Plane(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride),
            crop.left, crop.top, width, height, nv21,
        )
        val sameShape = width == car.width && height == car.height && marginWidth == 0 && marginHeight == 0
        val fast = sameShape && car.frameCompression == MySpinServer.COMPRESSION_JPEG
        jpeg.reset()
        // On the slow path this JPEG is only a stepping stone to RGB, so keep it nearly lossless:
        // its artefacts would otherwise be baked into every frame and bloat what zlib makes of it.
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), if (fast) JPEG_QUALITY else STEP_QUALITY, jpeg)
        val whole = jpeg.toByteArray()
        if (fast) return whole to MySpinServer.COMPRESSION_JPEG

        // The slow path: cut the margins off, scale to the screen, and encode however the car asked.
        val decoded = BitmapFactory.decodeByteArray(whole, 0, whole.size) ?: return null
        val target = bitmapFor(car, base).also { base = it }
        val used = Rect(marginWidth / 2, marginHeight / 2, width - marginWidth / 2, height - marginHeight / 2)
        Canvas(target).drawBitmap(decoded, used, Rect(0, 0, car.width, car.height), scalePaint)
        decoded.recycle()
        haveBase = true
        return encoder.encode(target, car)
    }

    private fun bitmapFor(car: MySpinServer.Client, existing: Bitmap?): Bitmap =
        existing?.takeIf { it.width == car.width && it.height == car.height }
            ?: Bitmap.createBitmap(car.width, car.height, Bitmap.Config.ARGB_8888).also { existing?.recycle() }

    /**
     * Sends the last picture again. Android Auto only sends video when its screen changes, so
     * after something else has been on the car's screen this is how its still picture comes back.
     */
    fun refresh() {
        handler.post {
            if (closed || !haveBase) return@post
            val picture = base ?: return@post
            val car = client
            if (picture.width != car.width || picture.height != car.height) return@post
            try {
                val encoded = encoder.encode(picture, car)
                sink.offer(encoded.first, encoded.second)
            } catch (failure: Exception) {
                logFailure("refreshing the picture failed", failure)
            }
        }
    }

    /**
     * The video session has ended: forget its last picture, so that nothing of it is sent again
     * (a banner refresh would otherwise bring the old screen back until the new session's first
     * frame).
     */
    fun reset() {
        handler.post {
            if (closed) return@post
            handler.removeCallbacks(drainPending)
            pending?.close()
            pending = null
            haveBase = false
        }
    }

    /** What happened since the last call, and starts counting afresh. */
    fun takePerformance(): Performance = synchronized(performanceLock) {
        Performance(
            decoded = decodedCount,
            converted = convertedCount,
            averageMillis = if (convertedCount > 0) convertNanos / 1e6 / convertedCount else 0.0,
            averageBytes = if (convertedCount > 0) (convertedBytes / convertedCount).toInt() else 0,
        ).also {
            decodedCount = 0
            convertedCount = 0
            convertNanos = 0
            convertedBytes = 0
        }
    }

    /** Stops delivering frames and releases the Surface. The decoder must be stopped first. */
    fun close() {
        // On the frame thread, so it cannot race a frame that is being converted.
        handler.post {
            closed = true
            handler.removeCallbacks(drainPending)
            pending?.close()
            pending = null
            runCatching { reader.close() }
            base?.recycle()
            base = null
            haveBase = false
            thread.quitSafely()
        }
    }

    private companion object {
        const val JPEG_QUALITY = 70
        const val STEP_QUALITY = 95
        const val MAX_IMAGES = 4

        /** About twelve conversions a second at most; the Discovery Sport draws about eight. */
        const val MIN_INTERVAL_MS = 80L

        /** Ordinary YUV 4:2:0 buffer layouts: flexible YCbCr 4:2:0, YV12 and NV21. */
        val READABLE_FORMATS = setOf(0x23, 0x32315659, 0x11)
    }
}
