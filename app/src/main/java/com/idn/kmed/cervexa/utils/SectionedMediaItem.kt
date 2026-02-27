package com.idn.kmed.cervexa.utils

/**
 * Sealed class untuk item di RecyclerView yang sudah dibagi per seksi tanggal.
 *
 * - [Header]    → baris judul tanggal (mis. "Selasa, 25 Feb 2025")
 * - [Media]     → thumbnail foto/video seperti biasa
 */
sealed class SectionedMediaItem {

    data class Header(val dateLabel: String) : SectionedMediaItem()

    data class Media(val item: MediaItem) : SectionedMediaItem()
}
