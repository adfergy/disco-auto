// SPDX-License-Identifier: MIT
package app.spinbridge.aa

import java.nio.ByteBuffer

/**
 * Repacks a decoder's YUV 4:2:0 output (three planes with arbitrary row and pixel strides) into
 * NV21, the layout Android's JPEG encoder takes, widening it from video range to full range on
 * the way.
 *
 * The widening matters: Android Auto's H.264 is limited range (black is 16, white is 235), and a
 * JPEG is full range. Copied as-is, blacks come out dark grey and the whole picture looks washed
 * out on the car's screen.
 *
 * No Android types, so it is tested on the JVM with hand-built planes.
 */
object Nv21 {
    class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

    private val lumaLut = ByteArray(256) { value ->
        (((value - 16) * 255 + 109) / 219).coerceIn(0, 255).toByte()
    }
    private val chromaLut = ByteArray(256) { value ->
        (((value - 128) * 255) / 224 + 128).coerceIn(0, 255).toByte()
    }

    fun size(width: Int, height: Int): Int = width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2)

    /**
     * Fills [out] (at least [size] bytes) from the picture whose top-left pixel is at
     * ([left], [top]) in the planes. [width] and [height] must be even.
     */
    fun convert(
        y: Plane, u: Plane, v: Plane,
        left: Int, top: Int, width: Int, height: Int,
        out: ByteArray,
        fullRange: Boolean = false,
    ) {
        require(width % 2 == 0 && height % 2 == 0) { "NV21 needs even dimensions, got ${width}x$height" }
        require(out.size >= size(width, height)) { "output buffer too small" }
        val luma = if (fullRange) null else lumaLut
        val chroma = if (fullRange) null else chromaLut

        val row = ByteArray(maxOf(width, 1) * maxOf(y.pixelStride, u.pixelStride, v.pixelStride))
        var at = 0

        // Luma: one row at a time, so the row stride's padding is skipped.
        val yBuffer = y.buffer.duplicate()
        for (line in 0 until height) {
            val start = (top + line) * y.rowStride + left * y.pixelStride
            if (y.pixelStride == 1) {
                yBuffer.position(start)
                yBuffer.get(row, 0, width)
                if (luma == null) {
                    System.arraycopy(row, 0, out, at, width)
                } else {
                    for (i in 0 until width) out[at + i] = luma[row[i].toInt() and 0xFF]
                }
            } else {
                for (i in 0 until width) {
                    val value = yBuffer.get(start + i * y.pixelStride).toInt() and 0xFF
                    out[at + i] = luma?.get(value) ?: value.toByte()
                }
            }
            at += width
        }

        // Chroma: NV21 wants V then U for each 2x2 block.
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val uBuffer = u.buffer.duplicate()
        val vBuffer = v.buffer.duplicate()
        for (line in 0 until chromaHeight) {
            val uStart = (top / 2 + line) * u.rowStride + (left / 2) * u.pixelStride
            val vStart = (top / 2 + line) * v.rowStride + (left / 2) * v.pixelStride
            for (i in 0 until chromaWidth) {
                val vValue = vBuffer.get(vStart + i * v.pixelStride).toInt() and 0xFF
                val uValue = uBuffer.get(uStart + i * u.pixelStride).toInt() and 0xFF
                out[at++] = chroma?.get(vValue) ?: vValue.toByte()
                out[at++] = chroma?.get(uValue) ?: uValue.toByte()
            }
        }
    }
}
