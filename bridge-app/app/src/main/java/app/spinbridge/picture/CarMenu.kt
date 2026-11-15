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
 * What the car shows when there is no picture for it: the logo, the kinds of picture there are
 * as buttons that can be touched on the car's own screen, and one line saying where to look for
 * the rest. What to actually do is on the phone, which can say it at length.
 *
 * Drawing is not thread-safe: one menu per producing thread. [choiceAt] and [buttons] are plain
 * arithmetic and can be used from anywhere.
 */
class CarMenu(context: Context) {
    enum class Choice {
        /** The phone's own screen. */
        MIRROR,

        /** The other picture (Android Auto). */
        ALTERNATIVE,
    }

    /**
     * One button. Not [enabled]: greyed out, and touching it does nothing. [current]: this is
     * what is on its way to the screen right now, so it is marked rather than offered.
     */
    data class Option(val choice: Choice, val label: String, val enabled: Boolean, val current: Boolean = false)

    /** Everything on the menu that can differ from one drawing to the next. */
    data class Content(val options: List<Option>, val hint: String)

    /** A rectangle in the car's own pixels. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float, slop: Float = 0f) =
            x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop
    }

    private val logo: Bitmap? = runCatching {
        BitmapFactory.decodeResource(context.resources, R.drawable.logo)
    }.getOrNull()
    private val encoder = FrameEncoder()
    private var madeFor: Any? = null
    private var made: Pair<ByteArray, Int>? = null

    /** The menu as a frame for [car]: the bytes and the compression to announce with them. */
    fun frame(car: MySpinServer.Client, content: Content): Pair<ByteArray, Int> {
        val shape = listOf(car.width, car.height, car.pixelFormat, car.endianness, car.frameCompression, content)
        made?.let { if (madeFor == shape) return it }
        val picture = draw(car.width, car.height, content)
        try {
            return encoder.encode(picture, car).also { made = it; madeFor = shape }
        } finally {
            picture.recycle()
        }
    }

    private fun draw(width: Int, height: Int, content: Content): Bitmap {
        val card = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(card)
        canvas.drawColor(Brand.GROUND)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

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
        }

        val boxes = buttons(width, height, content.options.size)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        content.options.forEachIndexed { index, option ->
            val box = boxes[index]
            val rect = RectF(box.left, box.top, box.right, box.bottom)
            val radius = (box.bottom - box.top) / 2f
            val labelColour: Int
            when {
                option.current -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = height / 140f
                    paint.color = Brand.ACCENT
                    canvas.drawRoundRect(rect, radius, radius, paint)
                    labelColour = Brand.INK
                }
                option.enabled -> {
                    paint.style = Paint.Style.FILL
                    paint.color = Brand.INK
                    canvas.drawRoundRect(rect, radius, radius, paint)
                    labelColour = Brand.GROUND
                }
                else -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = height / 210f
                    paint.color = faded(Brand.INK, 0.30f)
                    canvas.drawRoundRect(rect, radius, radius, paint)
                    labelColour = faded(Brand.INK, 0.38f)
                }
            }
            paint.style = Paint.Style.FILL
            paint.color = labelColour
            paint.textSize = (box.bottom - box.top) * LABEL_SIZE
            val room = (box.right - box.left) * 0.86f
            val wide = paint.measureText(option.label)
            if (wide > room) paint.textSize *= room / wide
            val baseline = (box.top + box.bottom) / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(option.label, (box.left + box.right) / 2f, baseline, paint)
        }

        paint.typeface = Typeface.DEFAULT
        paint.color = faded(Brand.INK, 0.88f)
        paint.textSize = height * HINT_SIZE
        val lines = wrapWords(content.hint, width * 0.90f, paint::measureText)
        val lineHeight = paint.textSize * 1.3f
        val buttonsBottom = boxes.maxOfOrNull { it.bottom } ?: (height * (BUTTONS_TOP + BUTTONS_HEIGHT))
        var baseline = buttonsBottom + (height - buttonsBottom - lines.size * lineHeight) / 2f + paint.textSize
        for (line in lines) {
            canvas.drawText(line, width / 2f, baseline, paint)
            baseline += lineHeight
        }
        return card
    }

    private fun faded(colour: Int, share: Float): Int =
        Color.argb((255 * share).toInt(), Color.red(colour), Color.green(colour), Color.blue(colour))

    companion object {
        private const val LOGO_TOP = 0.055f
        private const val LOGO_HEIGHT = 0.37f
        private const val LOGO_MAX_WIDTH = 0.60f
        private const val BUTTONS_TOP = 0.50f
        private const val BUTTONS_HEIGHT = 0.175f
        private const val BUTTON_WIDTH = 0.37f
        private const val BUTTON_GAP = 0.05f
        private const val LABEL_SIZE = 0.36f
        private const val HINT_SIZE = 0.062f

        /** Where the [count] buttons go on a [width] x [height] screen: one row, centred. */
        fun buttons(width: Int, height: Int, count: Int): List<Box> {
            if (count <= 0) return emptyList()
            val gap = width * BUTTON_GAP
            // Narrower buttons if there are more than the screen takes at full width.
            val each = minOf(width * BUTTON_WIDTH, (width * 0.92f - gap * (count - 1)) / count)
            val total = each * count + gap * (count - 1)
            val top = height * BUTTONS_TOP
            val bottom = top + height * BUTTONS_HEIGHT
            var left = (width - total) / 2f
            return List(count) {
                Box(left, top, left + each, bottom).also { left += each + gap }
            }
        }

        /**
         * Which option a touch at ([x], [y]) on a [width] x [height] screen showing [content]
         * falls on. Null between buttons, and on one that cannot be chosen.
         */
        fun choiceAt(width: Int, height: Int, content: Content, x: Int, y: Int): Choice? {
            val boxes = buttons(width, height, content.options.size)
            val slop = width * 0.015f
            content.options.forEachIndexed { index, option ->
                if (boxes[index].contains(x.toFloat(), y.toFloat(), slop)) {
                    return if (option.enabled && !option.current) option.choice else null
                }
            }
            return null
        }
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
