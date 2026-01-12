@file:Suppress("DEPRECATION")

package com.idn.kmed.cervexa.live

import android.annotation.SuppressLint
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.alexvas.rtsp.codec.VideoDecodeThread
import com.alexvas.rtsp.widget.RtspDataListener
import com.alexvas.rtsp.widget.RtspImageView
import com.alexvas.rtsp.widget.RtspProcessor.Statistics
import com.alexvas.rtsp.widget.RtspStatusListener
import com.alexvas.rtsp.widget.toHexString
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.idn.kmed.cervexa.HomeActivity
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.SettingsActivity.Companion.KEY_CAMERA_ROTATION_DEG
import com.idn.kmed.cervexa.SettingsActivity.Companion.KEY_USE_HW_DECODER
import com.idn.kmed.cervexa.databinding.FragmentVideoBinding
import com.idn.kmed.cervexa.record.RealtimeBitmapEncoder
import com.idn.kmed.cervexa.utils.MediaItem
import com.idn.kmed.cervexa.utils.MediaType
import com.idn.kmed.cervexa.utils.PatientUtils
import com.idn.kmed.cervexa.utils.StorageUtils
import com.idn.kmed.cervexa.utils.ThumbAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class VideoFragment : Fragment() {

    private lateinit var binding: FragmentVideoBinding
    private lateinit var liveViewModel: LiveViewModel

    private var statisticsTimer: Timer? = null
    private var ivVideoImageResolution = Pair(0, 0)

    // ==== Session / Storage (dari VideoActivity via arguments) ====
    private var sessionDir: File? = null
    private var patientNama: String = ""
    private var patientNik: String = ""
    private var patientRs: String = ""
    private var patientNrm: String = ""
    private var patientDobUtc: Long = -1L
    private var patientAge: Int = 0
    private var snapshotsDir: File? = null

    // ==== Encode / Flags ====
    private lateinit var recorder: RealtimeBitmapEncoder
    private val ss = AtomicBoolean(false)      // snapshot trigger

    // ==== Video ====
    private var videosDir: File? = null
    private val record = AtomicBoolean(false)  // recording flag
    private var videoOutputFile: File? = null
    private var lastFrameSize = Pair(0, 0) // fallback ukuran jika resolusi belum terdeteksi
    private var selectionMode = false

    // === Thumbs Adapter ===
    private lateinit var thumbsAdapter: ThumbAdapter
    private var allMediaItems: List<MediaItem> = emptyList()

    // === HUD durasi rekam ===
    private var recordStartElapsedMs = 0L
    private val hudHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // === Untuk Tanggal Media
    private val today = Date()
    private val formatter = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
    private val formattedDate = formatter.format(today)

    // ==== Zoom ====
    private var currentScale = 1f
    private var minScale = 1f
    private var maxScale = 5f
    private var focusX = 0f
    private var focusY = 0f

    private val prefs by lazy {
        requireContext().getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
    }

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    // ===== FIX: drop-frame untuk kerja berat overlay/snapshot =====
    private val frameBusy = AtomicBoolean(false)

    // ===== FIX: reuse Paint + formatter (lebih ringan) =====
    private val paintTextWhite by lazy {
        Paint().apply {
            color = Color.WHITE
            textSize = 36f
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }
    }
    private val paintBox by lazy {
        Paint().apply {
            color = Color.argb(128, 0, 0, 0)
            style = Paint.Style.FILL
        }
    }
    private val paintCover by lazy {
        Paint().apply { color = "#3F3F3F".toColorInt() }
    }
    private val overlayTimeFormatterNew by lazy {
        java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
    }
    private val overlayTimeFormatterOld by lazy<DateFormat> {
        SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private val rtspDataListener = object : RtspDataListener {
        override fun onRtspDataApplicationDataReceived(
            data: ByteArray,
            offset: Int,
            length: Int,
            timestamp: Long
        ) {
            val numBytesDump = min(length, 25)
            Log.i(
                TAG,
                "RTSP app data ($length bytes): ${data.toHexString(offset, offset + numBytesDump)}"
            )
        }
    }

    private val rtspStatusImageListener = object : RtspStatusListener {
        override fun onRtspStatusConnecting() {
            if (DEBUG) Log.v(TAG, "onRtspStatusConnecting()")
            binding.apply {
                tvStatusImage?.text = "RTSP connecting"
                pbLoadingImage.visibility = View.VISIBLE
                vShutterImage.visibility = View.VISIBLE
            }
        }

        override fun onRtspStatusConnected() {
            if (DEBUG) Log.v(TAG, "onRtspStatusConnected()")
            binding.apply {
                tvStatusImage?.text = "RTSP connected"
                bnStartStopImage?.text = "Stop RTSP"
            }
            setKeepScreenOn(true)
        }

        override fun onRtspStatusDisconnecting() {
            if (DEBUG) Log.v(TAG, "onRtspStatusDisconnecting()")
            binding.apply { tvStatusImage?.text = "RTSP disconnecting" }
        }

        override fun onRtspStatusDisconnected() {
            if (DEBUG) Log.v(TAG, "onRtspStatusDisconnected()")
            binding.apply {
                tvStatusImage?.text = "RTSP disconnected"
                bnStartStopImage?.text = "Start RTSP"
                pbLoadingImage.visibility = View.GONE
                vShutterImage.apply { alpha = 1f; visibility = View.VISIBLE }
                pbLoadingImage.isEnabled = false
            }
            setKeepScreenOn(false)
        }

        override fun onRtspStatusFailedUnauthorized() {
            if (DEBUG) Log.e(TAG, "onRtspStatusFailedUnauthorized()")
            if (context == null) return
            onRtspStatusDisconnected()
            binding.apply {
                tvStatusImage?.text = "RTSP username or password invalid"
                pbLoadingImage.visibility = View.GONE
            }
        }

        override fun onRtspStatusFailed(message: String?) {
            if (DEBUG) Log.e(TAG, "onRtspStatusFailed(message='$message')")
            if (context == null) return
            onRtspStatusDisconnected()
            binding.apply {
                tvStatusImage?.text = "Error: $message"
                pbLoadingImage.visibility = View.GONE
            }
        }

        override fun onRtspFirstFrameRendered() {
            if (DEBUG) Log.v(TAG, "onRtspFirstFrameRendered()")
            Log.i(TAG, "First frame rendered")
            binding.apply {
                pbLoadingImage.visibility = View.GONE
                vShutterImage.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction {
                        vShutterImage.visibility = View.GONE
                        vShutterImage.alpha = 1f
                    }
                    .start()
            }
        }

        override fun onRtspFrameSizeChanged(width: Int, height: Int) {
            if (DEBUG) Log.v(TAG, "onRtspFrameSizeChanged(width=$width, height=$height)")
            Log.i(TAG, "Video resolution changed to ${width}x${height}")
            ivVideoImageResolution = Pair(width, height)
            ConstraintSet().apply {
                clone(binding.csVideoImage)
                setDimensionRatio(binding.ivVideoImage.id, "$width:$height")
                applyTo(binding.csVideoImage)
            }
            currentScale = 1f
            focusX = binding.ivVideoImage.width / 2f
            focusY = binding.ivVideoImage.height / 2f
            applyZoomMatrix()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ==== Ambil argumen dari VideoActivity ====
        arguments?.let { args ->
            args.getString("sessionDirPath")?.takeIf { it.isNotBlank() }?.let { p ->
                sessionDir = File(p)
            }
            patientNama = args.getString("patient_nama").orEmpty()
            patientNik = args.getString("patient_nik").orEmpty()
            patientRs = args.getString("patient_rs").orEmpty()
            patientNrm = args.getString("patient_nrm").orEmpty()
            patientDobUtc = args.getLong("patient_dob_utc", -1L)
            patientAge = PatientUtils.calculateAge(patientDobUtc)

            sessionDir?.let { parent ->
                snapshotsDir = File(parent, "Snapshots").apply { if (!exists()) mkdirs() }
                videosDir = File(parent, "Video").apply { if (!exists()) mkdirs() }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (DEBUG) Log.v(TAG, "onCreateView()")

        liveViewModel = ViewModelProvider(this)[LiveViewModel::class.java]
        binding = FragmentVideoBinding.inflate(inflater, container, false)

        // Gesture: pinch to zoom + double tap reset
        scaleDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    currentScale =
                        (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
                    focusX = detector.focusX
                    focusY = detector.focusY
                    applyZoomMatrix()
                    return true
                }
            })

        gestureDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    currentScale = if (currentScale > 1.01f) 1f else 2f
                    focusX = e.x
                    focusY = e.y
                    applyZoomMatrix()
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (currentScale > 1.01f) {
                        val m = (binding.ivVideoImage.imageMatrix ?: android.graphics.Matrix())
                        m.postTranslate(-distanceX, -distanceY)
                        binding.ivVideoImage.imageMatrix = m
                    }
                    return true
                }
            }
        )

        val touch = View.OnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            gestureDetector.onTouchEvent(ev)
            true
        }
        binding.ivVideoImage.setOnTouchListener(touch)
        binding.vShutterImage.setOnTouchListener(touch)

        // Listener RTSP
        binding.ivVideoImage.setStatusListener(rtspStatusImageListener)
        binding.ivVideoImage.setDataListener(rtspDataListener)

        // ===== FIX: SOFTWARE decoder default (Mi Stick) =====
        val useHw = prefs.getBoolean(KEY_USE_HW_DECODER, false)
        binding.ivVideoImage.videoDecoderType =
            if (useHw) VideoDecodeThread.DecoderType.HARDWARE
            else VideoDecodeThread.DecoderType.SOFTWARE

        // Rotation
        binding.ivVideoImage.videoRotation = prefs.getInt(KEY_CAMERA_ROTATION_DEG, 0)

        // Start/Stop stream
        binding.bnStartStopImage?.setOnClickListener {
            if (binding.ivVideoImage.isStarted()) {
                binding.ivVideoImage.stop()
                stopStatistics()
            } else {
                startRtspStream()
            }
        }

        // Enter Landscape
        binding.btnEnterLandscape?.setOnClickListener {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        // Snapshot
        binding.btnSnapshot.setOnClickListener { ss.set(true) }

        // Record
        binding.btnRecordVideo.setOnClickListener {
            if (record.get()) stopVideoRecording() else startVideoRecording()
        }

        // Back hardware
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    showExitConfirmDialog()
                }
            }
        )

        // Back toolbar
        binding.topAppBar.setNavigationOnClickListener { showExitConfirmDialog() }

        // Back Landscape
        binding.btnBackLite?.setOnClickListener {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // Thumbs recycler
        binding.rvThumbs.apply {
            layoutManager = GridLayoutManager(requireContext(), 4)
            thumbsAdapter = ThumbAdapter { _, position ->
                val paths = ArrayList(allMediaItems.map { it.file.absolutePath })
                val types = ArrayList(allMediaItems.map { it.type.name })
                val i = Intent(
                    requireContext(),
                    com.idn.kmed.cervexa.gallery.MediaPagerActivity::class.java
                ).apply {
                    putStringArrayListExtra("paths", paths)
                    putStringArrayListExtra("types", types)
                    putExtra("index", position)
                }
                startActivity(i)
            }
            thumbsAdapter.selectionListener = object : ThumbAdapter.SelectionListener {
                override fun onSelectionChanged(count: Int) {
                    if (selectionMode) binding.topAppBar.title = "$count dipilih"
                }
            }
            thumbsAdapter.onStartSelectionRequested = {
                if (!selectionMode) enterSelectionMode()
            }
            adapter = thumbsAdapter
        }

        // Menu normal
        binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_info_pasien -> {
                    showPatientInfoBottomSheet(); true
                }

                R.id.action_pilih -> {
                    enterSelectionMode(); true
                }

                else -> false
            }
        }

        binding.btnSimpanCase.setOnClickListener { showSaveConfirmDialog() }

        binding.tvMediaTgl?.text = formattedDate

        refreshThumbs()

        return binding.root
    }

    private fun applyZoomMatrix() {
        val m = android.graphics.Matrix()
        m.postScale(currentScale, currentScale, focusX, focusY)
        binding.ivVideoImage.imageMatrix = m
    }

    override fun onResume() {
        if (DEBUG) Log.v(TAG, "onResume()")
        super.onResume()

        requireActivity().window.statusBarColor =
            if (isLandscape()) ContextCompat.getColor(requireContext(), R.color.colorBlack)
            else ContextCompat.getColor(requireContext(), R.color.colorButton)

        liveViewModel.loadParams(requireContext())

        if (!binding.ivVideoImage.isStarted()) {
            startRtspStream()
        }
    }

    override fun onPause() {
        super.onPause()

        requireActivity().window.statusBarColor =
            if (isLandscape()) ContextCompat.getColor(requireContext(), R.color.colorBlack)
            else ContextCompat.getColor(requireContext(), R.color.colorButton)

        liveViewModel.saveParams(requireContext())

        if (record.get()) {
            stopVideoRecording()
        } else {
            hudHandler.removeCallbacks(hudTick)
            binding.recordHud.visibility = View.GONE
        }
    }

    private fun stopStreamAndExit() {
        stopVideoRecording()

        if (binding.ivVideoImage.isStarted()) binding.ivVideoImage.stop()
        stopStatistics()

        binding.vShutterImage.apply { alpha = 1f; visibility = View.VISIBLE }

        val intent = Intent(requireContext(), HomeActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra("open_tab", "media")
        startActivity(intent)
    }

    private fun showExitConfirmDialog() {
        val confirmDialog = MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
            .setTitle("Selesaikan Sesi?")
            .setMessage("Apakah Anda yakin ingin keluar dan menyelesaikan sesi sekarang?")
            .setPositiveButton("Selesai") { _, _ -> stopStreamAndExit() }
            .setNegativeButton("Batal", null)
            .create()
        confirmDialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom)
        confirmDialog.show()
    }

    // ========================= THUMBS =========================

    private fun refreshThumbs() {
        val parent = sessionDir ?: return
        val imgs = File(parent, "Snapshots").listFiles { f ->
            f.isFile && f.extension.equals("jpg", true)
        }.orEmpty()

        val vids = File(parent, "Video").listFiles { f ->
            f.isFile && f.extension.equals("mp4", true)
        }.orEmpty()

        val merged = (imgs.map { MediaItem(it, MediaType.IMAGE) } +
                vids.map { MediaItem(it, MediaType.VIDEO) })
            .sortedByDescending { it.file.lastModified() }

        allMediaItems = merged

        val isEmpty = merged.isEmpty()
        binding.tvEmptyThumbs?.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.tvImgNoMedia?.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.tvImgSubtitleNoMedia?.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvThumbs.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.tvMedia?.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.tvMediaTgl?.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.btnSimpanCase.visibility = if (isEmpty) View.GONE else View.VISIBLE

        thumbsAdapter.submitList(merged)
    }

    // ========================= HUD TIMER =========================

    private val hudTick = object : Runnable {
        override fun run() {
            if (!record.get()) return
            val elapsed = android.os.SystemClock.elapsedRealtime() - recordStartElapsedMs
            binding.tvRecordTimer.text = formatHmsFixed(elapsed)
            hudHandler.postDelayed(this, 1000L)
        }
    }

    private fun formatHmsFixed(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    // ========================= SELECTION MODE =========================

    private fun enterSelectionMode() {
        selectionMode = true
        binding.topAppBar.menu.clear()
        binding.topAppBar.inflateMenu(R.menu.menu_video_fragment_select)
        binding.topAppBar.title = "0 dipilih"

        binding.topAppBar.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                R.id.action_delete_selected -> {
                    confirmDeleteSelected(); true
                }

                R.id.action_done_select -> {
                    exitSelectionMode(); true
                }

                else -> false
            }
        }
        thumbsAdapter.setSelectionMode(true)
    }

    private fun confirmDeleteSelected() {
        val files = thumbsAdapter.getSelectedItems()
        if (files.isEmpty()) {
            Toast.makeText(requireContext(), "Belum ada yang dipilih", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
            .setTitle("Hapus ${files.size} item?")
            .setMessage("Tindakan ini tidak dapat dibatalkan.")
            .setPositiveButton("Hapus") { _, _ -> deleteFiles(files) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun deleteFiles(files: List<File>) {
        var ok = 0
        var fail = 0
        val deletedPaths = mutableListOf<String>()

        files.forEach { f ->
            if (runCatching { f.delete() }.isSuccess) {
                ok++
                deletedPaths.add(f.absolutePath)
            } else {
                fail++
            }
        }

        refreshThumbs()

        if (deletedPaths.isNotEmpty()) {
            android.media.MediaScannerConnection.scanFile(
                requireContext(),
                deletedPaths.toTypedArray(),
                null,
                null
            )
        }

        exitSelectionMode()

        Toast.makeText(
            requireContext(),
            "Hapus: $ok sukses, $fail gagal",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        binding.topAppBar.menu.clear()
        binding.topAppBar.inflateMenu(R.menu.menu_video_fragment)
        binding.topAppBar.title = "Cervexa Colposcope"
        binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_info_pasien -> {
                    showPatientInfoBottomSheet(); true
                }

                R.id.action_pilih -> {
                    enterSelectionMode(); true
                }

                else -> false
            }
        }
        thumbsAdapter.setSelectionMode(false)
    }

    // ========================= SAVE FLOW =========================

    private fun showSaveConfirmDialog() {
        val dialogConfirm = MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
            .setTitle("Konfirmasi")
            .setMessage("Pastikan pekerjaan telah selesai, sebelum menyimpan media")
            .setNegativeButton("Kembali", null)
            .setPositiveButton("Simpan") { _, _ ->
                showSavingProgressAndExecute()
            }
            .create()
        dialogConfirm.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom)
        dialogConfirm.show()
    }

    private fun showSavingProgressAndExecute() {
        val progressView = layoutInflater.inflate(R.layout.dialog_progress_saving, null)
        val progressDialog =
            MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
                .setView(progressView)
                .setCancelable(false)
                .create()

        progressDialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom)
        progressDialog.show()

        val bar = progressView.findViewById<LinearProgressIndicator>(R.id.progress)
        bar.isIndeterminate = false
        bar.max = 100
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            repeat(15) {
                bar.setProgressCompat((it + 1) * (100 / 10), true)
                delay(50)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            if (selectionMode) {
                val all = thumbsAdapter.currentList.map { it.file }
                val keep = thumbsAdapter.getSelectedItems().toSet()
                val toDelete = all.filterNot { keep.contains(it) }
                toDelete.forEach { runCatching { it.delete() } }
            }

            delay(600)

            withContext(Dispatchers.Main) {
                if (progressDialog.isShowing) progressDialog.dismiss()

                if (selectionMode) {
                    exitSelectionMode()
                    refreshThumbs()
                }

                showSaveSuccessDialog()
            }
        }
    }

    private fun showSaveSuccessDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_save_success, null)
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
            .setView(v)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom)
        dialog.show()

        v.findViewById<TextView>(R.id.tvAction)?.setOnClickListener {
            dialog.dismiss()
            stopStreamAndExit()
        }
    }

    // ========================= PATIENT INFO =========================

    private fun showPatientInfoBottomSheet() {
        val ctx = requireContext()
        val dialog = BottomSheetDialog(
            ctx,
            com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog
        )
        val v = layoutInflater.inflate(R.layout.bs_patient_info, null)
        dialog.setContentView(v)

        dialog.setOnShowListener {
            val sheet =
                dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                val radius = resources.getDimension(R.dimen.bs_top_radius)
                val shape = MaterialShapeDrawable(
                    ShapeAppearanceModel.Builder()
                        .setTopLeftCorner(CornerFamily.ROUNDED, radius)
                        .setTopRightCorner(CornerFamily.ROUNDED, radius)
                        .build()
                ).apply {
                    fillColor = ColorStateList.valueOf(Color.WHITE)
                    elevation = sheet.elevation
                }
                sheet.background = shape
            }
        }

        val btnClose = v.findViewById<ImageButton>(R.id.btnClose)
        val tvTanggal = v.findViewById<TextView>(R.id.tvTanggal)
        val tvNama = v.findViewById<TextView>(R.id.tvNama)
        val tvNik = v.findViewById<TextView>(R.id.tvNik)
        val tvDob = v.findViewById<TextView>(R.id.tvDob)
        val tvNrm = v.findViewById<TextView>(R.id.tvNrm)

        val sdfNow = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("id", "ID")).apply {
            timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        }
        tvTanggal.text = sdfNow.format(Date())

        val namaSafe = patientNama.ifBlank { "-" }
        tvNama.text = if (patientAge > 0) "$namaSafe ($patientRs)" else namaSafe

        tvNik.text = patientNik.ifBlank { "-" }

        tvDob.text = if (patientDobUtc > 0L) {
            val sdfDob = SimpleDateFormat("dd/MM/yyyy", Locale("id", "ID"))
            sdfDob.format(Date(patientDobUtc))
        } else "-"

        tvNrm.text = patientNrm.ifBlank { "Tidak ada nomor rekam medis" }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // ========================= RTSP =========================

    private fun startRtspStream() {
        val uri = Uri.parse(liveViewModel.rtspRequest.value)

        binding.ivVideoImage.apply {
            init(
                uri,
                liveViewModel.rtspUsername.value,
                liveViewModel.rtspPassword.value,
                "cervexa-client-android"
            )

            onRtspImageBitmapListener = object : RtspImageView.RtspImageBitmapListener {
                override fun onRtspImageBitmapObtained(bitmap: Bitmap) {

                    // simpan ukuran terakhir utk fallback recorder
                    lastFrameSize = Pair(bitmap.width, bitmap.height)

                    val doRecord = record.get()
                    val doSnapshot = ss.get()

                    // ===== FIX: kalau live-only, jangan proses apa-apa (hemat CPU Mi Stick) =====
                    if (!doRecord && !doSnapshot) return

                    // drop-frame untuk kerja berat overlay/snapshot
                    if (!frameBusy.compareAndSet(false, true)) return

                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                        try {
                            // overlay hanya saat record/snapshot
                            val bmOverlay = processTextToBitmapSafe(bitmap)

                            if (doRecord) {
                                // COPY ringan supaya aman dari aliasing thread/view
                                val forEnc =
                                    if (bmOverlay.isMutable) bmOverlay.copy(
                                        Bitmap.Config.ARGB_8888,
                                        false
                                    )
                                    else bmOverlay
                                recorder.submitBitmap(forEnc)
                            }

                            if (ss.getAndSet(false)) {
                                val dir = snapshotsDir ?: sessionDir
                                if (dir != null) {
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching {
                                            StorageUtils.saveJpegWithPrefix(
                                                dir,
                                                bmOverlay,
                                                prefix = "ss"
                                            )
                                        }.isSuccess
                                    }
                                    if (ok) withContext(Dispatchers.Main) { refreshThumbs() }
                                }
                            }
                        } finally {
                            frameBusy.set(false)
                        }
                    }
                }
            }

            start(
                requestVideo = true,
                requestAudio = false,
                requestApplication = false
            )
        }
        // startStatistics() // optional
    }

    private fun startStatistics() {
        if (DEBUG) Log.v(TAG, "startStatistics()")
        Log.i(TAG, "Start statistics")
        if (statisticsTimer == null) {
            val task: TimerTask = object : TimerTask() {
                override fun run() {
                    if (binding.ivVideoImage.isStarted()) {
                        val statistics: Statistics = binding.ivVideoImage.statistics
                        val text =
                            "Video decoder: ${
                                statistics.videoDecoderType.toString().lowercase()
                            } ${if (statistics.videoDecoderName.isNullOrEmpty()) "" else "(${statistics.videoDecoderName})"}" +
                                    "\nVideo decoder latency: ${statistics.videoDecoderLatencyMsec} ms" +
                                    "\nResolution: ${ivVideoImageResolution.first}x${ivVideoImageResolution.second}"
                        binding.tvStatistics2?.post { binding.tvStatistics2?.text = text }
                    }
                }
            }
            statisticsTimer = Timer("${TAG}::Statistics").apply { schedule(task, 0, 1000) }
        }
    }

    private fun stopStatistics() {
        if (DEBUG) Log.v(TAG, "stopStatistics()")
        statisticsTimer?.apply {
            Log.i(TAG, "Stop statistics")
            cancel()
        }
        statisticsTimer = null
    }

    private fun setKeepScreenOn(enable: Boolean) {
        if (DEBUG) Log.v(TAG, "setKeepScreenOn(enable=$enable)")
        activity?.apply {
            if (enable) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ========================= RECORDING =========================

    private fun startVideoRecording() {
        val dir = videosDir ?: sessionDir
        if (dir == null) {
            Toast.makeText(requireContext(), "Folder sesi belum siap", Toast.LENGTH_SHORT).show()
            return
        }

        val out = File(dir, "vid_${StorageUtils.timestampWIB()}.mp4")
        videoOutputFile = out

        // sumber resolusi (stream atau fallback)
        val srcW =
            if (ivVideoImageResolution.first > 0) ivVideoImageResolution.first else lastFrameSize.first
        val srcH =
            if (ivVideoImageResolution.second > 0) ivVideoImageResolution.second else lastFrameSize.second

        // ===== FIX: record max 720p untuk device lemah (Mi Stick) =====
        val targetW: Int
        val targetH: Int
        if (srcW >= 1280 && srcH >= 720) {
            targetW = 1280
            targetH = 720
        } else {
            targetW = srcW.coerceAtLeast(640)
            targetH = srcH.coerceAtLeast(360)
        }

        try {
            recorder = RealtimeBitmapEncoder(
                context = requireContext(),
                width = targetW,
                height = targetH,
                outputFile = out,
                frameRate = 30,
                queueCapacity = 2 // bounded queue (drop-frame)
            )
            recorder.start()

            record.set(true)
            recordStartElapsedMs = android.os.SystemClock.elapsedRealtime()
            binding.recordHud.visibility = View.VISIBLE
            binding.tvRecordTimer.text = "00:00:00"
            hudHandler.removeCallbacks(hudTick)
            hudHandler.post(hudTick)

            binding.btnRecordVideo.setImageResource(R.drawable.btn_stop)
        } catch (e: Exception) {
            record.set(false)
            Toast.makeText(requireContext(), "Gagal mulai rekam: ${e.message}", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun stopVideoRecording() {
        if (!record.get()) return

        runCatching { recorder.stop() }
        record.set(false)

        hudHandler.removeCallbacks(hudTick)
        binding.recordHud.visibility = View.GONE
        binding.btnRecordVideo.setImageResource(R.drawable.majesticons_video)

        val file = videoOutputFile
        videoOutputFile = null

        binding.rvThumbs.postDelayed({ refreshThumbs() }, 150)

        if (file != null) {
            Toast.makeText(requireContext(), "Meyimpan Media", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Rekaman dihentikan", Toast.LENGTH_SHORT).show()
        }
    }

    // ========================= OVERLAY =========================

    /**
     * Overlay (timestamp + identitas) di atas bitmap. Aman jika bitmap immutable.
     * Catatan: kalau src mutable, fungsi ini bisa “in-place”.
     */
    private fun processTextToBitmapSafe(src: Bitmap): Bitmap {
        val bitmap = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)

        val formatted: String = if (android.os.Build.VERSION.SDK_INT >= 26) {
            java.time.ZonedDateTime.now().format(overlayTimeFormatterNew)
        } else {
            overlayTimeFormatterOld.format(Date())
        }

        val canvas = Canvas(bitmap)

        // Tutup timestamp video bawaan (pojok kanan bawah)
        canvas.drawRect(
            bitmap.width.toFloat() - 360f,
            bitmap.height.toFloat() - 60f,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            paintCover
        )

        // Tulis timestamp kita (pojok kanan bawah)
        canvas.drawText(
            formatted,
            bitmap.width.toFloat() - 350f,
            bitmap.height.toFloat() - 20f,
            paintTextWhite
        )

        // Box identitas (pojok kiri bawah)
        canvas.drawRect(0f, bitmap.height.toFloat() - 65f, 650f, bitmap.height.toFloat(), paintBox)
        val leftText = if (patientNrm.isEmpty()) "$patientRs" else "$patientRs/$patientNrm"
        canvas.drawText(leftText, 20f, bitmap.height.toFloat() - 20f, paintTextWhite)

        return bitmap
    }

    // ========================= MISC =========================

    private fun openPreview(file: File, isVideo: Boolean) {
        val paths = arrayListOf(file.absolutePath)
        val types = arrayListOf(if (isVideo) "VIDEO" else "IMAGE")

        val target = if (isLandscape())
            com.idn.kmed.cervexa.gallery.MediaPagerActivityLand::class.java
        else
            com.idn.kmed.cervexa.gallery.MediaPagerActivity::class.java

        startActivity(
            Intent(requireContext(), target).apply {
                putStringArrayListExtra("paths", paths)
                putStringArrayListExtra("types", types)
                putExtra("index", 0)
                putExtra("forceLandscape", isLandscape())
            }
        )
    }

    companion object {
        private val TAG: String = VideoFragment::class.java.simpleName
        private const val DEBUG = true
    }
}
