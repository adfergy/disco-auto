// SPDX-License-Identifier: MIT
package app.spinbridge.picture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import app.spinbridge.Brand
import app.spinbridge.FrameEncoder
import app.spinbridge.R
import app.spinbridge.myspin.MySpinServer

/**
 * What the car shows while Android Auto's picture is not there: the logo, and a line or two
 * underneath saying what is going on. Nothing on it can be touched; what to do is on the phone,
 * which can say it at length.
 *
 * Drawing is not thread-safe: one card per producing thread.
 */
class CarCard(context: Context) {
    private val logo: Bitmap? = runCatching {
        BitmapFactory.decodeResource(context.resources, R.drawable.logo)
    }.getOrNull()
    private val encoder = FrameEncoder()
    private var madeFor: Any? = null
    private var made: Pair<ByteArray, Int>? = null

    /** The card as a frame for [car]: the bytes and the compression to announce with them. */
    fun frame(car: MySpinServer.Client, message: String): Pair<ByteArray, Int> {
        val shape = listOf(car.width, car.height, car.pixelFormat, car.endianness, car.frameCompression, message)
        made?.let { if (madeFor == shape) return it }
        val picture = draw(car.width, car.height, message)
        try {
            return encoder.encode(picture, car).also { made = it; madeFor = shape }
        } finally {
            picture.recycle()
        }
    }

    private fun draw(width: Int, height: Int, message: String): Bitmap {
        val card = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(card)
        canvas.drawColor(Brand.GROUND)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        var logoBottom = height * LOGO_TOP
        if (logo != null) {
            val shape = logo.width.toFloat() / logo.height
            var logoHeight = height * LOGO_HEIGHT
            var logoWidth = logoHeight * shape
            if (logoWidth > width * LOGO_MAX_WIDTH) {
                logoWidth = width * LOGO_MAX_WIDTH
                logoHeight = logoWidth / shape
            }
            val left = (width - logoWidth) / 2f
            val top = height * LOGO_TOP
            canvas.drawBitmap(logo, null, RectF(left, top, left + logoWidth, top + logoHeight), paint)
            logoBottom = top + logoHeight
        }

        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.argb(240, Color.red(Brand.INK), Color.green(Brand.INK), Color.blue(Brand.INK))
        paint.textSize = height * TEXT_SIZE
        val lines = wrapWords(message, width * 0.84f, paint::measureText)
        val lineHeight = paint.textSize * 1.35f
        val room = height - logoBottom
        var baseline = logoBottom + (room - lines.size * lineHeight) / 2f + paint.textSize
        for (line in lines) {
            canvas.drawText(line, width / 2f, baseline, paint)
            baseline += lineHeight
        }
        return card
    }

    companion object {
        private const val LOGO_TOP = 0.08f
        private const val LOGO_HEIGHT = 0.46f
        private const val LOGO_MAX_WIDTH = 0.70f
        private const val TEXT_SIZE = 0.068f
    }
}

/**
 * Breaks [text] at spaces so that no line is wider than [room], as [measure] sees it. A single
 * word wider than the room keeps a line to itself.
 */
internal fun wrapWords(text: String, room: Float, measure: (String) -> Float): List<String> {
    val lines = ArrayList<String>()
    var line = ""
    for (word in text.split(' ').filter { it.isNotEmpty() }) {
        val longer = if (line.isEmpty()) word else "$line $word"
        if (line.isEmpty() || measure(longer) <= room) {
            line = longer
        } else {
            lines += line
            line = word
        }
    }
    if (line.isNotEmpty()) lines += line
    return lines
}
