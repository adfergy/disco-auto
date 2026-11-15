// SPDX-License-Identifier: MIT
package app.spinbridge

import android.graphics.Bitmap
import app.spinbridge.myspin.MySpinServer
import app.spinbridge.myspin.PixelPacker
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Turns one picture, already at the car's screen size, into the bytes of a FramebufferUpdate for
 * whatever compression the car asked for. Not thread-safe: one encoder per producing thread.
 */
class FrameEncoder(var jpegQuality: Int = 75) {
    private val jpegBuffer = ByteArrayOutputStream(96 * 1024)
    private var pixels = IntArray(0)

    /** The encoded frame and the compression id to announce with it. */
    fun encode(frame: Bitmap, client: MySpinServer.Client): Pair<ByteArray, Int> =
        when (val compression = client.frameCompression) {
            MySpinServer.COMPRESSION_JPEG -> jpeg(frame) to compression
            MySpinServer.COMPRESSION_ZLIB -> deflate(raw(frame, client)) to compression
            else -> raw(frame, client) to MySpinServer.COMPRESSION_NONE
        }

    fun jpeg(frame: Bitmap): ByteArray {
        jpegBuffer.reset()
        frame.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpegBuffer)
        return jpegBuffer.toByteArray()
    }

    private fun raw(frame: Bitmap, client: MySpinServer.Client): ByteArray {
        val count = frame.width * frame.height
        if (pixels.size != count) pixels = IntArray(count)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        return PixelPacker.pack(pixels, client.pixelFormat, client.endianness)
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size / 4 + 64)
            val chunk = ByteArray(32 * 1024)
            while (!deflater.finished()) {
                out.write(chunk, 0, deflater.deflate(chunk))
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
