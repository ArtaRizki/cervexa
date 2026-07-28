package com.idn.kmed.cervexa.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Renders AI detection overlay (bounding box, label, confidence score) on top of frame bitmaps.
 *
 * Used in both live stream (VideoFragmentMobile) and gallery (MediaPageFragment) contexts
 * to provide consistent visual feedback of AI abnormality detection results.
 *
 * Scaling is proportional to frame resolution to ensure readability across devices.
 */
class OverlayRenderer {

    companion object {
        // Color constants
        private const val COLOR_RED = 0xFFFF0000.toInt()       // #FF0000
        private const val COLOR_ORANGE = 0xFFFF8C00.toInt()    // #FF8C00
        private const val COLOR_GREEN = 0xFF00C853.toInt()     // #00C853

        // Proportional scaling factors
        private const val TEXT_SIZE_RATIO = 0.04f       // 4% of frame height
        private const val STROKE_WIDTH_RATIO = 0.005f   // 0.5% of frame width
        private const val PADDING_RATIO = 0.02f         // 2% of frame height
        private const val LABEL_BG_ALPHA = 160          // Background alpha for label
    }

    /**
     * Renders the AI detection overlay on top of the source bitmap.
     *
     * @param source The original frame bitmap
     * @param result The detection result containing label, score, and bounding box
     * @param includeTimestamp Whether to include a timestamp on the overlay
     * @return A new Bitmap with the overlay drawn on top
     */
    fun renderOverlay(
        source: Bitmap,
        result: AbnormalityResult.Detected,
        includeTimestamp: Boolean = false
    ): Bitmap {
        val width = source.width
        val height = source.height

        val overlay = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(overlay)

        val textSize = calculateTextSize(height)
        val strokeWidth = calculateStrokeWidth(width)
        val padding = height * PADDING_RATIO
        val labelColor = getLabelColor(result)
        val labelText = formatLabel(result)

        // Draw frame border based on classification color
        when (result.label) {
            Classification.ABNORMAL -> {
                drawFrameBorder(canvas, width, height, strokeWidth, labelColor)
            }
            Classification.NORMAL -> {
                drawFrameBorder(canvas, width, height, strokeWidth, COLOR_GREEN)
            }
        }

        val subText = when (result.label) {
            Classification.ABNORMAL -> "Harap lakukan pemeriksaan lebih lanjut"
            Classification.NORMAL -> ""
        }

        // Draw label with background
        drawLabel(canvas, labelText, subText, labelColor, textSize, padding, width)

        // Draw timestamp if requested
        if (includeTimestamp) {
            drawTimestamp(canvas, textSize, padding, width, height)
        }

        return overlay
    }

    /**
     * Renders an error message directly on the frame for debugging.
     */
    fun renderError(frame: Bitmap, errorMessage: String): Bitmap {
        val overlay = frame.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(overlay)
        val width = canvas.width
        val height = canvas.height

        val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = calculateTextSize(height) * 1.5f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        val paintBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80FF0000") // Semi-transparent red
            style = Paint.Style.FILL
        }

        canvas.drawRect(0f, 0f, width.toFloat(), calculateTextSize(height) * 3f, paintBox)
        canvas.drawText(errorMessage, width / 2f, calculateTextSize(height) * 1.5f, paintText)

