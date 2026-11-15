// SPDX-License-Identifier: MIT
package app.spinbridge.myspin

/**
 * Packs ARGB pixels (Android's `Bitmap.getPixels` layout: `0xAARRGGBB` per int) into the raw pixel
 * formats a mySPIN head unit can ask for when it does not want JPEG.
 *
 * Unverified against a car: the launcher does this in native code. The reading used here is the
 * plain one, each pixel being one 16/24/32-bit number with its components in the order the format
 * is named (most significant first), written out in the byte order the car asked for.
 */
object PixelPacker {
    const val BIG_ENDIAN = 1
    const val LITTLE_ENDIAN = 2

    fun pack(argb: IntArray, pixelFormat: Int, endianness: Int): ByteArray {
        val little = endianness == LITTLE_ENDIAN
        return when (pixelFormat) {
            MySpinServer.PIXEL_RGB565 -> rgb565(argb, little)
            MySpinServer.PIXEL_RGB888 -> rgb888(argb, little)
            MySpinServer.PIXEL_RGBA8888 -> rgba8888(argb, little)
            else -> argb8888(argb, little)
        }
    }

    private fun rgb565(argb: IntArray, little: Boolean): ByteArray {
        val out = ByteArray(argb.size * 2)
        var at = 0
        for (pixel in argb) {
            val value = ((pixel shr 8) and 0xF800) or ((pixel shr 5) and 0x07E0) or ((pixel shr 3) and 0x001F)
            if (little) {
                out[at++] = value.toByte()
                out[at++] = (value shr 8).toByte()
            } else {
                out[at++] = (value shr 8).toByte()
                out[at++] = value.toByte()
            }
        }
        return out
    }

    private fun rgb888(argb: IntArray, little: Boolean): ByteArray {
        val out = ByteArray(argb.size * 3)
        var at = 0
        for (pixel in argb) {
            val r = (pixel shr 16).toByte()
            val g = (pixel shr 8).toByte()
            val b = pixel.toByte()
            if (little) {
                out[at++] = b; out[at++] = g; out[at++] = r
            } else {
                out[at++] = r; out[at++] = g; out[at++] = b
            }
        }
        return out
    }

    private fun argb8888(argb: IntArray, little: Boolean): ByteArray {
        val out = ByteArray(argb.size * 4)
        var at = 0
        for (pixel in argb) {
            val a = (pixel ushr 24).toByte()
            val r = (pixel shr 16).toByte()
            val g = (pixel shr 8).toByte()
            val b = pixel.toByte()
            if (little) {
                out[at++] = b; out[at++] = g; out[at++] = r; out[at++] = a
            } else {
                out[at++] = a; out[at++] = r; out[at++] = g; out[at++] = b
            }
        }
        return out
    }

    private fun rgba8888(argb: IntArray, little: Boolean): ByteArray {
        val out = ByteArray(argb.size * 4)
        var at = 0
        for (pixel in argb) {
            val a = (pixel ushr 24).toByte()
            val r = (pixel shr 16).toByte()
            val g = (pixel shr 8).toByte()
            val b = pixel.toByte()
            if (little) {
                out[at++] = a; out[at++] = b; out[at++] = g; out[at++] = r
            } else {
                out[at++] = r; out[at++] = g; out[at++] = b; out[at++] = a
            }
        }
        return out
    }
}
