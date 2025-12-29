package com.idn.kmed.cervexa.utils

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.concurrent.TimeUnit

data class MediaPreview(
    val frame: Bitmap?,        // thumbnail (bisa null kalau gagal)
    val durationMs: Long,      // durasi mentah (ms)
    val durationText: String   // durasi "HH:mm:ss"
)

/**
 * Ambil thumbnail & durasi video.
 * @param file File video
 * @param frameTimeUs default: di tengah durasi (kalau diketahui), fallback 1 detik.
 * @param frameOption gunakan OPTION_CLOSEST_SYNC agar lebih cepat/stabil di banyak file.
 */
fun extractPreview(file: File, frameTimeUs: Long? = null, frameOption: Int = MediaMetadataRetriever.OPTION_CLOSEST_SYNC): MediaPreview {
    var retriever: MediaMetadataRetriever? = null
    return try {
        retriever = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }

        // Durasi
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L

        // Waktu frame: default ambil tengah durasi, kalau durasi tak ada → 1 detik
        val targetUs = frameTimeUs ?: when {
            durationMs > 0 -> (durationMs / 2) * 1000L
            else -> 1_000_000L
        }

        val frame = retriever.getFrameAtTime(targetUs, frameOption)

        MediaPreview(
            frame = frame,
            durationMs = durationMs,
            durationText = formatDurationHms(durationMs)
        )
    } catch (_: Exception) {
        MediaPreview(frame = null, durationMs = 0L, durationText = "00:00:00")
    } finally {
        retriever?.release()
    }
}

private fun formatDurationHms(durationMs: Long): String {
    val h = TimeUnit.MILLISECONDS.toHours(durationMs)
    val m = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}
