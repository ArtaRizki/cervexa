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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.idn.kmed.cervexa.R
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.graphics.scale

enum class MediaType { IMAGE, VIDEO }
data class MediaItem(val file: File, val type: MediaType)

private data class VideoPreview(val frame: Bitmap?, val durationText: String)

class ThumbAdapter(
    private val onClick: (MediaItem, Int) -> Unit

) : ListAdapter<MediaItem, ThumbAdapter.VH>(DIFF) {
    interface SelectionListener { fun onSelectionChanged(count: Int) }
    private val selected = mutableSetOf<String>()
    private var selectionMode = false
    var selectionListener: SelectionListener? = null
    var onStartSelectionRequested: (() -> Unit)? = null

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
                oldItem.file.absolutePath == newItem.file.absolutePath && oldItem.type == newItem.type

            override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
                oldItem.file.lastModified() == newItem.file.lastModified()
        }
    }

    fun toggleSelectPublic(item: MediaItem) = toggleSelect(item)

    // aktifkan stable IDs
    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long {
        val it = getItem(position)
        return (it.file.absolutePath + "|" + it.type.name).hashCode().toLong()
    }

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!enabled) selected.clear()
        notifyDataSetChanged()
        // -> tambahkan ini supaya top bar langsung sinkron
        selectionListener?.onSelectionChanged(selected.size)
    }

    fun getSelectedItems(): List<File> =
        currentList.filter { selected.contains(it.file.absolutePath) }.map { it.file }

    private fun toggleSelect(item: MediaItem) {
        val key = item.file.absolutePath
        if (selected.contains(key)) selected.remove(key) else selected.add(key)

        notifyItemChanged(currentList.indexOf(item))
    }

    private fun toggleSelectAt(position: Int) {
        if (position == RecyclerView.NO_POSITION || position !in 0 until itemCount) return
        val item = getItem(position)
        val key = item.file.absolutePath
        if (selected.contains(key)) selected.remove(key) else selected.add(key)

        // update 1 item + beritahu listener jumlah terbaru
        notifyItemChanged(position)
        selectionListener?.onSelectionChanged(selected.size)
    }

    // pool worker untuk decoding/metadata
    private val executor = Executors.newFixedThreadPool(2)

    // cache bitmap kecil (unit = byteCount)
    private val imageCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    // cache video preview (unit = jumlah entry)
    private val videoCache = object : LruCache<String, VideoPreview>(256) {}

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ivThumb: ImageView = v.findViewById(R.id.ivThumb)
        val ivPlay: ImageView = v.findViewById(R.id.ivPlay)
        val ivImg: ImageView = v.findViewById(R.id.ivImg)
        val bgVideoTitle: View = v.findViewById(R.id.bgVideoTitle)
        val tvTimeVideo: TextView = v.findViewById(R.id.tvTimeVideo)
        val cancelled = AtomicBoolean(false)
        val selOverlay: View? = v.findViewById(R.id.selOverlay)
        val selCheck: ImageView? = v.findViewById(R.id.selCheck)
        /*init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onClick(getItem(pos), pos)
                }
            }
        }*/
        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (selectionMode) {
                    toggleSelectAt(pos)
                } else {
                    onClick(getItem(pos), pos)
                }
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false

                // >>> jika belum mode pilih, minta Fragment mengaktifkan lebih dulu
                if (!selectionMode) {
                    onStartSelectionRequested?.invoke()
                    // toggle setelah mode aktif; pakai post agar eksekusi setelah Fragment mengganti menu & setSelectionMode(true)
                    itemView.post {
                        val p2 = bindingAdapterPosition
                        if (p2 != RecyclerView.NO_POSITION) toggleSelectAt(p2)
                    }
                } else {
                    toggleSelectAt(pos)
                }
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_thumb, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.cancelled.set(false)

        // reset tampilan
        holder.ivThumb.setImageDrawable(null)
        holder.tvTimeVideo.text = ""

        // setelah set thumbnail/durasi
        val isSel = selected.contains(item.file.absolutePath)
        holder.itemView.isActivated = isSel

        // kalau kamu menambahkan overlay/ceklist di item_thumb.xml:
        holder.selOverlay?.visibility = if (isSel) View.VISIBLE else View.GONE
        holder.selCheck?.visibility = if (isSel) View.VISIBLE else View.GONE

        when (item.type) {
            MediaType.IMAGE -> {
                holder.ivPlay.visibility = View.GONE
                holder.ivImg.visibility = View.VISIBLE
                holder.bgVideoTitle.visibility = View.GONE
                holder.tvTimeVideo.visibility = View.GONE

                val key = "IMG:${item.file.absolutePath}|${item.file.lastModified()}"
                imageCache.get(key)?.let { bmp ->
                    holder.ivThumb.setImageBitmap(bmp); return
                }

                executor.execute {
                    if (holder.cancelled.get()) return@execute
                    val bmp = decodeScaled(item.file, reqW = 480, reqH = 480)
                    if (bmp != null) imageCache.put(key, bmp)
                    if (!holder.cancelled.get()) {
                        holder.ivThumb.post {
                            val posNow = holder.bindingAdapterPosition
                            if (posNow != RecyclerView.NO_POSITION &&
                                getItemOrNull(posNow)?.file == item.file
                            ) {
                                holder.ivThumb.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            }

            MediaType.VIDEO -> {
                holder.ivPlay.visibility = View.VISIBLE
                holder.ivImg.visibility = View.GONE
                holder.bgVideoTitle.visibility = View.VISIBLE
                holder.tvTimeVideo.visibility = View.VISIBLE

                val vkey = "VID:${item.file.absolutePath}|${item.file.lastModified()}"
                videoCache.get(vkey)?.let { p ->
                    holder.ivThumb.setImageBitmap(p.frame)
                    holder.tvTimeVideo.text = p.durationText
                    return
                }

                executor.execute {
                    if (holder.cancelled.get()) return@execute
                    val preview = extractVideoPreview(item.file)
                    videoCache.put(vkey, preview)
                    if (!holder.cancelled.get()) {
                        holder.ivThumb.post {
                            val posNow = holder.bindingAdapterPosition
                            if (posNow != RecyclerView.NO_POSITION &&
                                getItemOrNull(posNow)?.file == item.file
                            ) {
                                holder.ivThumb.setImageBitmap(preview.frame)
                                holder.tvTimeVideo.text = preview.durationText
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.cancelled.set(true)
        super.onViewRecycled(holder)
    }

    /** Helper aman saat posisi berubah */
    private fun getItemOrNull(position: Int): MediaItem? =
        if (position in 0 until itemCount) getItem(position) else null

    /** --- Helpers --- */

    private fun decodeScaled(file: File, reqW: Int, reqH: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        var inSample = 1
        while (o.outWidth / inSample > reqW || o.outHeight / inSample > reqH) inSample *= 2
        val o2 = BitmapFactory.Options().apply {
            inSampleSize = inSample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, o2)
    }

    private fun extractVideoPreview(file: File): VideoPreview {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)

            // durasi
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val h = TimeUnit.MILLISECONDS.toHours(ms)
            val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
            val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
            val durationText = String.format("%02d:%02d:%02d", h, m, s)

            // frame di tengah durasi (fallback 1s)
            val targetUs = if (ms > 0) (ms / 2) * 1000L else 1_000_000L
            var frame = mmr.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame = frame?.let { thumbnailScale(it, 480, 480) }

            VideoPreview(frame, durationText)
        } catch (_: Exception) {
            VideoPreview(null, "00:00:00")
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    private fun thumbnailScale(src: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val ratio = minOf(maxW.toFloat() / src.width, maxH.toFloat() / src.height)
        return if (ratio >= 1f) src
        else src.scale((src.width * ratio).toInt(), (src.height * ratio).toInt())
    }
}