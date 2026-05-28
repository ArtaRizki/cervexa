package com.idn.kmed.cervexa.ml

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

/**
 * Fallback detector that identifies acetowhite regions in cervical images
 * using pixel color analysis.
 *
 * Acetowhite areas (white/bright patches) are characteristic of pre-cancerous
 * lesions when viewed after acetic acid application (VIA method).
 *
 * This detector samples the center region (25%–75% of width and height) of the
 * bitmap at every [SAMPLE_STEP] pixels and classifies pixels as acetowhite based
 * on RGB channel thresholds and inter-channel difference constraints.
 *
 * Confidence mapping: min(acetowhiteRatio / [ABNORMAL_RATIO_THRESHOLD], 1.0)
 * Classification: score > 0.5 → ABNORMAL, else → NORMAL
 */
class AcetowhiteDetector {

    /**
     * Detects acetowhite regions in the given bitmap.
     *
     * @param bitmap The input image to analyze
     * @return [AbnormalityResult.Detected] with isFallback = true
     * @throws Exception if pixel sampling fails (propagated for AiDetector to handle)
     */
    fun detect(bitmap: Bitmap): AbnormalityResult.Detected {
        val width = bitmap.width
        val height = bitmap.height

        // Sampling area tengah (fokus ke serviks): 25%–75% width & height
        val startX = (width * 0.25).toInt()
        val endX = (width * 0.75).toInt()
        val startY = (height * 0.25).toInt()
        val endY = (height * 0.75).toInt()

        var acetowhitePixels = 0
        var totalSampledPixels = 0

        // Sampling setiap SAMPLE_STEP piksel untuk performa
        for (y in startY until endY step SAMPLE_STEP) {
            for (x in startX until endX step SAMPLE_STEP) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if (isAcetowhitePixel(r, g, b)) {
                    acetowhitePixels++
                }
                totalSampledPixels++
            }
        }

        if (totalSampledPixels == 0) {
            return AbnormalityResult.Detected(
                label = Classification.NORMAL,
                confidenceScore = 0f,
                boundingBox = null,
                isFallback = true
            )
        }

        val acetowhiteRatio = acetowhitePixels.toFloat() / totalSampledPixels.toFloat()
        val confidenceScore = (acetowhiteRatio / ABNORMAL_RATIO_THRESHOLD).coerceAtMost(1.0f)

        return try {
            classify(confidenceScore)
        } catch (e: Exception) {
            Log.e(TAG, "Classification error: ${e.message}")
            // Handle classification error: return confidenceScore = -1
            AbnormalityResult.Detected(
                label = Classification.NORMAL,
                confidenceScore = -1f,
                boundingBox = null,
                isFallback = true
            )
        }
    }

    /**
     * Classifies the detection result based on confidence score.
     *
     * @param confidenceScore The computed confidence score
     * @return [AbnormalityResult.Detected] with appropriate classification
     */
    private fun classify(confidenceScore: Float): AbnormalityResult.Detected {
        val label = if (confidenceScore > CLASSIFICATION_THRESHOLD) {
            Classification.ABNORMAL
        } else {
            Classification.NORMAL
        }

        return AbnormalityResult.Detected(
            label = label,
            confidenceScore = confidenceScore,
            boundingBox = null,
            isFallback = true
        )
    }

    /**
     * Determines if a pixel meets acetowhite criteria.
     *
     * Acetowhite pixel criteria:
     * - R > 150 AND G > 150 AND B > 130
     * - |R - G| < 45 AND |G - B| < 45
     */
    private fun isAcetowhitePixel(r: Int, g: Int, b: Int): Boolean {
        return r > MIN_R &&
                g > MIN_G &&
                b > MIN_B &&
                Math.abs(r - g) < MAX_CHANNEL_DIFF &&
                Math.abs(g - b) < MAX_CHANNEL_DIFF
    }

    companion object {
        private const val TAG = "AcetowhiteDetector"

        const val MIN_R = 150
        const val MIN_G = 150
        const val MIN_B = 130
        const val MAX_CHANNEL_DIFF = 45
        const val ABNORMAL_RATIO_THRESHOLD = 0.15f
        const val SAMPLE_STEP = 5
        const val CLASSIFICATION_THRESHOLD = 0.5f
    }
}
