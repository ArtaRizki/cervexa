package com.idn.kmed.cervexa.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * PrintHelper — mengelola tiga opsi output untuk PDF rekam medis:
 *   1. [printPdf]      → cetak ke printer jaringan (internet/lokal) via PrintManager
 *   2. [downloadPdf]   → simpan ke folder Download perangkat
 *   3. [sharePdf]      → bagikan via intent chooser / share sheet
 *
 * PrintManager Android mendukung printer Mopria, AirPrint, dan IPP secara otomatis
 * selama perangkat terhubung ke jaringan yang sama dengan printer.
 */
object PrintHelper {

    /* ── 1. CETAK KE PRINTER JARINGAN ───────────────────────────────────── */

    /**
     * Buka dialog cetak sistem Android.
     * Printer Wi-Fi (Mopria/IPP) dan printer cloud otomatis terdeteksi.
     *
     * @param activity  Activity aktif (untuk context PrintManager)
     * @param pdfFile   File PDF yang sudah digenerate
     * @param jobName   Nama pekerjaan cetak (tampil di antrian printer)
     */
    fun printPdf(activity: Activity, pdfFile: File, jobName: String = "Cervexa Rekam Medis") {
        val pm = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: run {
            Toast.makeText(activity, "Layanan cetak tidak tersedia", Toast.LENGTH_SHORT).show()
            return
        }
        pm.print(jobName, PdfPrintDocumentAdapter(pdfFile), null)
    }

    /* ── 2. SIMPAN / DOWNLOAD PDF ───────────────────────────────────────── */

    /**
     * Simpan PDF ke folder Download perangkat.
     * Android 10+ : via MediaStore (tidak butuh WRITE_EXTERNAL_STORAGE)
     * Android 9-  : via File API langsung ke Environment.DIRECTORY_DOWNLOADS
     *
     * @return true jika berhasil tersimpan
     */
    fun downloadPdf(context: Context, pdfFile: File, displayName: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                resolver.openOutputStream(uri)!!.use { out ->
                    pdfFile.inputStream().use { it.copyTo(out) }
                }
                cv.clear()
                cv.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, cv, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val dest = File(dir, displayName)
                pdfFile.copyTo(dest, overwrite = true)
            }
            true
        }.getOrElse { e ->
            e.printStackTrace()
            false
        }
    }

    /* ── 3. BAGIKAN PDF ─────────────────────────────────────────────────── */

    /**
     * Tampilkan intent chooser untuk membagikan PDF.
     * Jika [targetPackage] diisi, coba share langsung ke aplikasi tersebut.
     * Jika aplikasi tidak terinstall, tampilkan dialog ke Play Store.
     */
    fun sharePdf(
        activity: Activity,
        pdfFile: File,
        targetPackage: String? = null,
        appLabel: String = "Aplikasi"
    ) {
        // Harus tersedia di file_paths.xml
        val uri: Uri = try {
            FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", pdfFile)
        } catch (e: Exception) {
            Toast.makeText(activity, "Tidak dapat membuka file: ${e.message}", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Laporan Rekam Medis Cervexa")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (targetPackage != null) `package` = targetPackage
        }

        if (targetPackage != null) {
            // Cek apakah app target terinstall
            val canHandle = activity.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
            if (!canHandle) {
                // Tawarkan buka Play Store
                com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle("$appLabel Belum Terpasang")
                    .setMessage("Aplikasi $appLabel belum terpasang di perangkat ini. Buka Play Store untuk memasangnya?")
                    .setPositiveButton("Buka Play Store") { _, _ ->
                        openPlayStore(activity, targetPackage)
                    }
                    .setNegativeButton("Batal", null)
                    .show()
                return
            }
        }

        activity.startActivity(
            if (targetPackage != null) intent
            else Intent.createChooser(intent, "Bagikan PDF")
        )
    }

    /* ── Play Store redirect ─────────────────────────────────────────────── */

    fun openPlayStore(context: Context, packageName: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /* ═══════════════════════════════════════════════════════════════════════
       Inner: PrintDocumentAdapter — melayani data PDF ke subsistem cetak
       ═══════════════════════════════════════════════════════════════════════ */

    private class PdfPrintDocumentAdapter(private val pdfFile: File) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttribs: PrintAttributes?,
            newAttribs: PrintAttributes,
            cancellationSignal: CancellationSignal,
            callback: LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal.isCanceled) {
                callback.onLayoutCancelled(); return
            }
            val info = PrintDocumentInfo.Builder(pdfFile.name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback.onLayoutFinished(info, oldAttribs != newAttribs)
        }

        override fun onWrite(
            pageRanges: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal,
            callback: WriteResultCallback
        ) {
            try {
                FileInputStream(pdfFile).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { out ->
                        input.copyTo(out)
                    }
                }
                if (cancellationSignal.isCanceled) {
                    callback.onWriteCancelled(); return
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
}