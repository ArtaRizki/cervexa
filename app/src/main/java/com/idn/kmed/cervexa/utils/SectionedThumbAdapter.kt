package com.idn.kmed.cervexa.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.scale
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.idn.kmed.cervexa.R
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// ⚠️ Sengaja diberi nama berbeda dari VideoPreview di ThumbAdapter.kt
// supaya tidak terjadi redeclaration conflict di package yang sama.
private data class SectionedVideoPreview(val frame: Bitmap?, val durationText: String)

/**
 * Adapter bergrid yang menyisipkan header tanggal di antara kelompok media.
 *
 * Cara pasang:
 *   val glm = GridLayoutManager(ctx, SPAN)
 *   glm.spanSizeLookup = adapter.buildSpanSizeLookup()
 *   rv.layoutManager = glm
 *   rv.adapter = adapter
 */
class SectionedThumbAdapter(
    private val spanCount: Int = 4,
    private val onItemClick: (item: MediaItem, indexInMediaOnly: Int) -> Unit
) : ListAdapter<SectionedMediaItem, RecyclerView.ViewHolder>(DIFF) {

    // ── selection state ────────────────────────────────────────────────────
    private val selected = mutableSetOf<String>()
    private var selectionMode = false

    var onStartSelectionRequested: (() -> Unit)? = null
    var selectionListener: SelectionListener? = null

    interface SelectionListener {
        fun onSelectionChanged(count: Int)
    }

    // ── async decode ───────────────────────────────────────────────────────
    private val executor = Executors.newFixedThreadPool(2)
    private val imageCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val videoCache = object : LruCache<String, SectionedVideoPreview>(256) {}

    // ── view types ─────────────────────────────────────────────────────────
    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_MEDIA = 1

        private val DIFF = object : DiffUtil.ItemCallback<SectionedMediaItem>() {
            override fun areItemsTheSame(a: SectionedMediaItem, b: SectionedMediaItem) = when {
                a is SectionedMediaItem.Header && b is SectionedMediaItem.Header ->
                    a.dateLabel == b.dateLabel

                a is SectionedMediaItem.Media && b is SectionedMediaItem.Media ->
                    a.item.file.absolutePath == b.item.file.absolutePath

                else -> false
            }

            override fun areContentsTheSame(a: SectionedMediaItem, b: SectionedMediaItem) = when {
                a is SectionedMediaItem.Header && b is SectionedMediaItem.Header ->
                    a.dateLabel == b.dateLabel

                a is SectionedMediaItem.Media && b is SectionedMediaItem.Media ->
                    a.item.file.lastModified() == b.item.file.lastModified()

                else -> false
            }
        }
    }

    override fun getItemViewType(position: Int) =
        if (getItem(position) is SectionedMediaItem.Header) TYPE_HEADER else TYPE_MEDIA

    // ── SpanSizeLookup: header full-width, media 1 sel ─────────────────────
    fun buildSpanSizeLookup() = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int) =
            if (getItemViewType(position) == TYPE_HEADER) spanCount else 1
    }

    // ── ViewHolders ────────────────────────────────────────────────────────

    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvDate: TextView = v.findViewById(R.id.tvSectionDate)
    }

    /** Field identik dengan item_thumb.xml */
    inner class MediaVH(v: View) : RecyclerView.ViewHolder(v) {
        val ivThumb: ImageView = v.findViewById(R.id.ivThumb)
        val ivPlay: ImageView = v.findViewById(R.id.ivPlay)
        val ivImg: ImageView = v.findViewById(R.id.ivImg)
        val bgVideoTitle: View = v.findViewById(R.id.bgVideoTitle)
        val tvTimeVideo: TextView = v.findViewById(R.id.tvTimeVideo)
        val selOverlay: View? = v.findViewById(R.id.selOverlay)
        val selCheck: ImageView? = v.findViewById(R.id.selCheck)
        val cancelled = AtomicBoolean(false)

        init {
            v.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val media = (getItem(pos) as? SectionedMediaItem.Media) ?: return@setOnClickListener
                if (selectionMode) toggleSelectAt(pos, media.item)
                else onItemClick(media.item, mediaIndexAt(pos))
            }
            v.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                val media = (getItem(pos) as? SectionedMediaItem.Media)
                    ?: return@setOnLongClickListener false
                if (!selectionMode) {
                    onStartSelectionRequested?.invoke()
                    // post supaya mode aktif lebih dulu sebelum toggle
                    v.post {
                        val p2 = bindingAdapterPosition
                        if (p2 == RecyclerView.NO_POSITION) return@post
                        val m2 = (getItem(p2) as? SectionedMediaItem.Media) ?: return@post
                        toggleSelectAt(p2, m2.item)
                    }
                } else {
                    toggleSelectAt(pos, media.item)
                }
                true
            }
        }
    }

    // ── onCreateViewHolder ─────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER)
            HeaderVH(inflater.inflate(R.layout.item_section_header, parent, false))
        else
            MediaVH(inflater.inflate(R.layout.item_thumb, parent, false))
    }

    // ── onBindViewHolder ───────────────────────────────────────────────────

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val sItem = getItem(position)) {
            is SectionedMediaItem.Header -> (holder as HeaderVH).tvDate.text = sItem.dateLabel
            is SectionedMediaItem.Media -> bindMedia(holder as MediaVH, sItem.item)
        }
    }

    private fun bindMedia(vh: MediaVH, item: MediaItem) {
        vh.cancelled.set(false)
        vh.ivThumb.setImageDrawable(null)
        vh.tvTimeVideo.text = ""

        val isSel = selected.contains(item.file.absolutePath)
        vh.itemView.isActivated = isSel
        vh.selOverlay?.visibility = if (isSel) View.VISIBLE else View.GONE
        vh.selCheck?.visibility = if (isSel) View.VISIBLE else View.GONE

        when (item.type) {
            MediaType.IMAGE -> bindImage(vh, item)
            MediaType.VIDEO -> bindVideo(vh, item)
        }
    }

    private fun bindImage(vh: MediaVH, item: MediaItem) {
        vh.ivPlay.visibility = View.GONE
        vh.ivImg.visibility = View.VISIBLE
        vh.bgVideoTitle.visibility = View.GONE
        vh.tvTimeVideo.visibility = View.GONE

        val key = "IMG:${item.file.absolutePath}|${item.file.lastModified()}"
        imageCache.get(key)?.let { vh.ivThumb.setImageBitmap(it); return }

        executor.execute {
            if (vh.cancelled.get()) return@execute
            val bmp = decodeScaled(item.file, 480, 480)
            if (bmp != null) imageCache.put(key, bmp)
            if (!vh.cancelled.get()) {
                vh.ivThumb.post {
                    val posNow = vh.bindingAdapterPosition
                    if (posNow != RecyclerView.NO_POSITION &&
                        (getItem(posNow) as? SectionedMediaItem.Media)?.item?.file == item.file
                    ) vh.ivThumb.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun bindVideo(vh: MediaVH, item: MediaItem) {
        vh.ivPlay.visibility = View.VISIBLE
        vh.ivImg.visibility = View.GONE
        vh.bgVideoTitle.visibility = View.VISIBLE
        vh.tvTimeVideo.visibility = View.VISIBLE

        val key = "VID:${item.file.absolutePath}|${item.file.lastModified()}"
        videoCache.get(key)?.let {
            vh.ivThumb.setImageBitmap(it.frame)
            vh.tvTimeVideo.text = it.durationText
            return
        }

        executor.execute {
            if (vh.cancelled.get()) return@execute
            val preview = extractVideoPreview(item.file)
            videoCache.put(key, preview)
            if (!vh.cancelled.get()) {
                vh.ivThumb.post {
                    val posNow = vh.bindingAdapterPosition
                    if (posNow != RecyclerView.NO_POSITION &&
                        (getItem(posNow) as? SectionedMediaItem.Media)?.item?.file == item.file
                    ) {
                        vh.ivThumb.setImageBitmap(preview.frame)
                        vh.tvTimeVideo.text = preview.durationText
                    }
                }
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is MediaVH) holder.cancelled.set(true)
        super.onViewRecycled(holder)
    }

    // ── selection helpers ──────────────────────────────────────────────────

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!enabled) selected.clear()
        notifyDataSetChanged()
        selectionListener?.onSelectionChanged(selected.size)
    }

    fun toggleSelectPublic(item: MediaItem) {
        val pos = currentList.indexOfFirst {
            it is SectionedMediaItem.Media && it.item.file.absolutePath == item.file.absolutePath
        }
        if (pos != -1) toggleSelectAt(pos, item)
    }

    private fun toggleSelectAt(position: Int, item: MediaItem) {
        val key = item.file.absolutePath
        if (selected.contains(key)) selected.remove(key) else selected.add(key)
        notifyItemChanged(position)
        selectionListener?.onSelectionChanged(selected.size)
    }

    /** File-file yang terpilih */
    fun getSelectedItems(): List<File> =
        currentList
            .filterIsInstance<SectionedMediaItem.Media>()
            .filter { selected.contains(it.item.file.absolutePath) }
            .map { it.item.file }

    /** Semua MediaItem tanpa header — untuk MediaPagerActivity */
    fun getAllMediaItems(): List<MediaItem> =
        currentList.filterIsInstance<SectionedMediaItem.Media>().map { it.item }

    /**
     * Hitung index di antara media saja (header tidak dihitung),
     * supaya posisi di MediaPagerActivity tetap akurat.
     */
    private fun mediaIndexAt(position: Int): Int =
        currentList.subList(0, position + 1).count { it is SectionedMediaItem.Media } - 1

    // ── decode helpers ─────────────────────────────────────────────────────

    private fun decodeScaled(file: File, reqW: Int, reqH: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        var inSample = 1
        while (o.outWidth / inSample > reqW || o.outHeight / inSample > reqH) inSample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = inSample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    private fun extractVideoPreview(file: File): SectionedVideoPreview {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val h = TimeUnit.MILLISECONDS.toHours(ms)
            val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
            val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
            val dur = String.format("%02d:%02d:%02d", h, m, s)
            val targetUs = if (ms > 0) (ms / 2) * 1000L else 1_000_000L
            val frame = mmr.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.let { thumbnailScale(it, 480, 480) }
            SectionedVideoPreview(frame, dur)
        } catch (_: Exception) {
            SectionedVideoPreview(null, "00:00:00")
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun thumbnailScale(src: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val ratio = minOf(maxW.toFloat() / src.width, maxH.toFloat() / src.height)
        return if (ratio >= 1f) src
        else src.scale((src.width * ratio).toInt(), (src.height * ratio).toInt())
    }
}