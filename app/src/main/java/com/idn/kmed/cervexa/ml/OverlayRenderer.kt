package com.idn.kmed.cervexa.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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

        // Draw bounding box or border based on classification
        when (result.label) {
            Classification.ABNORMAL -> {
                val box = result.boundingBox ?: RectF(0.28f, 0.28f, 0.72f, 0.72f)
                drawBoundingBox(canvas, box, width, height, strokeWidth, labelColor)
            }
            Classification.NORMAL -> {
                drawNormalBorder(canvas, width, height, strokeWidth)
            }
        }

        // Draw label with background
        drawLabel(canvas, labelText, labelColor, textSize, padding, width)

        // Draw timestamp if requested
        if (includeTimestamp) {
            drawTimestamp(canvas, textSize, padding, width, height)
        }

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
     * Draws a bounding box around the detected abnormal area.
     * Coordinates in boundingBox are normalized (0-1) and scaled to frame dimensions.
     */
    private fun drawBoundingBox(
        canvas: Canvas,
        boundingBox: RectF,
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

        // Scale normalized coordinates to frame dimensions
        val scaledRect = RectF(
            boundingBox.left * frameWidth,
            boundingBox.top * frameHeight,
            boundingBox.right * frameWidth,
            boundingBox.bottom * frameHeight
        )

        canvas.drawRect(scaledRect, paint)
    }

    /**
     * Draws a green border around the entire frame for NORMAL classification.
     */
    private fun drawNormalBorder(
        canvas: Canvas,
        frameWidth: Int,
        frameHeight: Int,
        strokeWidth: Float
    ) {
        val paint = Paint().apply {
            color = COLOR_GREEN
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

        val bgPaint = Paint().apply {
            this.color = Color.BLACK
            alpha = LABEL_BG_ALPHA
            style = Paint.Style.FILL
        }

        val textWidth = textPaint.measureText(text)
        val textHeight = textPaint.descent() - textPaint.ascent()

        // Position label at top-left with padding
        val labelX = padding
        val labelY = padding + textHeight

        // Draw background rectangle
        val bgRect = RectF(
            labelX - padding / 2,
            labelY - textHeight - padding / 4,
            labelX + textWidth + padding / 2,
            labelY + padding / 4
        )
        canvas.drawRect(bgRect, bgPaint)

        // Draw text
        canvas.drawText(text, labelX, labelY - textPaint.descent(), textPaint)
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
