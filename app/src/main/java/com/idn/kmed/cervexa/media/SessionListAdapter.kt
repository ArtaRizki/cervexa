package com.idn.kmed.cervexa.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.scale
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.utils.MediaItem
import com.idn.kmed.cervexa.utils.MediaType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.idn.kmed.cervexa.media.SessionItem

/** Baris pada list: Header bulan / Item sesi */
sealed class SessRow {
    data class Header(val key: String) : SessRow()           // ex: "Juli 2025"
    data class Item(val sess: SessionItem) : SessRow()
}

/** Adapter list sesi dengan sticky month header */
class SessionListAdapter(
    private val onSessionClick: (SessionItem) -> Unit,
    private val onMoreClick: (SessionItem) -> Unit      // titik-tiga → bottom sheet
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), StickyHeaderProvider {

    companion object {
        private const val TYPE_HEADER = 1
        private const val TYPE_ITEM = 2
    }

    private val rows = mutableListOf<SessRow>()

    // decoding worker + caches
    private val executor = Executors.newFixedThreadPool(2)
    private val imgCache = object : android.util.LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val vidCache = object : android.util.LruCache<String, Bitmap>(128) {}

    init {
        setHasStableIds(true)
    }

    /** Tambahkan batch (lazy load) dan gabungkan ke rows dengan DiffUtil */
    fun append(batch: List<SessionItem>) {
        if (batch.isEmpty()) return
        val old = rows.toList()

        // 1. Ambil item yang SUDAH ada di list saat ini (existing)
        val currentItems = rows.filterIsInstance<SessRow.Item>().map { it.sess }

        // 2. Gabung dengan batch baru yang didapat dari repo
        val allRawItems = currentItems + batch

        // 3. LOGIKA BARU: Grouping by Patient Identity
        // Tujuannya: Jika NIK sama (atau Nama sama), jadikan satu item saja (ambil yang terbaru)
//        val distinctPatients = allRawItems
//            .groupBy { item ->
//                // Kunci unik: Prioritaskan NIK(GAJADI), jika kosong pakai Nama, jika kosong pakai nama folder
//                val uniqueKey =
//                    item.nama?.takeIf { it.isNotBlank() }
//                        ?: item.patientDir.name
//                uniqueKey
//            }
//            .map { (_, sessions) ->
//                // Dari beberapa sesi milik pasien yang sama, ambil yang 'lastTs' (timestamp) paling besar/baru
//                sessions.maxByOrNull { it.lastTs }!!
//            }
        val distinctPatients = allRawItems
            .distinctBy { it.patientDir.absolutePath }

        // 4. Lanjutkan logika grouping by Month (seperti kode asli) menggunakan list yang sudah disaring
        val grouped = distinctPatients.groupBy { it.monthKey }
        val monthOrder = grouped.keys.sortedByDescending { k -> grouped[k]!!.maxOf { it.lastTs } }

        val newRows = mutableListOf<SessRow>() // Gunakan mutableList baru
        for (m in monthOrder) {
            // Tambahkan Header Bulan
            newRows += SessRow.Header(m)

            // Tambahkan Item Pasien (yang sudah unique)
            // Urutkan per item descending (yang terbaru paling atas dalam grup bulannya)
            val itemsInMonth = grouped[m]!!.sortedByDescending { it.lastTs }

            itemsInMonth.forEach { item ->
                newRows += SessRow.Item(item)
            }
        }

        // 5. Hitung Diff dan update UI
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newRows.size

            override fun areItemsTheSame(o: Int, n: Int): Boolean {
                val a = old[o]
                val b = newRows[n]
                return when {
                    a is SessRow.Header && b is SessRow.Header -> a.key == b.key
                    // Cek kesamaan berdasarkan direktori file pasien (unique path)
                    a is SessRow.Item && b is SessRow.Item -> a.sess.patientDir == b.sess.patientDir
                    else -> false
                }
            }

            override fun areContentsTheSame(o: Int, n: Int): Boolean {
                val a = old[o]
                val b = newRows[n]
                // Cek konten, terutama timestamp, kalau timestamp berubah berarti ada update data baru
                return a == b || (a is SessRow.Item && b is SessRow.Item && a.sess.lastTs == b.sess.lastTs)
            }
        })

        rows.clear()
        rows.addAll(newRows)
        diff.dispatchUpdatesTo(this)
    }

    fun reset() {
        val c = rows.size
        rows.clear()
        if (c > 0) notifyItemRangeRemoved(0, c)
    }

    // ---------- StickyHeaderProvider ----------
    override fun isHeader(position: Int): Boolean =
        rows.getOrNull(position) is SessRow.Header

    override fun getHeaderText(position: Int): String = when (val r = rows.getOrNull(position)) {
        is SessRow.Header -> r.key
        is SessRow.Item -> r.sess.monthKey
        else -> ""
    }

    // ---------- RecyclerView.Adapter ----------
    override fun getItemViewType(position: Int) =
        if (rows[position] is SessRow.Header) TYPE_HEADER else TYPE_ITEM

    override fun getItemCount(): Int = rows.size

    override fun getItemId(position: Int): Long = when (val r = rows[position]) {
        is SessRow.Header -> ("H|" + r.key).hashCode().toLong()
        is SessRow.Item -> ("I|" + r.sess.patientDir.absolutePath).hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER)
            HeaderVH(inf.inflate(R.layout.item_month_header, parent, false))
        else
            ItemVH(inf.inflate(R.layout.item_media_row, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is SessRow.Header -> (holder as HeaderVH).bind(r.key)
            is SessRow.Item -> (holder as ItemVH).bind(
                s = r.sess,
                click = onSessionClick,
                moreClick = onMoreClick,
                executor = executor,
                imgCache = imgCache,
                vidCache = vidCache
            )
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ItemVH) holder.onRecycled()
        super.onViewRecycled(holder)
    }

    // ---------- ViewHolders ----------
    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(text: String) {
            (itemView as TextView).text = text
        }
    }

    class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
        private val ivThumb: ImageView = v.findViewById(R.id.ivThumb)
        private val tvTitle: TextView = v.findViewById(R.id.tvTitle)
        private val tvNik: TextView = v.findViewById(R.id.tvSub1)
        private val tvTs: TextView = v.findViewById(R.id.tvSub2)
        private val btnMore: ImageButton = v.findViewById(R.id.btnMore)
        private val cancelled = AtomicBoolean(false)

        fun bind(
            s: SessionItem,
            click: (SessionItem) -> Unit,
            moreClick: (SessionItem) -> Unit,
            executor: java.util.concurrent.Executor,
            imgCache: android.util.LruCache<String, Bitmap>,
            vidCache: android.util.LruCache<String, Bitmap>
        ) {
            cancelled.set(false)
            itemView.setOnClickListener { click(s) }

            tvTitle.text = s.nama ?: s.patientDir.name
            tvNik.text = s.nik ?: "—"
            tvTs.text = formatTs(s.lastTs) // "yyyy-MM-dd, HH:mm"

            btnMore.setOnClickListener { moreClick(s) }

            // cache key per file + lastTs
            val key = (if (s.thumb.type == MediaType.IMAGE) "IMG:" else "VID:") +
                    s.thumb.file.absolutePath + "|" + s.lastTs

            (if (s.thumb.type == MediaType.IMAGE) imgCache.get(key) else vidCache.get(key))
                ?.let { ivThumb.setImageBitmap(it); return }

            executor.execute {
                if (cancelled.get()) return@execute
                val bmp = if (s.thumb.type == MediaType.IMAGE)
                    decodeScaled(s.thumb.file, 480, 480)
                else
                    extractVideoFrame(s.thumb.file)
                if (bmp != null) {
                    if (s.thumb.type == MediaType.IMAGE) imgCache.put(key, bmp) else vidCache.put(
                        key,
                        bmp
                    )
                }
                ivThumb.post { if (!cancelled.get()) ivThumb.setImageBitmap(bmp) }
            }
        }

        fun onRecycled() {
            cancelled.set(true)
        }
    }
}

/* ==================== Helpers ==================== */

private fun formatTs(ts: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(ts))

private fun decodeScaled(file: File, reqW: Int, reqH: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, o)
    var s = 1
    while (o.outWidth / s > reqW || o.outHeight / s > reqH) s *= 2
    val o2 = BitmapFactory.Options().apply {
        inSampleSize = s
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(file.absolutePath, o2)
}

private fun extractVideoFrame(file: File): Bitmap? {
    val mmr = MediaMetadataRetriever()
    return try {
        mmr.setDataSource(file.absolutePath)
        val ms =
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val us = if (ms > 0) (ms / 2) * 1000L else 1_000_000L
        mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { src ->
            val r = minOf(480f / src.width, 480f / src.height)
            if (r >= 1f) src else src.scale((src.width * r).toInt(), (src.height * r).toInt())
        }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { mmr.release() }
    }
}