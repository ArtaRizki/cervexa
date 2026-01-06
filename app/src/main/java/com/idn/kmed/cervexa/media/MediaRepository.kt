package com.idn.kmed.cervexa.media

import android.content.Context
import com.idn.kmed.cervexa.utils.StorageUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import com.idn.kmed.cervexa.utils.MediaItem
import com.idn.kmed.cervexa.utils.MediaType
import org.json.JSONObject


data class SessionItem(
    val dateDir: File,          // folder TANGGAL (yyyy-MM-dd)
    val patientDir: File,       // folder pasien di dalam dateDir
    val thumb: MediaItem,       // thumbnail terakhir (foto / video)
    val lastTs: Long,           // timestamp terakhir media di sesi ini
    val monthKey: String,       // untuk sticky header: "Oktober 2025"

    // metadata pasien
    val nama: String?,
    val nik: String?,
    val nrm: String?,
    val rs: String?,
    val dobUtc: Long?           // sama konsepnya dengan PatientItem.dobUtcMs
)
class MediaRepository(private val context: Context) {

    private val loc = Locale("id", "ID")
    private val monthFmt = SimpleDateFormat("MMMM yyyy", loc)

    // --- struct kecil internal buat parsing meta ---
    private data class SessionMeta(
        val nama: String? = null,
        val nik: String? = null,
        val nrm: String? = null,
        val rs: String? = null,
        val dobUtc: Long? = null
    )

    /**
     * Baca metadata pasien dari:
     * 1) session.json (preferensi utama)
     * 2) fallback nama folder "NIK_NAMA_USIA"
     */
    private fun readSessionMeta(patientDir: File): SessionMeta {
        // 1) coba dari JSON
        runCatching {
            val jsonFile = File(patientDir, "session.json")
            if (jsonFile.exists()) {
                val o = JSONObject(jsonFile.readText())

                return SessionMeta(
                    nama = o.optString("nama", null),
                    nik = o.optString("nik", null),
                    nrm = o.optString("nrm", null),
                    rs = o.optString("rs", null),
                    dobUtc = o.optLong("dob_utc").takeIf { it > 0L }
                )
            }
        }

        // 2) fallback dari nama folder pasien: "NIK_NAMA_USIA"
        val folder = patientDir.name
        val parts = folder.split("_")

        val nik = parts.getOrNull(0)
        val nama = parts.drop(1).dropLast(1)
            .joinToString(" ")
            .replace('_', ' ')
            .trim()
            .ifBlank { null }

        return SessionMeta(
            nama = nama,
            nik = nik,
            nrm = null,
            rs = null,
            dobUtc = null
        )
    }

    /**
     * Kumpulkan semua session (per folder tanggal / pasien)
     */
    fun collectAllSessions(): List<SessionItem> {
        val base = StorageUtils.getBaseScanDir(context)
        if (!base.exists()) return emptyList()

        val sessions = mutableListOf<SessionItem>()

        base.listFiles()?.forEach { dateDir ->
            if (!dateDir.isDirectory) return@forEach

            dateDir.listFiles()?.forEach { patientDir ->
                if (!patientDir.isDirectory) return@forEach

                val snapDir = File(patientDir, "Snapshots")
                val vidDir = File(patientDir, "Video")

                val lastImg = snapDir
                    .listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
                    ?.maxByOrNull { it.lastModified() }

                val lastVid = vidDir
                    .listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
                    ?.maxByOrNull { it.lastModified() }

                val chosen: MediaItem? = when {
                    lastImg != null -> MediaItem(lastImg, MediaType.IMAGE)
                    lastVid != null -> MediaItem(lastVid, MediaType.VIDEO)
                    else -> null
                }

                if (chosen != null) {
                    val ts = chosen.file.lastModified()
                    val key = monthFmt.format(Date(ts))

                    val meta = readSessionMeta(patientDir)

                    sessions += SessionItem(
                        dateDir = dateDir,
                        patientDir = patientDir,
                        thumb = chosen,
                        lastTs = ts,
                        monthKey = key,
                        nama = meta.nama,
                        nik = meta.nik,
                        nrm = meta.nrm,
                        rs = meta.rs,
                        dobUtc = meta.dobUtc
                    )
                }
            }
        }

        // urutkan dari sesi terbaru
        return sessions.sortedByDescending { it.lastTs }
    }

    // --- cache in-memory supaya nggak baca file tiap kali ---
    private var cached: List<SessionItem>? = null

    fun loadPage(offset: Int, limit: Int): List<SessionItem> {
        val data = cached ?: collectAllSessions().also { cached = it }
        if (offset >= data.size) return emptyList()
        val end = (offset + limit).coerceAtMost(data.size)
        return data.subList(offset, end)
    }

    /**
     * Search bisa pakai:
     * - nama
     * - NIK
     * - NRM
     * - RS
     * - nama folder tanggal (yyyy-MM-dd)
     */
    fun searchSessions(query: String): List<SessionItem> {
        val data = cached ?: collectAllSessions().also { cached = it }

        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) return data

        return data.filter { sess ->
            val fields = listOfNotNull(
                sess.nama,
                sess.nik,
                sess.nrm,
                sess.rs,
                sess.dateDir.name // "yyyy-MM-dd"
            )

            fields.any { field ->
                field.lowercase(Locale.getDefault()).contains(q)
            }
        }
    }

    fun totalCount(): Int =
        (cached ?: collectAllSessions().also { cached = it }).size

    fun listMediaInSession(session: SessionItem): List<MediaItem> {
        val imgs = File(session.patientDir, "Snapshots")
            .listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
            ?.map { MediaItem(it, MediaType.IMAGE) }
            .orEmpty()

        val vids = File(session.patientDir, "Video")
            .listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.map { MediaItem(it, MediaType.VIDEO) }
            .orEmpty()

        return (imgs + vids).sortedByDescending { it.file.lastModified() }
    }

    fun getRelatedSessions(anchor: SessionItem): List<SessionItem> {
        // Pastikan cache sudah terisi
        val allData = cached ?: collectAllSessions().also { cached = it }

        // 1. Tentukan kunci identitas dari item yang diklik
        val anchorKey = anchor.nik?.takeIf { it.isNotBlank() }
            ?: anchor.nama?.takeIf { it.isNotBlank() }
            ?: anchor.patientDir.name

        // 2. Cari semua item lain yang punya kunci identitas sama
        return allData.filter { item ->
            val itemKey = item.nik?.takeIf { it.isNotBlank() }
                ?: item.nama?.takeIf { it.isNotBlank() }
                ?: item.patientDir.name

            itemKey == anchorKey
        }
    }

    fun invalidate() {
        cached = null
    }
}
