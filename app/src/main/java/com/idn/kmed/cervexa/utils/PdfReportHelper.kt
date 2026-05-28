package com.idn.kmed.cervexa.utils

import android.graphics.*
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * PdfReportHelper — menghasilkan PDF rekam medis berbasis Android PdfDocument
 * (tanpa library eksternal).
 *
 * Dua mode:
 *  - [generatePatientPdf]  → ringkasan data pasien saja (tanpa gambar)
 *  - [generateSessionPdf]  → detail sesi + media (snapshot di-embed, video placeholder)
 */
object PdfReportHelper {

    /* ── Dimensi A4 dalam poin (72 dpi) ────────────────────────────────── */
    private const val PW = 595   // page width
    private const val PH = 842   // page height
    private const val M = 44f   // margin kiri/kanan/atas/bawah

    /* ── Warna brand ────────────────────────────────────────────────────── */
    private val COLOR_PRIMARY = Color.parseColor("#1976D2")
    private val COLOR_ACCENT = Color.parseColor("#0D47A1")
    private val COLOR_LIGHT_BG = Color.parseColor("#E3F2FD")
    private val COLOR_DIVIDER = Color.parseColor("#BBDEFB")
    private val COLOR_TEXT = Color.parseColor("#212121")
    private val COLOR_LABEL = Color.parseColor("#546E7A")
    private val COLOR_WHITE = Color.WHITE

    /* ── Paint factories ─────────────────────────────────────────────────── */
    private fun pFill(color: Int, sz: Float = 10f) = Paint().apply {
        style = Paint.Style.FILL
        this.color = color
        textSize = sz
        isAntiAlias = true
    }

    private fun pStroke(color: Int, w: Float = 1f) = Paint().apply {
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = w
        isAntiAlias = true
    }

    private fun pText(color: Int, sz: Float, bold: Boolean = false) = Paint().apply {
        this.color = color
        textSize = sz
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        isAntiAlias = true
    }

    /* ════════════════════════════════════════════════════════════════════
       PUBLIC API
       ════════════════════════════════════════════════════════════════════ */

