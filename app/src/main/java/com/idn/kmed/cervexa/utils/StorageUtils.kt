package com.idn.kmed.cervexa.utils

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object StorageUtils {

    private val tzJakarta = TimeZone.getTimeZone("Asia/Jakarta")

    /** Folder tanggal harian: yyyy-MM-dd (WIB). */
    fun todayDateFolderWIB(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale("id", "ID")).apply { timeZone = tzJakarta }
        return sdf.format(Date())
    }

    /** Base: /Android/data/<pkg>/files/Pictures/Scans/ */
    fun getBaseScanDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File(base, "Scans").apply { if (!exists()) mkdirs() }
    }

    /** Pastikan folder sesi (tanggal/pasien) ada. */
    fun ensureSessionDir(context: Context, dateFolder: String, patientFolder: String): File {
        val dateDir = File(getBaseScanDir(context), dateFolder).apply { if (!exists()) mkdirs() }
        return File(dateDir, patientFolder).apply { if (!exists()) mkdirs() }
    }

    /** Simpan bitmap JPEG ke sessionDir dengan nama result_<ts>.jpg (WIB). */
    fun saveJpeg(sessionDir: File, bitmap: Bitmap, quality: Int = 100): File {
        val out = File(sessionDir, "result_${timestampWIB()}.jpg")
        FileOutputStream(out).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)
            fos.flush()
        }
        return out
    }

    /** Tulis metadata sesi sebagai JSON kecil. */
    fun writeSessionMetadata(sessionDir: File, json: String) {
        File(sessionDir, "session.json").writeText(json)
    }

    /** Timestamp untuk nama file: yyyyMMdd_HHmmss (WIB). */
    fun timestampWIB(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = tzJakarta }
        return sdf.format(Date())
    }

    fun ensureChildDir(parent: File, name: String): File =
        File(parent, name).apply { if (!exists()) mkdirs() }

    fun saveJpegWithPrefix(targetDir: File, bitmap: Bitmap, prefix: String = "img", quality: Int = 100): File {
        val out = File(targetDir, "${prefix}_${timestampWIB()}.jpg")
        FileOutputStream(out).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)
            fos.flush()
        }
        return out
    }
}