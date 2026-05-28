package com.idn.kmed.cervexa.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * AI inference pipeline that processes camera frames for abnormality detection.
 *
 * Uses a coroutine-based frame queue (Channel with capacity 3, DROP_OLDEST)
 * to manage incoming frames without blocking the UI thread. The pipeline
 * attempts TFLite inference via [ViaModelHelper] first, falling back to
 * [AcetowhiteDetector] if TFLite fails.
 *
 * Fallback chain:
 * 1. TFLite inference via [ViaModelHelper.detectAbnormality] → returns [AbnormalityResult.Detected]
 * 2. If TFLite throws → [AcetowhiteDetector.detect]
 * 3. If Acetowhite classification error → result has confidenceScore = -1
 * 4. If Acetowhite detection exception → return [AbnormalityResult.Error], disable AnalysisMode
 */
class AiDetector(
    private val context: Context,
    private val viaModelHelper: ViaModelHelper,
    private val acetowhiteDetector: AcetowhiteDetector,
    private val analysisModeManager: AnalysisModeManager
) {

    private val frameChannel = Channel<Bitmap>(
        capacity = 3,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var analysisJob: Job? = null

    private val _result = MutableStateFlow<AbnormalityResult>(AbnormalityResult.Idle)

    /** Observable result of the latest inference. */
    val result: StateFlow<AbnormalityResult> = _result.asStateFlow()

    /**
     * Starts the analysis consumer coroutine that processes frames from the channel.
     *
     * The consumer runs on [Dispatchers.Default] and continuously receives frames
     * from the channel, running inference on each one and emitting results to [result].
     *
     * @param scope The [CoroutineScope] to launch the consumer coroutine in
     */
    fun startAnalysis(scope: CoroutineScope) {
        // Cancel any existing job before starting a new one
        analysisJob?.cancel()

        analysisJob = scope.launch(Dispatchers.Default) {
            for (frame in frameChannel) {
                val analysisResult = analyzeImage(frame)
                _result.value = analysisResult
            }
        }
    }

    /**
     * Stops the analysis pipeline by cancelling the consumer job and closing the channel.
     *
     * After calling this method, no more frames will be processed and the channel
     * will reject new submissions.
     */
    fun stopAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        frameChannel.close()
    }

    /**
     * Submits a frame to the analysis queue.
     *
     * Uses [Channel.trySend] which is non-blocking. If the channel is full,
     * the oldest frame is dropped (DROP_OLDEST overflow strategy).
     *
     * @param bitmap The camera frame to analyze
     */
    fun submitFrame(bitmap: Bitmap) {
        frameChannel.trySend(bitmap)
    }

    /**
     * Analyzes a single image and returns the detection result.
     *
     * This method implements the fallback chain:
     * 1. Try TFLite inference via [ViaModelHelper] — now returns [AbnormalityResult.Detected] directly
     * 2. On TFLite exception → fall back to [AcetowhiteDetector]
     * 3. On Acetowhite classification error → result has confidenceScore = -1
     * 4. On Acetowhite detection exception → return [AbnormalityResult.Error] and disable AnalysisMode
     *
     * @param bitmap The image to analyze
     * @return [AbnormalityResult] containing the detection result
     */
    suspend fun analyzeImage(bitmap: Bitmap): AbnormalityResult {
        return try {
            // Primary: TFLite inference — returns AbnormalityResult.Detected directly
            viaModelHelper.detectAbnormality(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "TFLite inference failed, falling back to AcetowhiteDetector: ${e.message}")
            // Fallback: AcetowhiteDetector
            try {
                acetowhiteDetector.detect(bitmap)
            } catch (acetowhiteException: Exception) {
                Log.e(TAG, "AcetowhiteDetector also failed: ${acetowhiteException.message}")
                // Both TFLite and Acetowhite failed — disable AnalysisMode for this session
                analysisModeManager.deactivate()
                AbnormalityResult.Error(
                    message = "AI: ERROR",
                    errorCode = ERROR_CODE_ALL_DETECTORS_FAILED
                )
            }
        }
    }

    /**
     * Validates whether an image meets the minimum size requirements for analysis.
     *
     * @param width The image width in pixels
     * @param height The image height in pixels
     * @return Error message string if validation fails, null if the image is valid
     */
    fun validateImage(width: Int, height: Int): String? {
        return if (width < MIN_IMAGE_SIZE || height < MIN_IMAGE_SIZE) {
            "Gambar terlalu kecil untuk dianalisis (minimum ${MIN_IMAGE_SIZE}x${MIN_IMAGE_SIZE} piksel)"
        } else {
            null
        }
    }

    companion object {
        private const val TAG = "AiDetector"
        const val CLASSIFICATION_THRESHOLD = 0.5f
        const val MIN_IMAGE_SIZE = 64
        const val ERROR_CODE_ALL_DETECTORS_FAILED = -2
    }
}
