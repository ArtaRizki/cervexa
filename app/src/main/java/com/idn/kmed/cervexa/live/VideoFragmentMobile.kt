package com.idn.kmed.cervexa.live

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
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
import com.jiangdg.usbcamera.UVCCameraHelper
import com.serenegiant.usb.CameraDialog
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.common.AbstractUVCCameraHandler
import com.serenegiant.usb.widget.CameraViewInterface
import android.hardware.usb.UsbDevice
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.idn.kmed.cervexa.home.HomeActivity
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.settings.SettingsActivity.Companion.KEY_CAMERA_ROTATION_DEG
import com.idn.kmed.cervexa.settings.SettingsActivity.Companion.KEY_USE_HW_DECODER
import com.idn.kmed.cervexa.databinding.FragmentVideoMobileBinding
import com.idn.kmed.cervexa.record.RealtimeBitmapEncoder
import com.idn.kmed.cervexa.utils.*
import com.idn.kmed.cervexa.utils.PdfReportHelper
import com.idn.kmed.cervexa.utils.PrintHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
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
 
    private var viaModelHelper: com.idn.kmed.cervexa.ml.ViaModelHelper? = null

    // ==== AI Detection Components ====
    private lateinit var analysisModeManager: com.idn.kmed.cervexa.ml.AnalysisModeManager
    private var aiDetector: com.idn.kmed.cervexa.ml.AiDetector? = null
    private val overlayRenderer = com.idn.kmed.cervexa.ml.OverlayRenderer()
    private var latestAiResult: com.idn.kmed.cervexa.ml.AbnormalityResult =
        com.idn.kmed.cervexa.ml.AbnormalityResult.Idle
    private var aiResultObserverJob: Job? = null
    private var analysisModeObserverJob: Job? = null

    // ==== Low Memory Callback ====
    private val memoryCallback = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) { /* no-op */ }
        override fun onLowMemory() { /* handled by onTrimMemory */ }
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                Log.w(TAG, "Low memory detected (level=$level), deactivating AI AnalysisMode")
                analysisModeManager.deactivate()
                activity?.runOnUiThread {
                    if (isAdded && context != null) {
                        Toast.makeText(
                            requireContext(),
                            "AI dinonaktifkan: memori tidak cukup",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    // Frame terakhir — dilindungi lock hanya saat assignment
    private var lastBitmap: Bitmap? = null
    private val bitmapLock = Any()

    private var statisticsJob: Job? = null
    private var clockJob: Job? = null
    private var ivVideoImageResolution = Pair(0, 0)

    private var mCameraHelper: UVCCameraHelper? = null
    private var isRequest = false
    private var isPreview = false
    private var captureJob: Job? = null

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
        // apiDelegate.createSession(serverPatientId, patientRs) // DIHAPUS - Seperti Commit 1665902

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
        aiResultObserverJob?.cancel()
        analysisModeObserverJob?.cancel()

        // Unregister low memory callback
        try {
            requireContext().applicationContext.unregisterComponentCallbacks(memoryCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering memory callback: ${e.message}")
        }

        // Stop AI analysis — robust cleanup: each step independent
        try {
            aiDetector?.stopAnalysis()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AI analysis: ${e.message}")
        }

        if (record.get()) stopVideoRecording()
        captureJob?.cancel()
        mCameraHelper?.release()

        binding.root.postDelayed({
            synchronized(bitmapLock) { lastBitmap?.recycle(); lastBitmap = null }
        }, 100)

        // Close ViaModelHelper (handles Interpreter.close() failure internally with try-catch-finally)
        try {
            viaModelHelper?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ViaModelHelper: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusBarColor()
        liveViewModel.loadParams(requireContext())
        analysisModeManager.restore()
        mCameraHelper?.registerUSB()
        if (clockJob?.isActive != true) startOverlayClock()
    }

    override fun onPause() {
        super.onPause()
        if (record.get()) stopVideoRecording()
        hudHandler.removeCallbacks(hudTick)
        binding.recordHud.visibility = View.GONE
        mCameraHelper?.unregisterUSB()
        analysisModeManager.persist()
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
        
        viaModelHelper = com.idn.kmed.cervexa.ml.ViaModelHelper(requireContext())

        // Initialize AI Detection components
        analysisModeManager = com.idn.kmed.cervexa.ml.AnalysisModeManager(prefs)
        val acetowhiteDetector = com.idn.kmed.cervexa.ml.AcetowhiteDetector()
        aiDetector = com.idn.kmed.cervexa.ml.AiDetector(
            context = requireContext(),
            viaModelHelper = viaModelHelper!!,
            acetowhiteDetector = acetowhiteDetector,
            analysisModeManager = analysisModeManager
        )

        // Observe AnalysisMode state changes to start/stop analysis
        observeAnalysisMode()

        // Observe AiDetector results for overlay rendering
        observeAiResults()

        // Register low memory callback
        requireContext().applicationContext.registerComponentCallbacks(memoryCallback)

        binding.ivVideoImage.enablePinchZoom()

        mCameraHelper = UVCCameraHelper.getInstance()
        mCameraHelper?.initUSBMonitor(requireActivity(), binding.ivVideoImage as com.serenegiant.usb.widget.CameraViewInterface, listener)
        startFrameCapture()

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
                        val tv = binding.ivVideoImage as com.serenegiant.usb.widget.UVCCameraTextureView
                        val m = tv.getTransform(android.graphics.Matrix())
                        m.postTranslate(-dX, -dY); tv.setTransform(m)
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
            if (isPreview) {
                mCameraHelper?.stopPreview()
                isPreview = false
            } else {
                mCameraHelper?.startPreview(binding.ivVideoImage as com.serenegiant.usb.widget.CameraViewInterface)
                isPreview = true
            }
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

        // AI Toggle button
        binding.btnAiToggle.setOnClickListener {
            analysisModeManager.toggle()
        }

        // Observe AnalysisMode state to update toggle UI
        observeAiToggleUi()

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

    private val listener = object : UVCCameraHelper.OnMyDevConnectListener {
        override fun onAttachDev(device: UsbDevice?) {
            if (!isRequest) {
                isRequest = true
                if (mCameraHelper != null) {
                    mCameraHelper!!.requestPermission(0)
                }
            }
        }
        override fun onDettachDev(device: UsbDevice?) {
            if (isRequest) {
                isRequest = false
                mCameraHelper?.closeCamera()
            }
        }
        override fun onConnectDev(device: UsbDevice?, isConnected: Boolean) {
            if (!isConnected) {
                activity?.runOnUiThread {
                    Toast.makeText(context, "Gagal konek USB Camera", Toast.LENGTH_SHORT).show()
                    binding.tvStatusImage?.text = "Error: connect failed"
                    binding.pbLoadingImage?.visibility = View.GONE
                }
                isPreview = false
            } else {
                isPreview = true
                activity?.runOnUiThread {
                    binding.tvStatusImage?.text = "UVC connected ✓"
                    binding.bnStartStopImage?.text = "Stop UVC"
                    binding.pbLoadingImage?.visibility = View.GONE
                    binding.vShutterImage?.visibility = View.GONE
                    setKeepScreenOn(true)
                }
            }
        }
        override fun onDisConnectDev(device: UsbDevice?) {
            activity?.runOnUiThread {
                binding.tvStatusImage?.text = "UVC disconnected"
                binding.bnStartStopImage?.text = "Start UVC"
                setKeepScreenOn(false)
            }
            isPreview = false
        }
    }

    private fun startFrameCapture() {
        captureJob?.cancel()
        captureJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                val tv = binding.ivVideoImage as? com.serenegiant.usb.widget.UVCCameraTextureView
                if (isPreview && tv?.isAvailable == true) {
                    val bitmap = tv.bitmap
                    if (bitmap != null) {
                        processFrame(bitmap)
                    }
                }
                delay(66) // ~15 FPS
            }
        }
    }

    private fun processFrame(bitmap: Bitmap) {
        if (!isAdded || view == null) return

        val workBitmap: Bitmap = synchronized(bitmapLock) {
            lastBitmap?.recycle()
            val b = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            lastBitmap = b
            b
        }

        if (analysisModeManager.isActive.value) {
            aiDetector?.submitFrame(workBitmap)
        }

        val bmWithOverlay = when (val result = latestAiResult) {
            is com.idn.kmed.cervexa.ml.AbnormalityResult.Detected -> {
                val overlaid = overlayRenderer.renderOverlay(workBitmap, result)
                processTextToBitmapSafe(overlaid)
            }
            else -> processTextToBitmapSafe(workBitmap)
        }

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
            binding.btnRecordVideo.setImageResource(R.drawable.ic_btn_stop)
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
        binding.btnRecordVideo.setImageResource(R.drawable.ic_video)

        runCatching { if (::recorder.isInitialized) recorder.stop() }
            .onFailure { Log.e(TAG, "Encoder stop error", it) }

        val file = videoOutputFile; videoOutputFile = null
        if (file != null && file.exists()) {
            if (!isMetadataSaved) saveSessionMetadata()
            // apiDelegate.uploadVideo(file) // DIHAPUS - Seperti Commit 1665902
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
        if (!isPreview) {
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
                // apiDelegate.uploadSnapshot(savedFile) // DIHAPUS - Seperti Commit 1665902
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

        // AI overlay is now handled by OverlayRenderer — no inline AI drawing here

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
    // AI DETECTION OBSERVERS
    // =====================================================================

    /**
     * Observes AnalysisMode state to update the AI toggle button visual.
     * ON state: green background, green text/icon, "AI ON" label.
     * OFF state: gray background, gray text/icon, "AI OFF" label.
     */
    private fun observeAiToggleUi() {
        viewLifecycleOwner.lifecycleScope.launch {
            analysisModeManager.isActive.collectLatest { isActive ->
                updateAiToggleVisual(isActive)
            }
        }
    }

    /**
     * Updates the AI toggle button visual based on active/inactive state.
     */
    private fun updateAiToggleVisual(isActive: Boolean) {
        if (!isAdded || view == null) return
        val colorActive = 0xFF00C853.toInt()   // Green
        val colorInactive = 0xFF888888.toInt() // Gray

        binding.btnAiToggle.setBackgroundResource(
            if (isActive) R.drawable.bg_ai_toggle_on else R.drawable.bg_ai_toggle_off
        )
        binding.tvAiToggleLabel.text = if (isActive) "AI ON" else "AI OFF"
        binding.tvAiToggleLabel.setTextColor(if (isActive) colorActive else colorInactive)
        binding.ivAiIcon.setColorFilter(if (isActive) colorActive else colorInactive)
    }

    /**
     * Observes AnalysisMode state changes to start/stop the AI analysis pipeline.
     * When AnalysisMode is activated, starts the AiDetector consumer coroutine.
     * When deactivated, stops analysis and resets the result to Idle.
     *
     * Note: We recreate AiDetector on re-activation because stopAnalysis() closes
     * the channel permanently. This ensures clean state on each toggle cycle.
     */
    private fun observeAnalysisMode() {
        analysisModeObserverJob?.cancel()
        analysisModeObserverJob = viewLifecycleOwner.lifecycleScope.launch {
            analysisModeManager.isActive.collectLatest { isActive ->
                if (isActive) {
                    // Recreate AiDetector to get a fresh channel if previous was closed
                    val currentHelper = viaModelHelper
                    if (currentHelper != null) {
                        val acetowhiteDetector = com.idn.kmed.cervexa.ml.AcetowhiteDetector()
                        aiDetector = com.idn.kmed.cervexa.ml.AiDetector(
                            context = requireContext(),
                            viaModelHelper = currentHelper,
                            acetowhiteDetector = acetowhiteDetector,
                            analysisModeManager = analysisModeManager
                        )
                        aiDetector?.startAnalysis(viewLifecycleOwner.lifecycleScope)
                        observeAiResults()
                        showAiLoadingIndicator(true)
                    }
                } else {
                    aiDetector?.stopAnalysis()
                    latestAiResult = com.idn.kmed.cervexa.ml.AbnormalityResult.Idle
                    showAiLoadingIndicator(false)
                }
            }
        }
    }

    /**
     * Observes AiDetector result StateFlow to update the latest AI result
     * used for overlay rendering on each frame.
     */
    private fun observeAiResults() {
        aiResultObserverJob?.cancel()
        aiResultObserverJob = viewLifecycleOwner.lifecycleScope.launch {
            aiDetector?.result?.collectLatest { result ->
                latestAiResult = result
                // Hide loading indicator once we get a result
                if (result !is com.idn.kmed.cervexa.ml.AbnormalityResult.Idle) {
                    showAiLoadingIndicator(false)
                }
            }
        }
    }

    /**
     * Shows or hides a non-blocking loading indicator while AI is processing.
     * Only shows when RTSP stream is active (first frame rendered) to avoid
     * conflicting with RTSP connection loading state.
     */
    private fun showAiLoadingIndicator(show: Boolean) {
        if (!isAdded || view == null) return
        requireActivity().runOnUiThread {
            // Only show AI loading if stream is active (shutter is hidden = first frame rendered)
            if (show && binding.vShutterImage.visibility == View.GONE) {
                binding.pbLoadingImage.visibility = View.VISIBLE
            } else if (!show && analysisModeManager.isActive.value) {
                // Hide only if we're in analysis mode (don't interfere with RTSP loading)
                binding.pbLoadingImage.visibility = View.GONE
            }
        }
    }

    // =====================================================================
    // STATISTICS
    // =====================================================================

    private fun startStatisticsJob() {
        // Obsolete
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

                // AI Detection metadata
                val aiResult = latestAiResult
                if (aiResult is com.idn.kmed.cervexa.ml.AbnormalityResult.Detected) {
                    put("ai_classification", aiResult.label.name)
                    put("ai_confidence_score", aiResult.confidenceScore.toDouble())
                    put("ai_is_fallback", aiResult.isFallback)
                    if (aiResult.boundingBox != null) {
                        put("ai_bounding_box", JSONObject().apply {
                            put("left", aiResult.boundingBox.left.toDouble())
                            put("top", aiResult.boundingBox.top.toDouble())
                            put("right", aiResult.boundingBox.right.toDouble())
                            put("bottom", aiResult.boundingBox.bottom.toDouble())
                        })
                    }
                }
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
        val tv = binding.ivVideoImage as? com.serenegiant.usb.widget.UVCCameraTextureView
        val m = android.graphics.Matrix()
        m.postScale(currentScale, currentScale, focusX, focusY)
        tv?.setTransform(m)
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
        mCameraHelper?.release()
        captureJob?.cancel()
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
                showSavingProgressAndExecute()
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
            ?.setOnClickListener {
                d.dismiss()
                showPrintPromptDialog()   // ← tanya cetak sebelum keluar
            }
    }

    /** Dialog: apakah ingin mencetak setelah simpan? */
    private fun showPrintPromptDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
            .setTitle("Cetak Dokumen?")
            .setMessage("Apakah Anda ingin mencetak atau mengunduh data rekam medis sesi ini?")
            .setPositiveButton("Ya, Cetak / Unduh") { _, _ -> showPrintOptionsDialog() }
            .setNegativeButton("Tidak, Selesai") { _, _ -> stopStreamAndExit() }
            .create()
            .also { it.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom); it.show() }
    }

    /** Dialog: pilih jenis dokumen dan output */
    private fun showPrintOptionsDialog() {
        val options = arrayOf(
            "🖨️  Cetak Data Pasien",
            "📋  Cetak Sesi Lengkap (+ Media)",
            "💾  Unduh PDF Sesi ke Perangkat"
        )
        MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
            .setTitle("Pilih Opsi Cetak / Unduh")
            .setItems(options) { dlg, which ->
                dlg.dismiss()
                when (which) {
                    0 -> generateAndActionPdf(sessionOnly = false, download = false)
                    1 -> generateAndActionPdf(sessionOnly = true, download = false)
                    2 -> generateAndActionPdf(sessionOnly = true, download = true)
                }
                stopStreamAndExit()
            }
            .setNegativeButton("Batal") { _, _ -> stopStreamAndExit() }
            .create()
            .also { it.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom); it.show() }
    }

    /**
     * Generate PDF lalu print atau download.
     * @param sessionOnly  true = sertakan media (snapshot/video), false = data pasien saja
     * @param download     true = simpan ke Downloads, false = buka dialog cetak printer
     */
    private fun generateAndActionPdf(sessionOnly: Boolean, download: Boolean) {
        val ctx = requireContext()
        val ts = System.currentTimeMillis()
        val fname = if (sessionOnly) "cervexa_sesi_${ts}.pdf" else "cervexa_pasien_${ts}.pdf"
        val outFile = File(ctx.cacheDir, fname)

        val snaps = snapshotsDir?.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
        val videos = videosDir?.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()

        lifecycleScope.launch(Dispatchers.IO) {
            val pdf = if (sessionOnly) {
                // Extract AI result for report metadata
                val aiResult = latestAiResult
                val aiClassification = (aiResult as? com.idn.kmed.cervexa.ml.AbnormalityResult.Detected)?.label?.name
                val aiScore = (aiResult as? com.idn.kmed.cervexa.ml.AbnormalityResult.Detected)?.confidenceScore
                val aiIsFallback = (aiResult as? com.idn.kmed.cervexa.ml.AbnormalityResult.Detected)?.isFallback ?: false

                PdfReportHelper.generateSessionPdf(
                    outputFile = outFile,
                    nama = patientNama.ifBlank { "—" },
                    nik = patientNik.ifBlank { "—" },
                    hospitalName = patientRs.ifBlank { "—" },
                    nrm = patientNrm.ifBlank { null },
                    dobUtcMs = patientDobUtc.takeIf { it > 0L },
                    sessionId = apiDelegate.serverSessionId,
                    sessionCode = null,
                    startedAt = null,
                    completedAt = null,
                    snapshotFiles = snaps,
                    videoFiles = videos,
                    aiClassification = aiClassification,
                    aiConfidenceScore = aiScore,
                    aiIsFallback = aiIsFallback
                )
            } else {
                PdfReportHelper.generatePatientPdf(
                    outputFile = outFile,
                    nama = patientNama.ifBlank { "—" },
                    nik = patientNik.ifBlank { "—" },
                    hospitalName = patientRs.ifBlank { "—" },
                    nrm = patientNrm.ifBlank { null },
                    dobUtcMs = patientDobUtc.takeIf { it > 0L },
                    sessionId = apiDelegate.serverSessionId,
                    sessionCode = null,
                    startedAt = null,
                    completedAt = null,
                    snapshotCount = snaps.size,
                    videoCount = videos.size
                )
            }

            withContext(Dispatchers.Main) {
                if (pdf == null) {
                    Toast.makeText(ctx, "Gagal membuat PDF", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                if (download) {
                    val ok = PrintHelper.downloadPdf(ctx, pdf, fname)
                    Toast.makeText(
                        ctx,
                        if (ok) "PDF tersimpan di folder Downloads" else "Gagal menyimpan PDF",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val label = if (sessionOnly) "Sesi Pemeriksaan" else "Data Pasien"
                    PrintHelper.printPdf(requireActivity(), pdf, "Cervexa — $label")
                }
            }
        }
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