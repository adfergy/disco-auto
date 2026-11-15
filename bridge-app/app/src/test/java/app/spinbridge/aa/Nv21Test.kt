// SPDX-License-Identifier: MIT
package app.spinbridge.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class Nv21Test {

    private fun plane(vararg bytes: Int, rowStride: Int, pixelStride: Int) =
        Nv21.Plane(ByteBuffer.wrap(ByteArray(bytes.size) { bytes[it].toByte() }), rowStride, pixelStride)

    @Test
    fun planarInputWithRowPaddingIsRepacked() {
        // 4x2 picture, rows padded to 6 bytes. Chroma is 2x1, planar (pixel stride 1), padded to 3.
        val y = plane(10, 11, 12, 13, 99, 99, 20, 21, 22, 23, 99, 99, rowStride = 6, pixelStride = 1)
        val u = plane(100, 101, 99, rowStride = 3, pixelStride = 1)
        val v = plane(200, 201, 99, rowStride = 3, pixelStride = 1)
        val out = ByteArray(Nv21.size(4, 2))
        Nv21.convert(y, u, v, 0, 0, 4, 2, out, fullRange = true)
        assertArrayEquals(
            byteArrayOf(10, 11, 12, 13, 20, 21, 22, 23, 200.toByte(), 100, 201.toByte(), 101),
            out
        )
    }

    @Test
    fun semiPlanarInputIsRepacked() {
        // The usual hardware decoder layout: U and V views over one interleaved buffer, pixel stride 2.
        val y = plane(1, 2, 3, 4, rowStride = 2, pixelStride = 1)
        val u = plane(50, 60, rowStride = 2, pixelStride = 2)   // U at index 0
        val v = plane(60, 50, rowStride = 2, pixelStride = 2)   // V at index 0
        val out = ByteArray(Nv21.size(2, 2))
        Nv21.convert(y, u, v, 0, 0, 2, 2, out, fullRange = true)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 60, 50), out)
    }

    @Test
    fun videoRangeIsWidenedToFullRange() {
        val y = plane(16, 235, 126, 0, rowStride = 2, pixelStride = 1)
        val u = plane(128, rowStride = 1, pixelStride = 1)
        val v = plane(240, rowStride = 1, pixelStride = 1)
        val out = ByteArray(Nv21.size(2, 2))
        Nv21.convert(y, u, v, 0, 0, 2, 2, out)
        assertEquals("black", 0, out[0].toInt() and 0xFF)
        assertEquals("white", 255, out[1].toInt() and 0xFF)
        assertEquals("mid grey", 128, out[2].toInt() and 0xFF)
        assertEquals("below black clamps", 0, out[3].toInt() and 0xFF)
        assertEquals("V at its video-range maximum", 255, out[4].toInt() and 0xFF)
        assertEquals("neutral U stays neutral", 128, out[5].toInt() and 0xFF)
    }

    @Test
    fun cropOffsetIsHonoured() {
        // 4x4 luma, take the 2x2 block starting at (2, 2).
        val y = plane(
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 7, 8,
            0, 0, 9, 10,
            rowStride = 4, pixelStride = 1
        )
        val u = plane(1, 2, 3, 4, rowStride = 2, pixelStride = 1)
        val v = plane(5, 6, 7, 8, rowStride = 2, pixelStride = 1)
        val out = ByteArray(Nv21.size(2, 2))
        Nv21.convert(y, u, v, 2, 2, 2, 2, out, fullRange = true)
        assertArrayEquals(byteArrayOf(7, 8, 9, 10, 8, 4), out)
    }
}
