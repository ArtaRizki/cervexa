package com.idn.kmed.cervexa.live

import android.annotation.SuppressLint
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
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
import com.alexvas.rtsp.codec.VideoDecodeThread
import com.alexvas.rtsp.widget.RtspDataListener
import com.alexvas.rtsp.widget.RtspImageView
import com.alexvas.rtsp.widget.RtspProcessor.Statistics
import com.alexvas.rtsp.widget.RtspStatusListener
import com.alexvas.rtsp.widget.toHexString
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.idn.kmed.cervexa.HomeActivity
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.SettingsActivity.Companion.KEY_CAMERA_ROTATION_DEG
import com.idn.kmed.cervexa.SettingsActivity.Companion.KEY_USE_HW_DECODER
import com.idn.kmed.cervexa.databinding.FragmentVideoMobileBinding
import com.idn.kmed.cervexa.record.RealtimeBitmapEncoder
import com.idn.kmed.cervexa.utils.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * VideoFragmentMobile — OPTIMIZED
 *
 * Fix overlay text: ukuran font + padding + background box diskalakan berdasarkan ukuran frame.
 */
class VideoFragmentMobile : Fragment() {

    private lateinit var binding: FragmentVideoMobileBinding
    private lateinit var liveViewModel: LiveViewModel

    // Frame terakhir — dilindungi lock hanya saat assignment
    private var lastBitmap: Bitmap? = null
    private val bitmapLock = Any()

    private var statisticsJob: Job? = null
    private var clockJob: Job? = null
    private var ivVideoImageResolution = Pair(0, 0)

    // ==== Session / Storage ====
    private var sessionDir: File? = null
    private var patientNama: String = ""
    private var patientNik: String = ""
    private var patientRs: String = ""
    private var patientNrm: String = ""
    private var patientDobUtc: Long = -1L
    private var patientAge: Int = 0
    private var snapshotsDir: File? = null
    private var videosDir: File? = null
    private var isMetadataSaved = false
    private var serverPatientId: Int = -1