    /**
     * PDF ringkasan data pasien (tanpa gambar media).
     * @return File PDF sementara, null jika gagal.
     */
    fun generatePatientPdf(
        outputFile: File,
        nama: String,
        nik: String,
        hospitalName: String,
        nrm: String?,
        dobUtcMs: Long?,
        sessionId: Int,
        sessionCode: String?,
        startedAt: String?,
        completedAt: String?,
        snapshotCount: Int,
        videoCount: Int
    ): File? = runCatching {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, 1).create())
        val cv = page.canvas
        var y = M

        y = drawHeader(cv, y, "LAPORAN DATA PASIEN", hospitalName)
        y = drawPatientBlock(cv, y, nama, nik, hospitalName, nrm, dobUtcMs)
        y = drawSessionBlock(
            cv,
            y,
            sessionId,
            sessionCode,
            startedAt,
            completedAt,
            snapshotCount,
            videoCount
        )
        drawFooter(cv)

        doc.finishPage(page)
        FileOutputStream(outputFile).use { doc.writeTo(it) }
        doc.close()
        outputFile
    }.getOrNull()

    /**
     * PDF detail sesi lengkap termasuk thumbnail snapshot.
     * Otomatis menambah halaman jika konten melebihi satu halaman.
     */
    fun generateSessionPdf(
        outputFile: File,
        nama: String,
        nik: String,
        hospitalName: String,
        nrm: String?,
        dobUtcMs: Long?,
        sessionId: Int,
        sessionCode: String?,
        startedAt: String?,
        completedAt: String?,
        snapshotFiles: List<File>,
        videoFiles: List<File>,
        aiClassification: String? = null,
        aiConfidenceScore: Float? = null,
        aiIsFallback: Boolean = false
    ): File? = runCatching {
        val doc = PdfDocument()
        var pageNo = 1

        // Helper: buat halaman baru
        fun newPage(): Pair<PdfDocument.Page, Canvas> {
            val pg = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, pageNo++).create())
            return pg to pg.canvas
        }

        var (page, cv) = newPage()
        var y = M

        y = drawHeader(cv, y, "LAPORAN DATA SESI PEMERIKSAAN", hospitalName)
        y = drawPatientBlock(cv, y, nama, nik, hospitalName, nrm, dobUtcMs)
        y = drawSessionBlock(
            cv, y, sessionId, sessionCode, startedAt, completedAt,
            snapshotFiles.size, videoFiles.size
        )

        /* ── Section: AI Detection Results ────────────────────────────── */
        if (aiClassification != null && aiConfidenceScore != null) {
            y = drawAiResultBlock(cv, y, aiClassification, aiConfidenceScore, aiIsFallback)
        }

        /* ── Section: Media ───────────────────────────────────────────── */
        if (snapshotFiles.isNotEmpty() || videoFiles.isNotEmpty()) {
            y += 12f
            y = drawSectionTitle(cv, y, "MEDIA")

            /* Snapshot — grid 2 kolom */
            val imgW = ((PW - 2 * M - 12) / 2).toInt()
            val imgH = (imgW * 3 / 4)   // rasio 4:3
            var col = 0

            for (snap in snapshotFiles) {
                // Butuh ruang untuk 1 baris gambar + label
                val needed = imgH + 24f + 8f
                if (y + needed > PH - M - 20) {
                    drawFooter(cv)
                    doc.finishPage(page)
                    val np = newPage(); page = np.first; cv = np.second; y = M
                }

                val x = M + col * (imgW + 12)
                drawSnapshotTile(cv, snap, x.toInt(), y.toInt(), imgW, imgH)

                if (col == 1 || snapshotFiles.indexOf(snap) == snapshotFiles.lastIndex) {
                    y += imgH + 24f + 8f
                    col = 0
                } else col++
            }

            /* Video placeholder */
            for (vid in videoFiles) {
                val needed = 56f + 8f
                if (y + needed > PH - M - 20) {
                    drawFooter(cv)
                    doc.finishPage(page)
                    val np = newPage(); page = np.first; cv = np.second; y = M
                }
                y = drawVideoPlaceholder(cv, y, vid)
            }
        }

        drawFooter(cv)
        doc.finishPage(page)
        FileOutputStream(outputFile).use { doc.writeTo(it) }
        doc.close()
        outputFile
    }.getOrNull()

    /* ════════════════════════════════════════════════════════════════════
       DRAW HELPERS
       ════════════════════════════════════════════════════════════════════ */

    private fun drawHeader(cv: Canvas, y: Float, title: String, hospitalName: String): Float {
        var yy = y

        /* Header bar */
        cv.drawRect(0f, 0f, PW.toFloat(), 72f, pFill(COLOR_PRIMARY))

        /* Logo area — lingkaran putih */
        cv.drawCircle(M + 20f, 36f, 20f, pFill(COLOR_WHITE))
        cv.drawText("C", M + 13f, 41f, pText(COLOR_PRIMARY, 22f, true))

        /* App name */
        cv.drawText("CERVEXA", M + 50f, 32f, pText(COLOR_WHITE, 16f, true))
        cv.drawText("Medical Imaging System", M + 50f, 50f, pText(Color.parseColor("#BBDEFB"), 9f))

        /* Kanan: tanggal cetak */
        val dateTxt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("id", "ID")).format(Date())
        val dW = pText(COLOR_WHITE, 9f).measureText("Dicetak: $dateTxt")
        cv.drawText("Dicetak: $dateTxt", PW - M - dW, 50f, pText(COLOR_WHITE, 9f))

        yy = 84f

        /* Garis warna accent */
        cv.drawRect(0f, 72f, PW.toFloat(), 76f, pFill(COLOR_ACCENT))

        /* Judul dokumen */
        yy += 4f
        cv.drawText(title, M, yy + 18f, pText(COLOR_ACCENT, 13f, true))
        yy += 28f

        /* Nama RS */
        cv.drawText(hospitalName.ifBlank { "—" }, M, yy, pText(COLOR_LABEL, 10f))
        yy += 16f

        /* Divider */
        cv.drawLine(M, yy, PW - M, yy, pStroke(COLOR_DIVIDER, 1.5f))
        yy += 14f

        return yy
    }

    private fun drawPatientBlock(
        cv: Canvas, y: Float,
        nama: String, nik: String, hospitalName: String,
        nrm: String?, dobUtcMs: Long?
    ): Float {
        var yy = y
        yy = drawSectionTitle(cv, yy, "DATA PASIEN")

        val rows = mutableListOf(
            "Nama Pasien" to nama.ifBlank { "—" },
            "NIK" to nik.ifBlank { "—" },
            "No. Rekam Medis (NRM)" to nrm.orEmpty().ifBlank { "—" },
            "Tanggal Lahir (DOB UTC)" to if ((dobUtcMs ?: -1L) > 0) {
                val sdf = SimpleDateFormat("dd MMMM yyyy", Locale("id", "ID"))
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.format(Date(dobUtcMs!!)) + " UTC"
            } else "—",
            "Nama Rumah Sakit" to hospitalName.ifBlank { "—" }
        )
        yy = drawTable(cv, yy, rows)
        return yy
    }

    private fun drawSessionBlock(
        cv: Canvas, y: Float,
        sessionId: Int, sessionCode: String?,
        startedAt: String?, completedAt: String?,
        snapshotCount: Int, videoCount: Int
    ): Float {
        var yy = y + 8f
        yy = drawSectionTitle(cv, yy, "DATA SESI")

        fun fmtDate(raw: String?): String {
            if (raw.isNullOrBlank()) return "—"
            return runCatching {
                val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US)
                    .also { it.timeZone = TimeZone.getTimeZone("UTC") }.parse(raw)
                    ?: return "—"
                SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("id", "ID"))
                    .also { it.timeZone = TimeZone.getTimeZone("Asia/Jakarta") }
                    .format(iso) + " WIB"
            }.getOrDefault(raw)
        }

        val rows = listOf(
            "ID Sesi" to if (sessionId > 0) sessionId.toString() else "—",
            "Kode Sesi" to sessionCode.orEmpty().ifBlank { "—" },
            "Waktu Mulai" to fmtDate(startedAt),
            "Waktu Selesai" to fmtDate(completedAt),
            "Status" to "Selesai",
            "Jumlah Snapshot" to "$snapshotCount berkas",
            "Jumlah Video" to "$videoCount berkas"
        )
        yy = drawTable(cv, yy, rows)
        return yy
    }

    /** Gambar baris tabel 2 kolom: Label | Nilai */
    private fun drawTable(cv: Canvas, y: Float, rows: List<Pair<String, String>>): Float {
        var yy = y
        val colW = (PW - 2 * M)
        val col1 = colW * 0.38f
        val rowH = 20f
        val pLbl = pText(COLOR_LABEL, 9f)
        val pVal = pText(COLOR_TEXT, 9.5f, bold = true)

        rows.forEachIndexed { i, (label, value) ->
            val bgColor = if (i % 2 == 0) COLOR_WHITE else COLOR_LIGHT_BG
            cv.drawRect(M, yy, PW - M, yy + rowH, pFill(bgColor))
            cv.drawText(label, M + 6f, yy + 13f, pLbl)
            cv.drawText(value, M + col1 + 6f, yy + 13f, pVal)
            // border bawah
            cv.drawLine(M, yy + rowH, PW - M, yy + rowH, pStroke(COLOR_DIVIDER, 0.5f))
            yy += rowH
        }
        // border luar
        cv.drawRect(M, y, PW - M, yy, pStroke(COLOR_DIVIDER, 1f))
        return yy + 6f
    }

    private fun drawSectionTitle(cv: Canvas, y: Float, title: String): Float {
        cv.drawRect(M, y, PW - M, y + 20f, pFill(COLOR_PRIMARY))
        cv.drawText(title, M + 8f, y + 14f, pText(COLOR_WHITE, 9f, true))
        return y + 24f
    }

    private fun drawSnapshotTile(cv: Canvas, file: File, x: Int, y: Int, w: Int, h: Int) {
        val bmp = runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = false }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }.getOrNull()

        if (bmp != null) {
            val dst = Rect(x, y, x + w, y + h)
            // Crop ke tengah (center crop)
            val src = centerCropRect(bmp.width, bmp.height, w, h)
            cv.drawBitmap(bmp, src, dst, null)
            bmp.recycle()
        } else {
            cv.drawRect(
                x.toFloat(),
                y.toFloat(),
                (x + w).toFloat(),
                (y + h).toFloat(),
                pFill(COLOR_LIGHT_BG)
            )
            cv.drawText("Gambar tidak tersedia", x + 8f, y + h / 2f, pText(COLOR_LABEL, 8f))
        }
        // Border
        cv.drawRect(
            x.toFloat(),
            y.toFloat(),
            (x + w).toFloat(),
            (y + h).toFloat(),
            pStroke(COLOR_DIVIDER, 1f)
        )
        // Filename label
        val fname = file.name.take(28)
        cv.drawRect(
            x.toFloat(),
            (y + h).toFloat(),
            (x + w).toFloat(),
            (y + h + 20f),
            pFill(COLOR_LIGHT_BG)
        )
        cv.drawText(fname, x + 4f, y + h + 13f, pText(COLOR_LABEL, 7f))
    }

    private fun centerCropRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        val scale = maxOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        val scaledW = (srcW * scale).toInt()
        val scaledH = (srcH * scale).toInt()
        val left = (scaledW - dstW) / 2
        val top = (scaledH - dstH) / 2
        val rW = (dstW / scale).toInt()
        val rH = (dstH / scale).toInt()
        val rL = ((srcW - rW) / 2)
        val rT = ((srcH - rH) / 2)
        return Rect(rL, rT, rL + rW, rT + rH)
    }

    private fun drawVideoPlaceholder(cv: Canvas, y: Float, file: File): Float {
        val h = 52f
        val rect = RectF(M, y, PW - M, y + h)
        cv.drawRoundRect(rect, 6f, 6f, pFill(Color.parseColor("#37474F")))
        // Icon teks
        cv.drawText("▶", M + 12f, y + h / 2f + 5f, pText(COLOR_WHITE, 18f))
        // Nama file
        cv.drawText("VIDEO: ${file.name}", M + 36f, y + h / 2f - 4f, pText(COLOR_WHITE, 9f, true))
        // Ukuran file
        val sizeTxt = "${file.length() / 1024} KB"
        cv.drawText(sizeTxt, M + 36f, y + h / 2f + 10f, pText(Color.parseColor("#B0BEC5"), 8f))
        return y + h + 8f
    }

    private fun drawAiResultBlock(
        cv: Canvas, y: Float,
        classification: String,
        confidenceScore: Float,
        isFallback: Boolean
    ): Float {
        var yy = y + 8f
        yy = drawSectionTitle(cv, yy, "HASIL ANALISIS AI")

        val displayPercentage = if (classification == "ABNORMAL") {
            "${Math.round(confidenceScore * 100)}%"
        } else {
            "${Math.round((1 - confidenceScore) * 100)}%"
        }

        val methodLabel = if (isFallback) "Acetowhite Detection (Fallback)" else "VIA Model (TFLite)"

        val rows = listOf(
            "Klasifikasi" to classification,
            "Confidence Score" to displayPercentage,
            "Metode Analisis" to methodLabel
        )
        yy = drawTable(cv, yy, rows)
        return yy
    }

    private fun drawFooter(cv: Canvas) {
        val footY = PH - 28f
        cv.drawLine(M, footY, PW - M, footY, pStroke(COLOR_DIVIDER, 0.8f))
        cv.drawText(
            "Cervexa Medical Imaging System — Dokumen ini dicetak secara otomatis",
            M, footY + 14f, pText(COLOR_LABEL, 7f)
        )
        val timeStr = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("id", "ID")).format(Date())
        val tw = pText(COLOR_LABEL, 7f).measureText(timeStr)
        cv.drawText(timeStr, PW - M - tw, footY + 14f, pText(COLOR_LABEL, 7f))
    }
}