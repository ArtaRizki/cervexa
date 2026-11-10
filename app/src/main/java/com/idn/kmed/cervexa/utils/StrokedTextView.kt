package com.idn.kmed.cervexa.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.TextureView
import androidx.appcompat.widget.AppCompatTextView
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

// ponytail: two-pass stroke+fill TextView without mutating setTextColor() inside onDraw()
class StrokedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    override fun onDraw(canvas: Canvas) {
        val textLayout = layout
        if (textLayout != null && text.isNotEmpty()) {
            val prevColor = paint.color
            val prevStyle = paint.style
            val prevStrokeWidth = paint.strokeWidth
            val prevStrokeJoin = paint.strokeJoin
            val prevStrokeCap = paint.strokeCap

            canvas.save()
            canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = (textSize * 0.15f).coerceIn(3f, 8f)
            paint.color = Color.BLACK
            textLayout.draw(canvas)
            canvas.restore()

            paint.style = prevStyle
            paint.strokeWidth = prevStrokeWidth
            paint.strokeJoin = prevStrokeJoin
            paint.strokeCap = prevStrokeCap
            paint.color = prevColor
        }
        super.onDraw(canvas)
    }
}

/**
 * Smart detector that checks whether the camera's RTSP stream already has a built-in
 * yellow timestamp burned into the bottom-right corner.
 * - If built-in timestamp exists -> app hides its own timestamp overlay to avoid overlap.
 * - If built-in timestamp does NOT exist -> app shows and burns in its own timestamp at bottom-right.
 */
object CameraTimestampDetector {
    private var probeBitmap: Bitmap? = null

    fun currentTimestampText(): String {
        return if (android.os.Build.VERSION.SDK_INT >= 26) {
            ZonedDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd-yyyy/HH:mm:ss"))
        } else {
            SimpleDateFormat("MM-dd-yyyy/HH:mm:ss", Locale.getDefault()).format(Date())
        }
    }

    @Synchronized
    fun checkTextureView(tv: TextureView?): Boolean {
        if (tv == null || !tv.isAvailable || tv.width <= 0 || tv.height <= 0) return false
        return try {
            var bmp = probeBitmap
            if (bmp == null || bmp.isRecycled) {
                bmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
                probeBitmap = bmp
            }
            tv.getBitmap(bmp)
            hasBuiltInTimestamp(bmp)
        } catch (_: Throwable) {
            false
        }
    }

    fun hasBuiltInTimestamp(bmp: Bitmap): Boolean {
        if (bmp.isRecycled || bmp.width < 64 || bmp.height < 64) return false
        val w = bmp.width
        val h = bmp.height
        val x0 = (w * 0.66f).toInt()
        val x1 = (w * 0.99f).toInt()
        val y0 = (h * 0.925f).toInt()
        val y1 = (h * 0.995f).toInt()
        val stepY = ((y1 - y0) / 8).coerceAtLeast(1)
        val stepX = ((x1 - x0) / 80).coerceAtLeast(1)

        var yellowPx = 0
        var sharpEdges = 0

        var y = y0
        while (y < y1) {
            var prevYell = false
            var x = x0
            while (x < x1) {
                val px = bmp.getPixel(x, y)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val isYell = r >= 170 && g >= 170 && (r - b) >= 22 && (g - b) >= 22
                if (isYell) yellowPx++
                if (isYell != prevYell) sharpEdges++
                prevYell = isYell
                x += stepX
            }
            y += stepY
        }
        return yellowPx >= 12 && sharpEdges >= 16
    }
}
