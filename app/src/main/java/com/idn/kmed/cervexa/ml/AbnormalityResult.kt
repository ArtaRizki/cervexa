package com.idn.kmed.cervexa.ml

import android.graphics.RectF

/**
 * Sealed class representing the result of AI abnormality detection.
 *
 * Variants:
 * - [Detected]: Inference completed successfully with classification result
 * - [Error]: Inference failed with an error
 * - [Idle]: No inference has been run yet
 */
sealed class AbnormalityResult {

    /**
     * Successful detection result containing classification, confidence, and optional bounding box.
     *
     * @param label Classification result (NORMAL or ABNORMAL)
     * @param confidenceScore Probability value in range [0.0, 1.0]
     * @param boundingBox Normalized coordinates of detected abnormal area, null if NORMAL
     * @param isFallback true if result was produced by AcetowhiteDetection fallback
     */
    data class Detected(
        val label: Classification,
        val confidenceScore: Float,
        val boundingBox: RectF?,
        val isFallback: Boolean = false
    ) : AbnormalityResult()

    /**
     * Error state when inference or detection fails.
     *
     * @param message Human-readable error description
     * @param errorCode Numeric error code, -1 by default
     */
    data class Error(
        val message: String,
        val errorCode: Int = -1
    ) : AbnormalityResult()

    /**
     * Idle state indicating no inference has been performed yet.
     */
    object Idle : AbnormalityResult()
}

/**
 * Classification labels for abnormality detection results.
 */
enum class Classification {
    NORMAL,
    ABNORMAL
}
