package com.idn.kmed.cervexa.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Environment
import android.text.TextPaint
import android.text.TextUtils
import com.idn.kmed.cervexa.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object StorageUtils {

    const val KEY_SHOW_APP_TIMESTAMP = "show_app_timestamp"

    private val tzJakarta = TimeZone.getTimeZone("Asia/Jakarta")
    private val overlayTimestampFmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)
    }

    fun isAppTimestampEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(
            context.getString(R.string.pref_application),
            Context.MODE_PRIVATE
        )
        return prefs.getBoolean(KEY_SHOW_APP_TIMESTAMP, true)
    }

    fun setAppTimestampEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(
            context.getString(R.string.pref_application),
            Context.MODE_PRIVATE
        )
        prefs.edit().putBoolean(KEY_SHOW_APP_TIMESTAMP, enabled).apply()
    }

    fun formatOverlayTimestamp(millis: Long = System.currentTimeMillis()): String {
        val fmt = overlayTimestampFmt.get() ?: SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date(millis))
    }

    /** Menggambar baris watermark RS/NRM (kiri-bawah) dan timestamp aplikasi opsional (kanan-bawah). */
    fun drawOverlayWatermarkRow(
        canvas: Canvas,
        frameWidth: Int,
        frameHeight: Int,
        leftText: String,
        paintFill: Paint,
        paintStroke: Paint,
        showRightTimestamp: Boolean,
        rightText: String = if (showRightTimestamp) formatOverlayTimestamp() else ""
    ) {
        val sideMargin = (frameWidth * 0.008f).coerceAtLeast(6f)
        val bottomMargin = (frameHeight * 0.004f).coerceAtLeast(2f)
        val baselineY = frameHeight - bottomMargin

        var maxLeftW = (frameWidth * 0.55f - sideMargin).coerceAtLeast(0f)
        if (showRightTimestamp && rightText.isNotBlank()) {
            val tsW = paintFill.measureText(rightText)
            val tsX = (frameWidth - sideMargin - tsW).coerceAtLeast(frameWidth * 0.50f)
            canvas.drawText(rightText, tsX, baselineY, paintStroke)
            canvas.drawText(rightText, tsX, baselineY, paintFill)
            maxLeftW = (tsX - sideMargin * 2f).coerceAtLeast(frameWidth * 0.30f)
        }

        val infoDraw = if (paintFill.measureText(leftText) > maxLeftW && maxLeftW > 0f) {
            TextUtils.ellipsize(leftText, TextPaint(paintFill), maxLeftW, TextUtils.TruncateAt.END).toString()
        } else {
            leftText
        }
        canvas.drawText(infoDraw, sideMargin, baselineY, paintStroke)
        canvas.drawText(infoDraw, sideMargin, baselineY, paintFill)
    }

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