        return overlay
    }

    /**
     * Calculates text size proportional to frame height.
     *
     * @param frameHeight The height of the frame in pixels
     * @return Text size in pixels
     */
    fun calculateTextSize(frameHeight: Int): Float {
        return frameHeight * TEXT_SIZE_RATIO
    }

    /**
     * Calculates stroke width proportional to frame width.
     *
     * @param frameWidth The width of the frame in pixels
     * @return Stroke width in pixels
     */
    fun calculateStrokeWidth(frameWidth: Int): Float {
        return frameWidth * STROKE_WIDTH_RATIO
    }

    /**
     * Formats the display label based on detection result.
     *
     * Format:
     * - ABNORMAL: "AI: ABNORMAL (XX%)" or "AI: ABNORMAL (XX%) (Acetowhite)" if fallback
     * - NORMAL: "AI: NORMAL (XX%)" or "AI: NORMAL (XX%) (Acetowhite)" if fallback
     *
     * Percentage calculation:
     * - ABNORMAL: round(confidenceScore * 100)
     * - NORMAL: round((1 - confidenceScore) * 100)
     *
     * @param result The detection result
     * @return Formatted label string
     */
    fun formatLabel(result: AbnormalityResult.Detected): String {
        val percentage = when (result.label) {
            Classification.ABNORMAL -> (result.confidenceScore * 100).roundToInt()
            Classification.NORMAL -> ((1 - result.confidenceScore) * 100).roundToInt()
        }

        val classLabel = when (result.label) {
            Classification.ABNORMAL -> "ABNORMAL"
            Classification.NORMAL -> "NORMAL"
        }

        val fallbackSuffix = if (result.isFallback) " (Acetowhite)" else ""

        return "AI: $classLabel ($percentage%)$fallbackSuffix"
    }

    /**
     * Determines the label color based on confidence score.
     *
     * - score > 0.75 → red (#FF0000)
     * - 0.5 < score ≤ 0.75 → orange (#FF8C00)
     * - score ≤ 0.5 → green (#00C853)
     *
     * @param result The detection result
     * @return Color int value
     */
    fun getLabelColor(result: AbnormalityResult.Detected): Int {
        return when {
            result.confidenceScore > 0.75f -> COLOR_RED
            result.confidenceScore > 0.5f -> COLOR_ORANGE
            else -> COLOR_GREEN
        }
    }

    /**
     * Draws a border around the entire frame indicating the classification result globally.
     */
    private fun drawFrameBorder(
        canvas: Canvas,
        frameWidth: Int,
        frameHeight: Int,
        strokeWidth: Float,
        color: Int
    ) {
        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            isAntiAlias = true
        }

        val halfStroke = strokeWidth / 2
        val borderRect = RectF(
            halfStroke,
            halfStroke,
            frameWidth - halfStroke,
            frameHeight - halfStroke
        )

        canvas.drawRect(borderRect, paint)
    }

    /**
     * Draws the classification label with a semi-transparent background.
     */
    private fun drawLabel(
        canvas: Canvas,
        text: String,
        subText: String,
        color: Int,
        textSize: Float,
        padding: Float,
        frameWidth: Int
    ) {
        val textPaint = Paint().apply {
            this.color = color
            this.textSize = textSize
            isAntiAlias = true
            isFakeBoldText = true
        }

        val subTextPaint = Paint().apply {
            this.color = Color.WHITE
            this.textSize = textSize * 0.7f
            isAntiAlias = true
        }

        val bgPaint = Paint().apply {
            this.color = Color.BLACK
            alpha = LABEL_BG_ALPHA
            style = Paint.Style.FILL
        }

        val textWidth = textPaint.measureText(text)
        val subTextWidth = if (subText.isNotEmpty()) subTextPaint.measureText(subText) else 0f
        val maxWidth = maxOf(textWidth, subTextWidth)

        val textHeight = textPaint.descent() - textPaint.ascent()
        val subTextHeight = if (subText.isNotEmpty()) subTextPaint.descent() - subTextPaint.ascent() else 0f

        // Position label at top-left with padding
        val labelX = padding
        val labelY = padding + textHeight
        val subLabelY = if (subText.isNotEmpty()) labelY + subTextHeight + (padding / 2) else labelY

        // Draw background rectangle
        val bgRect = RectF(
            labelX - padding / 2,
            labelY - textHeight - padding / 4,
            labelX + maxWidth + padding,
            if (subText.isNotEmpty()) subLabelY + padding / 4 else labelY + padding / 4
        )
        canvas.drawRect(bgRect, bgPaint)

        // Draw text
        canvas.drawText(text, labelX, labelY - textPaint.descent(), textPaint)
        if (subText.isNotEmpty()) {
            canvas.drawText(subText, labelX, subLabelY - subTextPaint.descent(), subTextPaint)
        }
    }

    /**
     * Draws a timestamp at the bottom-right of the frame.
     */
    private fun drawTimestamp(
        canvas: Canvas,
        textSize: Float,
        padding: Float,
        frameWidth: Int,
        frameHeight: Int
    ) {
        val timestampPaint = Paint().apply {
            color = Color.WHITE
            this.textSize = textSize * 0.7f
            isAntiAlias = true
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())

        val textWidth = timestampPaint.measureText(timestamp)
        val x = frameWidth - textWidth - padding
        val y = frameHeight - padding

        canvas.drawText(timestamp, x, y, timestampPaint)
    }
}