    private val apiDelegate by lazy {
        VideoApiDelegate(
            context = requireContext(),
            scope = lifecycleScope,
            onError = { msg ->
                view?.let {
                    com.google.android.material.snackbar.Snackbar
                        .make(it, msg, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                        .show()
                }
            }
        )
    }

    // ==== Encode / Flags ====
    private lateinit var recorder: RealtimeBitmapEncoder
    private val ss = AtomicBoolean(false)
    private val record = AtomicBoolean(false)
    private var videoOutputFile: File? = null
    private var lastFrameSize = Pair(0, 0)
    private var selectionMode = false

    private lateinit var thumbsAdapter: ThumbAdapter
    private var allMediaItems: List<MediaItem> = emptyList()

    private var recordStartElapsedMs = 0L
    private val hudHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val formattedDate by lazy {
        SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
    }

    private var currentScale = 1f
    private val minScale = 1f
    private val maxScale = 5f
    private var focusX = 0f
    private var focusY = 0f

    private val prefs by lazy {
        requireContext().getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
    }

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    // =====================================================================
    // Paint di-cache di class level
    // =====================================================================
    private val paintText = Paint().apply {
        color = Color.WHITE
        // textSize JANGAN hardcode (akan diskalakan berdasarkan frame)
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val paintBox = Paint().apply {
        color = Color.argb(128, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val paintDateBg = Paint().apply {
        color = "#3F3F3F".toColorInt()
        style = Paint.Style.FILL
    }

    // === Overlay scaling cache ===
    private var lastOverlayTargetHeight: Int = -1
    private var lastOverlayTextSizePx: Float = -1f

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // =====================================================================
    // LIFECYCLE
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let { args ->
            patientNama = args.getString("patient_nama").orEmpty()
            patientNik = args.getString("patient_nik").orEmpty()
            patientRs = args.getString("patient_rs").orEmpty()
            patientNrm = args.getString("patient_nrm").orEmpty()
            patientDobUtc = args.getLong("patient_dob_utc", -1L)
            patientAge = PatientUtils.calculateAge(patientDobUtc)
            serverPatientId = args.getInt("patient_id", -1)
            sessionDir =
                args.getString("sessionDirPath")?.takeIf { it.isNotBlank() }?.let { File(it) }
        }
        apiDelegate.createSession(serverPatientId, patientRs)

        if (sessionDir == null) {
            val dateFolder = StorageUtils.todayDateFolderWIB()
            val patientFolder = if (patientNik.isNotBlank())
                "${patientNik}_${patientNama.replace(" ", "_")}"
            else "Patient_Unknown_${System.currentTimeMillis()}"
            sessionDir = StorageUtils.ensureSessionDir(requireContext(), dateFolder, patientFolder)
        }

        sessionDir?.let { parent ->
            snapshotsDir = StorageUtils.ensureChildDir(parent, "Snapshots")
            videosDir = StorageUtils.ensureChildDir(parent, "Video")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (allMediaItems.isNotEmpty() && !isMetadataSaved) saveSessionMetadata()

        clockJob?.cancel()
        statisticsJob?.cancel()

        if (record.get()) stopVideoRecording()
        if (binding.ivVideoImage.isStarted()) binding.ivVideoImage.stop()

        binding.ivVideoImage.onRtspImageBitmapListener = null
        binding.ivVideoImage.setStatusListener(null)
        binding.ivVideoImage.setDataListener(null)
        hudHandler.removeCallbacks(hudTick)

        binding.root.postDelayed({
            synchronized(bitmapLock) { lastBitmap?.recycle(); lastBitmap = null }
        }, 100)
    }

    override fun onResume() {
        super.onResume()
        updateStatusBarColor()
        liveViewModel.loadParams(requireContext())
        if (!binding.ivVideoImage.isStarted()) startRtspStream()
        if (clockJob?.isActive != true) startOverlayClock()
    }

    override fun onPause() {
        super.onPause()
        if (record.get()) stopVideoRecording()
        hudHandler.removeCallbacks(hudTick)
        binding.recordHud.visibility = View.GONE
        liveViewModel.saveParams(requireContext())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.tvOverlayInfo.text = overlayInfoText()
        updateStatusBarColor()
        if (clockJob?.isActive != true) startOverlayClock()
    }

    // =====================================================================
    // UI INFLATION
    // =====================================================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        liveViewModel = ViewModelProvider(this)[LiveViewModel::class.java]
        binding = FragmentVideoMobileBinding.inflate(inflater, container, false)

        binding.ivVideoImage.apply {
            setStatusListener(rtspStatusListener)
            setDataListener(rtspDataListener)
            enablePinchZoom()
            videoDecoderType = VideoDecodeThread.DecoderType.HARDWARE
            videoRotation = prefs.getInt(KEY_CAMERA_ROTATION_DEG, 0)
        }

        setupGestureDetectors()
        setupButtons()
        setupThumbs()

        binding.tvMediaTgl?.text = formattedDate
        binding.tvOverlayInfo.text = overlayInfoText()
        startOverlayClock()
        refreshThumbs()

        return binding.root
    }

    private fun setupGestureDetectors() {
        scaleDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    currentScale = (currentScale * d.scaleFactor).coerceIn(minScale, maxScale)
                    focusX = d.focusX; focusY = d.focusY; applyZoomMatrix(); return true
                }
            })
        gestureDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    currentScale = if (currentScale > 1.01f) 1f else 2f
                    focusX = e.x; focusY = e.y; applyZoomMatrix(); return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    dX: Float,
                    dY: Float
                ): Boolean {
                    if (currentScale > 1.01f) {
                        val m = binding.ivVideoImage.imageMatrix ?: android.graphics.Matrix()
                        m.postTranslate(-dX, -dY); binding.ivVideoImage.imageMatrix = m
                    }; return true
                }
            })

        val touch = View.OnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev); gestureDetector.onTouchEvent(ev); true
        }
        binding.ivVideoImage.setOnTouchListener(touch)
        binding.vShutterImage.setOnTouchListener(touch)
    }

    private fun setupButtons() {
        binding.bnStartStopImage?.setOnClickListener {
            if (binding.ivVideoImage.isStarted()) {
                binding.ivVideoImage.stop(); statisticsJob?.cancel()
            } else startRtspStream()
        }
        binding.btnEnterLandscape?.setOnClickListener {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        binding.btnSnapshot.setOnClickListener { onSnapshotClicked() }
        binding.btnRecordVideo.setOnClickListener {
            if (record.get()) stopVideoRecording() else startVideoRecording()
        }
        binding.btnBackLite?.setOnClickListener {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        binding.btnSimpanCase.setOnClickListener { showSaveConfirmDialog() }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    showExitConfirmDialog()
                }
            })
        binding.topAppBar.setNavigationOnClickListener { showExitConfirmDialog() }
    }

    private fun setupThumbs() {
        binding.rvThumbs.apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 4)
            thumbsAdapter = ThumbAdapter { _, position ->
                val paths = ArrayList(allMediaItems.map { it.file.absolutePath })
                val types = ArrayList(allMediaItems.map { it.type.name })
                startActivity(
                    Intent(
                        requireContext(),
                        com.idn.kmed.cervexa.gallery.MediaPagerActivity::class.java
                    ).apply {
                        putStringArrayListExtra("paths", paths)
                        putStringArrayListExtra("types", types)
                        putExtra("index", position)
                    })
            }
            thumbsAdapter.selectionListener = object : ThumbAdapter.SelectionListener {
                override fun onSelectionChanged(count: Int) {
                    if (selectionMode) binding.topAppBar.title = "$count dipilih"
                }
            }
            thumbsAdapter.onStartSelectionRequested = { if (!selectionMode) enterSelectionMode() }
            adapter = thumbsAdapter
        }
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
    }

    // =====================================================================
    // RTSP STREAM
    // =====================================================================

    private val rtspStatusListener = object : RtspStatusListener {
        override fun onRtspStatusConnecting() {
            binding.tvStatusImage?.text = "RTSP connecting..."
            binding.pbLoadingImage.visibility = View.VISIBLE
            binding.vShutterImage.visibility = View.VISIBLE
        }

        override fun onRtspStatusConnected() {
            binding.tvStatusImage?.text = "RTSP connected ✓"
            binding.bnStartStopImage?.text = "Stop RTSP"
            setKeepScreenOn(true)
        }

        override fun onRtspStatusDisconnected() {
            binding.tvStatusImage?.text = "RTSP disconnected"
            binding.bnStartStopImage?.text = "Start RTSP"
            binding.pbLoadingImage.visibility = View.GONE
            binding.vShutterImage.apply { alpha = 1f; visibility = View.VISIBLE }
            setKeepScreenOn(false)
            synchronized(bitmapLock) { lastBitmap = null }
        }

        override fun onRtspStatusFailed(message: String?) {
            if (context == null) return
            onRtspStatusDisconnected()
            binding.tvStatusImage?.text = "Error: $message"
            binding.pbLoadingImage.visibility = View.GONE
            Toast.makeText(requireContext(), "❌ RTSP gagal: $message", Toast.LENGTH_LONG).show()
        }

        override fun onRtspFirstFrameRendered() {
            binding.pbLoadingImage.visibility = View.GONE
            binding.vShutterImage.animate().alpha(0f).setDuration(250).withEndAction {
                binding.vShutterImage.visibility = View.GONE; binding.vShutterImage.alpha = 1f
            }.start()
        }

        override fun onRtspFrameSizeChanged(width: Int, height: Int) {
            ivVideoImageResolution = Pair(width, height)
            lastFrameSize = Pair(width, height)
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

    private val rtspDataListener = object : RtspDataListener {
        override fun onRtspDataApplicationDataReceived(
            data: ByteArray,
            offset: Int,
            length: Int,
            timestamp: Long
        ) {
            Log.i(
                TAG,
                "RTSP app data ($length bytes): ${
                    data.toHexString(
                        offset,
                        offset + min(length, 25)
                    )
                }"
            )
        }
    }

    private fun startRtspStream() {
        val rtspUrl = liveViewModel.rtspRequest.value
        if (rtspUrl.isNullOrBlank()) {
            Toast.makeText(requireContext(), "❌ URL RTSP tidak valid", Toast.LENGTH_LONG)
                .show(); return
        }
        binding.ivVideoImage.apply {
            init(
                uri = Uri.parse(rtspUrl),
                username = liveViewModel.rtspUsername.value,
                password = liveViewModel.rtspPassword.value,
                userAgent = "cervexa-client-android"
            )
            videoDecoderType = if (prefs.getBoolean(KEY_USE_HW_DECODER, false))
                VideoDecodeThread.DecoderType.HARDWARE else VideoDecodeThread.DecoderType.SOFTWARE
            videoRotation = prefs.getInt(KEY_CAMERA_ROTATION_DEG, 0)

            onRtspImageBitmapListener = object : RtspImageView.RtspImageBitmapListener {
                override fun onRtspImageBitmapObtained(bitmap: Bitmap) {
                    if (!isAdded || view == null) return

                    val workBitmap: Bitmap = synchronized(bitmapLock) {
                        lastBitmap?.recycle()
                        val b = bitmap.copy(Bitmap.Config.ARGB_8888, true) // mutable!
                        lastBitmap = b
                        b
                    }

                    val bmWithOverlay = processTextToBitmapSafe(workBitmap)

                    if (record.get() && ::recorder.isInitialized) {
                        runCatching {
                            recorder.submitBitmap(
                                bmWithOverlay.copy(Bitmap.Config.ARGB_8888, false)
                            )
                        }.onFailure { Log.e(TAG, "submitBitmap error", it) }
                    }

                    if (ss.compareAndSet(true, false)) {
                        processSnapshot(bmWithOverlay)
                    }
                }
            }

            start(requestVideo = true, requestAudio = false, requestApplication = false)
        }
        startStatisticsJob()
    }

    // =====================================================================
    // RECORDING
    // =====================================================================

    private fun startVideoRecording() {
        var dir = videosDir
        if (dir == null || !dir.exists()) {
            sessionDir?.let {
                dir = StorageUtils.ensureChildDir(it, "Video").also { d -> videosDir = d }
            }
        }
        if (dir == null || !dir!!.exists()) {
            Toast.makeText(requireContext(), "❌ Gagal membuat direktori video", Toast.LENGTH_LONG)
                .show(); return
        }

        val width =
            ivVideoImageResolution.first.takeIf { it > 0 } ?: lastFrameSize.first.coerceAtLeast(
                STB_WIDTH
            )
        val height =
            ivVideoImageResolution.second.takeIf { it > 0 } ?: lastFrameSize.second.coerceAtLeast(
                STB_HEIGHT
            )
        val out = File(dir, "vid_${StorageUtils.timestampWIB()}.mp4")
        videoOutputFile = out

        runCatching {
            recorder = RealtimeBitmapEncoder(
                context = requireContext(),
                width = width,
                height = height,
                outputFile = out,
                frameRate = STB_FPS,
                bitRate = STB_BITRATE
            )
            recorder.start()
            record.set(true)
            recordStartElapsedMs = android.os.SystemClock.elapsedRealtime()

            binding.recordHud.visibility = View.VISIBLE
            binding.tvRecordTimer.text = "00:00:00"
            hudHandler.removeCallbacks(hudTick)
            hudHandler.post(hudTick)
            binding.btnRecordVideo.setImageResource(R.drawable.btn_stop)
        }.onFailure {
            Log.e(TAG, "Recording ERROR", it); record.set(false)
            Toast.makeText(requireContext(), "❌ Gagal merekam: ${it.message}", Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun stopVideoRecording() {
        if (!record.get()) return
        record.set(false)
        hudHandler.removeCallbacks(hudTick)
        binding.recordHud.visibility = View.GONE
        binding.btnRecordVideo.setImageResource(R.drawable.majesticons_video)

        runCatching { if (::recorder.isInitialized) recorder.stop() }
            .onFailure { Log.e(TAG, "Encoder stop error", it) }

        val file = videoOutputFile; videoOutputFile = null
        if (file != null && file.exists()) {
            if (!isMetadataSaved) saveSessionMetadata()
            apiDelegate.uploadVideo(file)
            Toast.makeText(requireContext(), "✅ VIDEO TERSIMPAN!", Toast.LENGTH_SHORT).show()
            binding.rvThumbs.postDelayed({ refreshThumbs() }, 300)
        } else {
            Toast.makeText(requireContext(), "⚠️ File video tidak ditemukan", Toast.LENGTH_SHORT)
                .show()
        }
    }

    // =====================================================================
    // SNAPSHOT
    // =====================================================================

    private fun onSnapshotClicked() {
        if (!binding.ivVideoImage.isStarted()) {
            Toast.makeText(requireContext(), "⚠️ Stream belum aktif", Toast.LENGTH_LONG)
                .show(); return
        }
        if (synchronized(bitmapLock) { lastBitmap == null }) {
            Toast.makeText(requireContext(), "⚠️ Tunggu frame pertama...", Toast.LENGTH_SHORT)
                .show(); return
        }
        if (sessionDir == null) {
            Toast.makeText(requireContext(), "❌ Direktori sesi tidak tersedia", Toast.LENGTH_LONG)
                .show(); return
        }
        ss.set(true)
        Toast.makeText(requireContext(), "📸 Mengambil snapshot...", Toast.LENGTH_SHORT).show()
    }

    private fun processSnapshot(bmp: Bitmap) {
        var dir = snapshotsDir
        if (dir == null || !dir.exists()) {
            sessionDir?.let {
                dir = StorageUtils.ensureChildDir(it, "Snapshots").also { d -> snapshotsDir = d }
            }
        }
        if (dir == null || !dir!!.exists()) {
            requireActivity().runOnUiThread {
                Toast.makeText(
                    requireContext(),
                    "❌ Gagal membuat direktori snapshot",
                    Toast.LENGTH_LONG
                ).show()
            }; return
        }
        runCatching { StorageUtils.saveJpegWithPrefix(dir!!, bmp, prefix = "ss") }
            .onSuccess { savedFile ->
                if (!isMetadataSaved) saveSessionMetadata()
                apiDelegate.uploadSnapshot(savedFile)   // ← BARU: upload ke server (background)
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "📸 SNAPSHOT TERSIMPAN!", Toast.LENGTH_SHORT)
                        .show()
                    refreshThumbs()
                }
            }
            .onFailure { err ->
                requireActivity().runOnUiThread {
                    Toast.makeText(
                        requireContext(),
                        "❌ Gagal simpan: ${err.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    // =====================================================================
    // FIX: overlay scaling berdasarkan ukuran frame
    // =====================================================================

    private fun ensureOverlayTextSize(frameHeight: Int) {
        if (frameHeight <= 0) return
        if (frameHeight == lastOverlayTargetHeight && lastOverlayTextSizePx > 0f) return

        val fontPx = (frameHeight * TEXT_SCALE).coerceIn(TEXT_MIN_PX, TEXT_MAX_PX)
        paintText.textSize = fontPx

        lastOverlayTargetHeight = frameHeight
        lastOverlayTextSizePx = fontPx
    }

    private fun overlayPadding(frameHeight: Int): Float {
        // padding ikut skala agar box tidak terlalu tebal di resolusi kecil
        return (frameHeight * PADDING_SCALE).coerceIn(PADDING_MIN_PX, PADDING_MAX_PX)
    }

    private fun processTextToBitmapSafe(src: Bitmap): Bitmap {
        if (src.isRecycled) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val bitmap = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)

        // >>> FIX UTAMA: font & padding mengikuti ukuran frame <<<
        ensureOverlayTextSize(bitmap.height)
        val pad = overlayPadding(bitmap.height)

        val formatted = if (android.os.Build.VERSION.SDK_INT >= 26)
            ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
        else SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())

        val info = if (patientNrm.isEmpty()) patientRs else "$patientRs/$patientNrm"

        // Baseline text (bawah)
        val baselineY = bitmap.height - pad

        // === Right-bottom date box (dynamic width) ===
        val dateTextW = paintText.measureText(formatted)
        val dateBoxW = dateTextW + (pad * 2f)
        val dateBoxH = (paintText.textSize + pad * 1.6f).coerceAtLeast(pad * 2.2f)

        val right = bitmap.width.toFloat()
        val left = (right - dateBoxW).coerceAtLeast(0f)
        val bottom = bitmap.height.toFloat()
        val top = (bottom - dateBoxH).coerceAtLeast(0f)

        canvas.drawRect(left, top, right, bottom, paintDateBg)
        canvas.drawText(formatted, left + pad, baselineY, paintText)

        // === Left-bottom info box (dynamic width) ===
        val infoTextW = paintText.measureText(info)
        val infoBoxW =
            (infoTextW + pad * 2f).coerceAtMost(bitmap.width * 0.75f) // batasi supaya aman
        val infoLeft = 0f
        val infoRight = (infoLeft + infoBoxW).coerceAtMost(bitmap.width.toFloat())
        val infoTop = top // sejajarkan tinggi box bawah
        val infoBottom = bottom

        canvas.drawRect(infoLeft, infoTop, infoRight, infoBottom, paintBox)

        // Kalau text terlalu panjang sampai melewati box, kita potong (ellipsize manual sederhana)
        val maxTextW = (infoRight - infoLeft - pad * 2f).coerceAtLeast(0f)
        val infoDraw = ellipsizeToWidth(info, paintText, maxTextW)

        canvas.drawText(infoDraw, infoLeft + pad, baselineY, paintText)

        return bitmap
    }

    private fun ellipsizeToWidth(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f) return ""
        if (paint.measureText(text) <= maxWidth) return text
        val ell = "…"
        val ellW = paint.measureText(ell)
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi) / 2
            val sub = text.substring(0, mid)
            val w = paint.measureText(sub) + ellW
            if (w <= maxWidth) lo = mid + 1 else hi = mid
        }
        val cut = (lo - 1).coerceAtLeast(0)
        return text.substring(0, cut) + ell
    }

    // =====================================================================
    // STATISTICS
    // =====================================================================

    private fun startStatisticsJob() {
        statisticsJob?.cancel()
        statisticsJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                if (binding.ivVideoImage.isStarted()) {
                    val s: Statistics = binding.ivVideoImage.statistics
                    binding.tvStatistics2?.text =
                        "Decoder: ${s.videoDecoderType.toString().lowercase()} " +
                                "${if (s.videoDecoderName.isNullOrEmpty()) "" else "(${s.videoDecoderName})"}\n" +
                                "Latency: ${s.videoDecoderLatencyMsec}ms\n" +
                                "Resolution: ${ivVideoImageResolution.first}×${ivVideoImageResolution.second}"
                }
                delay(1000)
            }
        }
    }

    // =====================================================================
    // SESSION METADATA
    // =====================================================================

    private fun saveSessionMetadata() {
        val dir = sessionDir ?: return
        if (isMetadataSaved) return
        runCatching {
            File(dir, "session.json").writeText(JSONObject().apply {
                put("nama", patientNama); put("nik", patientNik); put("nrm", patientNrm)
                put("rs", patientRs); put("dob_utc", patientDobUtc)
                put("saved_at", System.currentTimeMillis())
            }.toString(2))
            isMetadataSaved = true
        }.onFailure { Log.e(TAG, "session.json error", it) }
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private fun overlayInfoText() =
        if (patientNrm.isEmpty()) patientRs else "$patientRs/$patientNrm"

    private fun applyZoomMatrix() {
        val m = android.graphics.Matrix()
        m.postScale(currentScale, currentScale, focusX, focusY)
        binding.ivVideoImage.imageMatrix = m
    }

    private fun setKeepScreenOn(enable: Boolean) {
        activity?.apply {
            if (enable) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateStatusBarColor() {
        val color = if (isLandscape()) R.color.colorBlack else R.color.colorButton
        requireActivity().window.statusBarColor = ContextCompat.getColor(requireContext(), color)
    }

    private fun startOverlayClock() {
        if (clockJob?.isActive == true) return
        clockJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                binding.tvOverlayClock.text =
                    if (android.os.Build.VERSION.SDK_INT >= 26)
                        ZonedDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
                    else
                        SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())
                delay(1000)
            }
        }
    }

    private fun stopStreamAndExit() {
        stopVideoRecording()
        if (binding.ivVideoImage.isStarted()) binding.ivVideoImage.stop()
        statisticsJob?.cancel()
        binding.vShutterImage.apply { alpha = 1f; visibility = View.VISIBLE }
        startActivity(Intent(requireContext(), HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("open_tab", "media")
        })
    }

    private val hudTick = object : Runnable {
        override fun run() {
            if (!record.get()) return
            val e = android.os.SystemClock.elapsedRealtime() - recordStartElapsedMs
            binding.tvRecordTimer.text = String.format(
                "%02d:%02d:%02d",
                e / 1000 / 3600, e / 1000 % 3600 / 60, e / 1000 % 60
            )
            hudHandler.postDelayed(this, 1000L)
        }
    }

    // =====================================================================
    // THUMBNAIL / MEDIA
    // =====================================================================

    private fun refreshThumbs() {
        val parent = sessionDir ?: return
        val imgs =
            File(parent, "Snapshots").listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
                .orEmpty()
        val vids =
            File(parent, "Video").listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
                .orEmpty()
        val merged = (imgs.map { MediaItem(it, MediaType.IMAGE) } +
                vids.map { MediaItem(it, MediaType.VIDEO) })
            .sortedByDescending { it.file.lastModified() }
        allMediaItems = merged
        val empty = merged.isEmpty()
        binding.tvEmptyThumbs?.visibility = if (empty) View.VISIBLE else View.GONE
        binding.tvImgNoMedia?.visibility = if (empty) View.VISIBLE else View.GONE
        binding.tvImgSubtitleNoMedia?.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvThumbs.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvMedia?.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvMediaTgl?.visibility = if (empty) View.GONE else View.VISIBLE
        binding.btnSimpanCase.visibility = if (empty) View.GONE else View.VISIBLE
        thumbsAdapter.submitList(merged)
    }

    private fun enterSelectionMode() {
        selectionMode = true
        binding.topAppBar.menu.clear()
        binding.topAppBar.inflateMenu(R.menu.menu_video_fragment_select)
        binding.topAppBar.title = "0 dipilih"
        binding.topAppBar.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                R.id.action_delete_selected -> confirmDeleteSelected()
                R.id.action_done_select -> exitSelectionMode()
            }; true
        }
        thumbsAdapter.setSelectionMode(true)
    }

    private fun confirmDeleteSelected() {
        val files = thumbsAdapter.getSelectedItems()
        if (files.isEmpty()) {
            Toast.makeText(requireContext(), "Belum ada yang dipilih", Toast.LENGTH_SHORT)
                .show(); return
        }
        MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
            .setTitle("Hapus ${files.size} item?")
            .setMessage("Tindakan ini tidak dapat dibatalkan.")
            .setPositiveButton("Hapus") { _, _ -> deleteFiles(files) }
            .setNegativeButton("Batal", null).show()
    }

    private fun deleteFiles(files: List<File>) {
        var ok = 0
        var fail = 0
        val deleted = mutableListOf<String>()
        files.forEach { f ->
            if (runCatching { f.delete() }.isSuccess) {
                ok++; deleted.add(f.absolutePath)
            } else fail++
        }
        if (deleted.isNotEmpty()) android.media.MediaScannerConnection.scanFile(
            requireContext(),
            deleted.toTypedArray(),
            null,
            null
        )
        refreshThumbs(); exitSelectionMode()
        Toast.makeText(requireContext(), "Hapus: $ok sukses, $fail gagal", Toast.LENGTH_SHORT)
            .show()
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

    // =====================================================================
    // DIALOGS
    // =====================================================================

    private fun showExitConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
            .setTitle("Selesaikan Sesi?")
            .setMessage("Apakah Anda yakin ingin keluar dan menyelesaikan sesi sekarang?")
            .setPositiveButton("Selesai") { _, _ -> stopStreamAndExit() }
            .setNegativeButton("Batal", null).create()
            .also { it.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom); it.show() }
    }

    private fun showSaveConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
            .setTitle("Konfirmasi")
            .setMessage("Pastikan pekerjaan telah selesai, sebelum menyimpan media")
            .setNegativeButton("Kembali", null)
            .setPositiveButton("Simpan") { _, _ ->
                apiDelegate.completeSession {
                    showSavingProgressAndExecute()
                }
            }.create()
            .also { it.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom); it.show() }
    }

    private fun showSavingProgressAndExecute() {
        val pv = layoutInflater.inflate(R.layout.dialog_progress_saving, null)
        val pd = MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
            .setView(pv).setCancelable(false).create()
        pd.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom); pd.show()
        val bar = pv.findViewById<LinearProgressIndicator>(R.id.progress)
            .also { it.isIndeterminate = false; it.max = 100 }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            repeat(10) { bar.setProgressCompat((it + 1) * 10, true); delay(50) }
            withContext(Dispatchers.IO) { delay(500); saveSessionMetadata() }
            if (selectionMode) {
                val keep = thumbsAdapter.getSelectedItems().toSet()
                thumbsAdapter.currentList.map { it.file }.filterNot { keep.contains(it) }
                    .forEach { runCatching { it.delete() } }
            }
            pd.dismiss()
            if (selectionMode) {
                exitSelectionMode(); refreshThumbs()
            }
            showSaveSuccessDialog()
        }
    }

    private fun showSaveSuccessDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_save_success, null)
        val d = MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
            .setView(v).setCancelable(true).create()
        d.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom); d.show()
        v.findViewById<TextView>(R.id.tvAction)
            ?.setOnClickListener { d.dismiss(); stopStreamAndExit() }
    }

    private fun showPatientInfoBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(layoutInflater.inflate(R.layout.bs_patient_info, null))
        dialog.findViewById<ImageButton>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }
        val sdf = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("id", "ID")).apply {
            timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        }
        dialog.findViewById<TextView>(R.id.tvTanggal)?.text = sdf.format(Date())
        val namaSafe = patientNama.ifBlank { "-" }
        dialog.findViewById<TextView>(R.id.tvNama)?.text =
            if (patientAge > 0) "$namaSafe ($patientRs)" else namaSafe
        dialog.findViewById<TextView>(R.id.tvNik)?.text = patientNik.ifBlank { "-" }
        dialog.findViewById<TextView>(R.id.tvDob)?.text =
            if (patientDobUtc > 0L) SimpleDateFormat("dd/MM/yyyy", Locale("id", "ID")).format(
                Date(patientDobUtc)
            ) else "-"
        dialog.findViewById<TextView>(R.id.tvNrm)?.text =
            patientNrm.ifBlank { "Tidak ada nomor rekam medis" }
        dialog.show()
    }

    // =====================================================================
    // PINCH ZOOM (extension pada View)
    // =====================================================================

    private fun View.enablePinchZoom(minSc: Float = 1f, maxSc: Float = 3.5f) {
        var scale = 1f
        val sd = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    val ns = (scale * d.scaleFactor).coerceIn(minSc, maxSc)
                    pivotX = d.focusX; pivotY = d.focusY
                    scaleX = ns; scaleY = ns; scale = ns; return true
                }
            })
        val td = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(150)
                    .start()
                scale = 1f; return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                dx: Float,
                dy: Float
            ): Boolean {
                if (scale > 1f) {
                    translationX -= dx; translationY -= dy
                    val mX = width * (scale - 1f) / 2f
                    val mY = height * (scale - 1f) / 2f
                    translationX = translationX.coerceIn(-mX, mX)
                    translationY = translationY.coerceIn(-mY, mY)
                }
                return scale > 1f
            }
        })
        setOnTouchListener { _, ev ->
            sd.onTouchEvent(ev); td.onTouchEvent(ev); sd.isInProgress || scale > 1f
        }
    }

    companion object {
        private val TAG = VideoFragmentMobile::class.java.simpleName

        /** Encoder settings — disesuaikan untuk STB KitKat–Nougat */
        const val STB_WIDTH = 640
        const val STB_HEIGHT = 360
        const val STB_FPS = 15
        const val STB_BITRATE = 1_500_000 // 1.5 Mbps

        // ===== Overlay scaling =====
        // 0.035f = 3.5% tinggi frame (480 -> ~16.8px)
        private const val TEXT_SCALE = 0.035f
        private const val TEXT_MIN_PX = 14f
        private const val TEXT_MAX_PX = 42f

        // Padding scale
        private const val PADDING_SCALE = 0.03f
        private const val PADDING_MIN_PX = 12f
        private const val PADDING_MAX_PX = 32f
    }
}