package com.idn.kmed.cervexa.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

// ponytail: two-pass stroke+fill TextView for live stream watermark visibility over white/bright tissue
class StrokedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var isDrawing = false

    override fun invalidate() {
        if (!isDrawing) super.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        isDrawing = true
        try {
            val fillColor = currentTextColor
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = (textSize * 0.15f).coerceIn(3f, 8f)
            setTextColor(Color.BLACK)
            super.onDraw(canvas)

            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
            setTextColor(fillColor)
            super.onDraw(canvas)
        } finally {
            isDrawing = false
        }
    }
}
