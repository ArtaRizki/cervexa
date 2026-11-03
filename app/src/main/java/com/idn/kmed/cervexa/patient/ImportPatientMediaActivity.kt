package com.idn.kmed.cervexa.patient

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.gallery.SessionMediaActivity
import com.idn.kmed.cervexa.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ImportPatientMediaActivity : AppCompatActivity() {

    private lateinit var tilNama: TextInputLayout
    private lateinit var tilNik: TextInputLayout
    private lateinit var tilRS: TextInputLayout
    private lateinit var tilDob: TextInputLayout
    private lateinit var tilNrm: TextInputLayout

    private lateinit var etNama: TextInputEditText
    private lateinit var etNik: TextInputEditText
    private lateinit var etDob: TextInputEditText
    private lateinit var etRS: TextInputEditText
    private lateinit var etNrm: TextInputEditText

    private lateinit var btnSelectPhotos: Button
    private lateinit var loadingOverlay: View
    private lateinit var tvLoadingMsg: TextView

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("id", "ID")).apply {
        timeZone = TimeZone.getTimeZone("Asia/Jakarta")
    }
    private var selectedDobUtcMs: Long? = null

    // Photo picker modern (Android 13+ / Google Play Services)
    private val pickMultipleMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            processImportedUris(uris)
        } else {
            Toast.makeText(this, "Tidak ada foto yang dipilih", Toast.LENGTH_SHORT).show()
        }
    }

    // Fallback file picker untuk perangkat lawas / emulator tanpa GMS Photo Picker
    private val pickMultipleGetContent = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            processImportedUris(uris)
        } else {
            Toast.makeText(this, "Tidak ada foto yang dipilih", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_patient_media)

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        toolbar.setNavigationOnClickListener { finish() }

        tilNama = findViewById(R.id.tilNama)
        tilNik = findViewById(R.id.tilNik)
        tilDob = findViewById(R.id.tilDob)
        tilRS = findViewById(R.id.tilRS)
        tilNrm = findViewById(R.id.tilNrm)

        etNama = findViewById(R.id.etNama)
        etNik = findViewById(R.id.etNik)
        etDob = findViewById(R.id.etDob)
        etRS = findViewById(R.id.etRS)
        etNrm = findViewById(R.id.etNrm)

        btnSelectPhotos = findViewById(R.id.btnSelectPhotos)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        tvLoadingMsg = findViewById(R.id.tvLoadingMsg)

        setupDateInput()

        btnSelectPhotos.setOnClickListener {
            currentFocus?.clearFocus()
            if (validate()) {
                launchPhotoPicker()
            }
        }
    }

    private fun launchPhotoPicker() {
        try {
            pickMultipleMedia.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (e: Exception) {
            Log.w("ImportPatientMedia", "PickMultipleVisualMedia failed, using fallback GetMultipleContents", e)
            pickMultipleGetContent.launch("image/*")
        }
    }

    private fun processImportedUris(uris: List<Uri>) {
        val nama = etNama.text?.toString()?.trim().orEmpty()
        val nik = etNik.text?.toString()?.trim().orEmpty()
        val rs = etRS.text?.toString()?.trim().orEmpty()
        val nrm = etNrm.text?.toString()?.trim().orEmpty()
        val dobUtc = selectedDobUtcMs ?: -1L

        loadingOverlay.visibility = View.VISIBLE
        tvLoadingMsg.text = "Mengimpor ${uris.size} foto pemeriksaan..."
        btnSelectPhotos.isEnabled = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dateFolder = StorageUtils.todayDateFolderWIB()
                    val sanitizedNama = nama.replace(" ", "_")
                    val patientFolder = "${nik}_${sanitizedNama}"

                    val sessionDir = StorageUtils.ensureSessionDir(applicationContext, dateFolder, patientFolder)
                    val snapshotsDir = StorageUtils.ensureChildDir(sessionDir, "Snapshots")
                    StorageUtils.ensureChildDir(sessionDir, "Video")

                    val timestampFormatted = if (android.os.Build.VERSION.SDK_INT >= 26) {
                        java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
                    } else {
                        SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())
                    }

                    val baseTimestamp = StorageUtils.timestampWIB()
                    var importedCount = 0

                    uris.forEachIndexed { index, uri ->
                        val targetFile = File(snapshotsDir, "result_${baseTimestamp}_${index + 1}.jpg")
                        var saved = false

                        // Decode, crop to 16:9 (hilangkan hitam-hitam), dan bubuhkan watermark (RS, RM, Timestamp)
                        try {
                            contentResolver.openInputStream(uri)?.use { stream ->
                                val bmp = BitmapFactory.decodeStream(stream)
                                if (bmp != null) {
                                    val cropped = cropTo16By9(bmp)
                                    val watermarked = applyWatermarkOverlay(cropped, rs, nrm, timestampFormatted)
                                    FileOutputStream(targetFile).use { fos ->
                                        watermarked.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                                        fos.flush()
                                    }
                                    if (cropped !== bmp && !cropped.isRecycled) cropped.recycle()
                                    if (watermarked !== cropped && !watermarked.isRecycled) watermarked.recycle()
                                    bmp.recycle()
                                    saved = true
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("ImportPatientMedia", "Decode and watermark failed, falling back to direct byte copy", e)
                        }

                        // Fallback: direct byte copy jika BitmapFactory gagal decode
                        if (!saved) {
                            try {
                                contentResolver.openInputStream(uri)?.use { input ->
                                    FileOutputStream(targetFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                saved = true
                            } catch (e: Exception) {
                                Log.e("ImportPatientMedia", "Failed to copy uri: $uri", e)
                            }
                        }

                        if (saved) {
                            importedCount++
                        }
                    }

                    // Tulis session.json yang kompatibel 100% dengan MediaRepository
                    val jsonFile = File(sessionDir, "session.json")
                    val meta = JSONObject().apply {
                        put("nama", nama)
                        put("nik", nik)
                        put("nrm", nrm)
                        put("rs", rs)
                        put("dob_utc", dobUtc)
                        put("saved_at", System.currentTimeMillis())
                    }
                    jsonFile.writeText(meta.toString(2))

                    Triple(sessionDir, dateFolder, importedCount)
                }
            }

            loadingOverlay.visibility = View.GONE
            btnSelectPhotos.isEnabled = true

            result.onSuccess { (sessionDir, dateFolder, count) ->
                Toast.makeText(
                    this@ImportPatientMediaActivity,
                    "Berhasil mengimpor $count foto pemeriksaan",
                    Toast.LENGTH_SHORT
                ).show()

                // Langsung buka SessionMediaActivity agar dokter bisa review & analisis AI
                val allPaths = arrayListOf(sessionDir.absolutePath)
                startActivity(Intent(this@ImportPatientMediaActivity, SessionMediaActivity::class.java).apply {
                    putExtra("sessionDirPath", sessionDir.absolutePath)
                    putExtra("patientName", nama)
                    putExtra("patient_nama", nama)
                    putExtra("patientNik", nik)
                    putExtra("patient_nik", nik)
                    putExtra("patientRs", rs)
                    putExtra("patient_rs", rs)
                    putExtra("patientNrm", nrm)
                    putExtra("patient_nrm", nrm)
                    putExtra("patientDobUtc", dobUtc)
                    putExtra("patient_dob_utc", dobUtc)
                    putExtra("dateStr", dateFolder)
                    putStringArrayListExtra("allSessionPaths", allPaths)
                })

                finish()
            }.onFailure { err ->
                Log.e("ImportPatientMedia", "Error importing media", err)
                Toast.makeText(
                    this@ImportPatientMediaActivity,
                    "Gagal mengimpor foto: ${err.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupDateInput() {
        etDob.apply {
            inputType = InputType.TYPE_NULL
            keyListener = null
            isCursorVisible = false
            isFocusableInTouchMode = false
            showSoftInputOnFocus = false
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) post { showDatePicker() } }
        }
        val openPicker = { etDob.post { showDatePicker() } }
        etDob.setOnClickListener { openPicker() }
        tilDob.setEndIconOnClickListener { openPicker() }
        etDob.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_UP) openPicker()
                true
            } else false
        }
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance().apply { selectedDobUtcMs?.let { timeInMillis = it } }
        DatePickerDialog(
            this, R.style.BlueDatePickerDialog,
            { _, y, m, d ->
                val c = Calendar.getInstance().apply { set(y, m, d) }
                selectedDobUtcMs = c.timeInMillis
                etDob.setText(dateFormat.format(c.time))
                tilDob.error = null
                etRS.requestFocus()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.minDate =
                Calendar.getInstance().apply { add(Calendar.YEAR, -130) }.timeInMillis
            show()
            getButton(DatePickerDialog.BUTTON_POSITIVE)
                ?.setBackgroundColor(Color.parseColor("#1E63E4"))
            getButton(DatePickerDialog.BUTTON_POSITIVE)
                ?.setTextColor(Color.parseColor("#FFFFFF"))
            getButton(DatePickerDialog.BUTTON_NEGATIVE)
                ?.setBackgroundColor(Color.parseColor("#FFFFFF"))
            getButton(DatePickerDialog.BUTTON_NEGATIVE)
                ?.setTextColor(Color.parseColor("#1E63E4"))
        }
    }

    private fun validate(): Boolean {
        var ok = true

        val nama = etNama.text?.toString()?.trim().orEmpty()
        if (nama.isEmpty()) {
            tilNama.error = "Nama wajib diisi"; ok = false
        } else tilNama.error = null

        val nik = etNik.text?.toString()?.trim().orEmpty()
        when {
            nik.isEmpty() -> {
                tilNik.error = "NIK wajib diisi"; ok = false
            }
            nik.length != 16 || !nik.all { it.isDigit() } -> {
                tilNik.error = "NIK harus 16 digit angka"; ok = false
            }
            else -> tilNik.error = null
        }

        if (selectedDobUtcMs == null) {
            tilDob.error = "Tanggal lahir wajib diisi"; ok = false
        } else tilDob.error = null

        val rs = etRS.text?.toString()?.trim().orEmpty()
        if (rs.isEmpty()) {
            tilRS.error = "Nama Rumah Sakit wajib diisi"; ok = false
        } else tilRS.error = null

        if (!ok) Toast.makeText(this, "Periksa kembali data", Toast.LENGTH_SHORT).show()
        return ok
    }

    /**
     * Center-crop gambar ke rasio 16:9 agar identik dengan tangkapan live kamera Cervexa
     * dan mengeliminasi bilah hitam di sisi kiri-kanan (pillarbox) saat dicetak atau dilihat di galeri.
     */
    private fun cropTo16By9(src: Bitmap): Bitmap {
        val targetRatio = 16f / 9f
        val currentRatio = src.width.toFloat() / src.height.toFloat()
        return if (Math.abs(currentRatio - targetRatio) < 0.02f) {
            src
        } else if (currentRatio > targetRatio) {
            // Lebih lebar dari 16:9 -> potong kiri dan kanan secara simetris
            val newW = (src.height * targetRatio).toInt()
            val cropX = (src.width - newW) / 2
            Bitmap.createBitmap(src, cropX, 0, newW, src.height)
        } else {
            // Lebih tinggi dari 16:9 (misal 4:3 atau 1:1) -> potong atas dan bawah secara simetris
            val newH = (src.width / targetRatio).toInt()
            val cropY = (src.height - newH) / 2
            Bitmap.createBitmap(src, 0, cropY, src.width, newH)
        }
    }

    /**
     * Membubuhkan watermark overlay resmi Cervexa (Nama RS/RM di kiri bawah & Timestamp di kanan bawah)
     * persis seperti yang dibubuhkan pada foto hasil live stream kamera Cervexa.
     */
    private fun applyWatermarkOverlay(
        src: Bitmap,
        rs: String,
        nrm: String,
        timestampStr: String
    ): Bitmap {
        val bitmap = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)

        val frameHeight = bitmap.height
        val frameWidth = bitmap.width

        val textScale = 0.026f
        val textMinPx = 14f
        val textMaxPx = 32f
        val fontPx = (frameHeight * textScale).coerceIn(textMinPx, textMaxPx)

        val paddingScale = 0.016f
        val padMinPx = 8f
        val padMaxPx = 20f
        val pad = (frameHeight * paddingScale).coerceIn(padMinPx, padMaxPx)

        val paintText = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = fontPx
            setShadowLayer(4f, 1.5f, 1.5f, Color.BLACK)
        }

        val boxPadX = pad * 1.6f
        val boxH = (fontPx + pad * 1.8f).coerceAtLeast(pad * 2.6f)
        val bottom = frameHeight.toFloat()
        val top = (bottom - boxH).coerceAtLeast(0f)

        // Timestamp kanan bawah di-hide (menggunakan bawaan kamera).
        // Identity kiri bawah (Nama RS / Rekam Medis) menggunakan background transparan.
        val info = if (nrm.isEmpty()) rs else "$rs/$nrm"
        val infoTextW = paintText.measureText(info)
        val infoBoxW = (infoTextW + boxPadX * 2f).coerceAtMost(frameWidth * 0.55f)
        val infoLeft = 0f
        val infoRight = (infoLeft + infoBoxW).coerceAtMost(frameWidth.toFloat())

        val maxTextW = (infoRight - infoLeft - boxPadX * 2f).coerceAtLeast(0f)
        val infoDraw = if (paintText.measureText(info) > maxTextW) {
            TextUtils.ellipsize(info, TextPaint(paintText), maxTextW, TextUtils.TruncateAt.END).toString()
        } else info

        val infoCenterY = top + (boxH / 2f) - ((paintText.descent() + paintText.ascent()) / 2f)
        canvas.drawText(infoDraw, infoLeft + boxPadX, infoCenterY, paintText)

        return bitmap
    }
}
