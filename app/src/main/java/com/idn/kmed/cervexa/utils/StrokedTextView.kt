package com.idn.kmed.cervexa.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

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
