package com.idn.kmed.cervexa.live

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.idn.kmed.cervexa.home.HomeActivity
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.databinding.FragmentVideoTvBinding

import com.idn.kmed.cervexa.utils.*
import com.idn.kmed.cervexa.utils.PdfReportHelper
import com.idn.kmed.cervexa.utils.PrintHelper
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import com.idn.kmed.cervexa.record.RealtimeBitmapEncoder
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VideoFragmentTv — OPTIMIZED
 *
 * Fix scaling font overlay berdasarkan ukuran frame (REC_HEIGHT / bitmap.height)
 */
class VideoFragmentTv : Fragment() {

    private lateinit var binding: FragmentVideoTvBinding
    private lateinit var liveViewModel: LiveViewModel
    private var ivVideoImageResolution = Pair(0, 0)
    


    // ==== Session / Storage ====
    private var sessionDir: File? = null
    private var patientNama: String = ""
    private var patientNik: String = ""
    private var patientRs: String = ""
    private var patientNrm: String = ""
    private var patientDobUtc: Long = -1L

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
    private var patientAge: Int = 0
    private var snapshotsDir: File? = null

    private var clockJob: Job? = null
    private var liveResyncJob: Job? = null
    private var lastSnapshotMs: Long = 0L  // Catat waktu capture terakhir

    // ==== IJK Components ====
    private var ijkPlayer: IjkMediaPlayer? = null
    private var textureView: TextureView? = null

    // ==== PHONE CAMERA (CameraX) ====
    private var phoneCameraView: PreviewView? = null
    private var usePhoneCamera = false

    // ==== Encode / Flags ====
    private lateinit var recorder: RealtimeBitmapEncoder
    private var recordingJob: Job? = null
    private val record = AtomicBoolean(false)
    private var videoOutputFile: File? = null
    private var videosDir: File? = null

    // === HUD ===
    private var recordStartElapsedMs = 0L
    private val hudHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // === Selection Mode & Media ===
    private var selectionMode = false
    private lateinit var thumbsAdapter: ThumbAdapter
    private var allMediaItems: List<MediaItem> = emptyList()

    // === Tanggal ===
    private val formattedDate by lazy {
        SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
    }

    // === Gesture / Zoom ===
    private var currentScale = 1f
    private val minScale = 1f
    private val maxScale = 5f
    private var focusX = 0f
    private var focusY = 0f

    // === Image Enhancement ===
    private var brightness = 0f
    private var contrast = 1.0f   // netral — tidak ada enhancement warna
    private var saturation = 0.70f // default saturasi dikurangi
    private val colorMatrix = ColorMatrix()

    // ====== STATE (BASE CENTER + PAN) ======
    private var baseScaleVlc = 1f
    private var baseTxVlc = 0f
    private var baseTyVlc = 0f
    private var panTxVlc = 0f
    private var panTyVlc = 0f
    private var panTxPhone = 0f
    private var panTyPhone = 0f

    // =====================================================================
    // Paint objects di-cache di class level
    // =====================================================================
    private val paintDateBg = Paint().apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.FILL
    }
    private val paintDateText = Paint().apply {
        color = Color.WHITE  // Putih agar kontras di background hitam
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
    }
    private val paintText = Paint().apply {
        color = Color.WHITE  // Putih agar terbaca di background hitam
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    private val paintBox = Paint().apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.FILL
    }
    private val paintCaptureEnhance = Paint().apply {
        val cm = android.graphics.ColorMatrix()
        // Saturation direndahkan sedikit (1.05) & contrast 1.05
        val contrast = 1.05f
        val brightness = 0f
        val scale = contrast
        val translate = brightness + (1f - contrast) * 128f
        cm.set(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val sat = android.graphics.ColorMatrix()
        sat.setSaturation(1.05f)
        cm.postConcat(sat)
        colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        isAntiAlias = false
        isDither = false
    }
    private val paintEnhance = Paint().apply { isAntiAlias = false; isDither = false }

    // ===== Overlay Text Scale Cache =====
    private var lastOverlayFontPx: Float = -1f
    private var lastOverlayTargetHeight: Int = -1

    private val prefs by lazy {
        requireContext().getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
    }

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startPhoneCamera()
        else {
            Toast.makeText(requireContext(), "Izin kamera diperlukan", Toast.LENGTH_SHORT).show()
            usePhoneCamera = false
        }
    }

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
            sessionDir =
                args.getString("sessionDirPath")?.takeIf { it.isNotBlank() }?.let { File(it) }
            serverPatientId = args.getInt("patient_id", -1)
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

            val sessionJson = File(parent, "session.json")
            if (!sessionJson.exists()) {
                runCatching {
                    val json = org.json.JSONObject().apply {
                        put("nama", patientNama); put("nik", patientNik)
                        put("rs", patientRs); put("nrm", patientNrm)
                        put("dob_utc", patientDobUtc)
                        put(
                            "created_at",
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                        )
                        put("session_type", "colposcopy"); put("app_version", "1.0")
                    }
                    StorageUtils.writeSessionMetadata(parent, json.toString(2))
                }.onFailure { Log.e(TAG, "session.json error", it) }
            }
        }

        brightness = prefs.getFloat("image_brightness", 0f)
        contrast = prefs.getFloat("image_contrast", 1.2f)
        saturation = prefs.getFloat("image_saturation", 0.70f)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockJob?.cancel()
        stopPhoneCamera()
        prefs.edit().apply {
            putFloat("image_brightness", brightness)
            putFloat("image_contrast", contrast)
            putFloat("image_saturation", saturation)
            apply()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val infoText = if (patientNrm.isEmpty()) patientRs else "$patientRs/$patientNrm"
        binding.tvOverlayInfo.text = infoText
        startOverlayClock()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        toggleSystemUI()
        binding.tvOverlayInfo.text =
            if (patientNrm.isEmpty()) patientRs else "$patientRs/$patientNrm"
        binding.videoContainer.postDelayed({ applyIjkLayout() }, 300)
        view?.post {
            if (usePhoneCamera && phoneCameraView != null) {
                currentScale = 1f; applyZoomAndPan()
            }
            if (::thumbsAdapter.isInitialized) binding.rvThumbs.adapter = thumbsAdapter
            ensureResourcesAvailable()
        }
    }

    override fun onResume() {
        super.onResume()
        toggleSystemUI()
        liveViewModel.loadParams(requireContext())
        if (usePhoneCamera) checkAndStartPhoneCamera()
        else if (ijkPlayer == null || ijkPlayer?.isPlaying == false) startIjkStream()
    }

    override fun onPause() {
        super.onPause()
        updateStatusBarColor()
        liveViewModel.saveParams(requireContext())
        if (record.get()) stopVideoRecording()
        if (!usePhoneCamera) stopIjkStream()
    }

    // =====================================================================
    // UI INFLATION
    // =====================================================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        liveViewModel = ViewModelProvider(this)[LiveViewModel::class.java]
        binding = FragmentVideoTvBinding.inflate(inflater, container, false)
        


        textureView = binding.textureView?.also {
            applyHardwareBrightness(it, 35f)  // Brightness 35f + contrast agar cerah menyamai layar MS2
        }
        textureView?.apply { scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f }

        scaleDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    currentScale =
                        (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
                    focusX = detector.focusX; focusY = detector.focusY
                    applyZoomAndPan(); return true
                }
            })

        gestureDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    currentScale = if (currentScale > 1.01f) 1f else 2f
                    focusX = e.x; focusY = e.y; applyZoomAndPan(); return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    dX: Float,
                    dY: Float
                ): Boolean {
                    if (currentScale > 1.01f) {
                        if (usePhoneCamera) {
                            panTxPhone -= dX; panTyPhone -= dY
                        } else {
                            panTxVlc -= dX; panTyVlc -= dY
                        }
                        applyZoomAndPan()
                    }
                    return true
                }
            })

        val touchListener = View.OnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev); gestureDetector.onTouchEvent(ev); true
        }
        textureView?.setOnTouchListener(touchListener)
        binding.videoContainer.setOnTouchListener(touchListener)

        binding.bnStartStopImage?.setOnClickListener {
            if (usePhoneCamera) stopPhoneCamera()
            else if (ijkPlayer?.isPlaying == true) stopIjkStream()
            else startIjkStream()
        }
        binding.bnStartStopImage?.setOnLongClickListener { toggleSourceMode(); true }
        binding.btnEnterLandscape?.setOnClickListener {
            val isPortrait =
                resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            requireActivity().requestedOrientation =
                if (isPortrait) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        binding.btnSnapshot.setOnClickListener { takeSnapshot() }
        binding.btnRecordVideo.setOnClickListener {
            if (record.get()) stopVideoRecording() else startVideoRecording()
        }
        binding.btnBackLite?.setOnLongClickListener { showImageEnhancementDialog(); true }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    showSaveConfirmDialog()
                }
            })

        binding.topAppBar.setNavigationOnClickListener { showSaveConfirmDialog() }
        binding.btnBackLite?.setOnClickListener { showSaveConfirmDialog() }

        binding.rvThumbs.apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 4)
            thumbsAdapter = ThumbAdapter { _, position -> openPreview(position) }
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

        binding.btnSimpanCase.setOnClickListener { showSaveConfirmDialog() }
        binding.tvMediaTgl?.text = formattedDate

        refreshThumbs()
        binding.tvOverlayInfo.text =
            if (patientNrm.isEmpty()) patientRs else "$patientRs/$patientNrm"
        startOverlayClock()

        return binding.root
    }

    // =====================================================================
    // ZOOM / PAN / COLOR
    // =====================================================================

    private fun applyZoomAndPan() {
        if (!isAdded) return
        if (usePhoneCamera) {
            val pv = phoneCameraView ?: return
            val scale = currentScale.coerceIn(minScale, maxScale)
            pv.pivotX = focusX; pv.pivotY = focusY
            pv.scaleX = scale; pv.scaleY = scale
            if (scale <= 1.01f) {
                panTxPhone = 0f; panTyPhone = 0f
            }
            pv.translationX = panTxPhone; pv.translationY = panTyPhone
            return
        }
        val tv = textureView ?: return
        val scale = currentScale.coerceIn(minScale, maxScale)
        tv.pivotX = focusX; tv.pivotY = focusY
        tv.scaleX = baseScaleVlc * scale; tv.scaleY = baseScaleVlc * scale
        if (scale <= 1.01f) {
            panTxVlc = 0f; panTyVlc = 0f
        }
        tv.translationX = baseTxVlc + panTxVlc; tv.translationY = baseTyVlc + panTyVlc
        applyColorEnhancement()
    }

    private fun applyColorEnhancement() {
        val tv = textureView ?: return
        val bm =
            ColorMatrix().apply { setScale(1f + brightness, 1f + brightness, 1f + brightness, 1f) }
        val cm = ColorMatrix().apply {
            val t = (1f - contrast) / 2f * 255f
            set(
                floatArrayOf(
                    contrast,
                    0f,
                    0f,
                    0f,
                    t,
                    0f,
                    contrast,
                    0f,
                    0f,
                    t,
                    0f,
                    0f,
                    contrast,
                    0f,
                    t,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f
                )
            )
        }
        val sm = ColorMatrix().apply { setSaturation(saturation) }
        colorMatrix.reset(); colorMatrix.postConcat(bm); colorMatrix.postConcat(cm); colorMatrix.postConcat(
            sm
        )
        paintEnhance.colorFilter = ColorMatrixColorFilter(colorMatrix)
        tv.post { tv.invalidate() }
    }

    // =====================================================================
    // PHONE CAMERA
    // =====================================================================

    private fun checkAndStartPhoneCamera() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startPhoneCamera()
        else requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startPhoneCamera() {
        textureView?.visibility = View.GONE
        binding.pbLoadingImage.visibility = View.GONE
        binding.vShutterImage.visibility = View.GONE

        phoneCameraView?.let { binding.videoContainer.removeView(it) }

        val pv = PreviewView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        phoneCameraView = pv
        binding.videoContainer.addView(pv)

        currentScale = 1f; panTxPhone = 0f; panTyPhone = 0f
        focusX = binding.videoContainer.width / 2f; focusY = binding.videoContainer.height / 2f

        ProcessCameraProvider.getInstance(requireContext()).also { future ->
            future.addListener({
                runCatching {
                    val cp = future.get()
                    val preview =
                        Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                    cp.unbindAll()
                    cp.bindToLifecycle(
                        viewLifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview
                    )
                    binding.tvStatusImage?.text = "Mode: Kamera Smartphone"
                }.onFailure {
                    Log.e(TAG, "CameraX gagal", it)
                    Toast.makeText(
                        requireContext(),
                        "Gagal buka kamera HP: ${it.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }, ContextCompat.getMainExecutor(requireContext()))
        }
    }

    private fun stopPhoneCamera() {
        runCatching { ProcessCameraProvider.getInstance(requireContext()).get().unbindAll() }
        phoneCameraView?.let { binding.videoContainer.removeView(it) }
        phoneCameraView = null
        textureView?.visibility = View.VISIBLE
        currentScale = 1f; panTxPhone = 0f; panTyPhone = 0f
    }

    private fun toggleSourceMode() {
        if (usePhoneCamera) {
            usePhoneCamera = false; stopPhoneCamera()
            binding.videoContainer.postDelayed({ startIjkStream() }, 300)
            Toast.makeText(requireContext(), "Mode: Alat (RTSP)", Toast.LENGTH_SHORT).show()
        } else {
            usePhoneCamera = true; stopIjkStream(); checkAndStartPhoneCamera()
            Toast.makeText(requireContext(), "Mode: Kamera HP", Toast.LENGTH_SHORT).show()
        }
    }

    // =====================================================================
    // IJK STREAM
    // =====================================================================

    private val ijkEventListener = IMediaPlayer.OnInfoListener { _, what, _ ->
        when (what) {
            IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                binding.pbLoadingImage.visibility = View.GONE
                binding.vShutterImage.visibility = View.GONE
                binding.tvStatusImage?.text = "RTSP Connected"
                setKeepScreenOn(true)
            }
        }
        true
    }

    private fun startIjkStream() {
        if (usePhoneCamera) return
        binding.pbLoadingImage.visibility = View.VISIBLE
        binding.vShutterImage.visibility = View.VISIBLE

        runCatching {
            val rawUrl = liveViewModel.rtspRequest.value ?: ""
            val user = liveViewModel.rtspUsername.value ?: ""
            val pass = liveViewModel.rtspPassword.value ?: ""
            val finalUrl = if (user.isNotEmpty() && !rawUrl.contains("//$user"))
                rawUrl.replace("rtsp://", "rtsp://$user:$pass@") else rawUrl

            val player = IjkMediaPlayer().apply {
                IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_WARN)

                // ── FORMAT (FFmpeg demuxer) ──
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "nobuffer")
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "udp")
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "max_delay", 0L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 256L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 0L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reorder_queue_size", 0L)

                // ── PLAYER (IjkPlayer internal) ──
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 1L) // 1ms (0=unlimited!)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_buffer_size", 1024L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", 1L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 5L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "an", 1L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1L)

                // ── CODEC (decoder) ──
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_frame", 0L)
            }

            val tv = textureView ?: return
            val st = tv.surfaceTexture ?: run {
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        tv.surfaceTextureListener = null
                        if (isAdded) startIjkStream()
                    }
                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = false
                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                }
                return
            }
            player.setSurface(android.view.Surface(st))
            player.setOnInfoListener(ijkEventListener)
            player.setOnErrorListener { _, _, _ ->
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    binding.tvStatusImage?.text = "Error — Retrying..."
                    binding.pbLoadingImage.visibility = View.GONE
                }
                true
            }
            player.setOnCompletionListener {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    binding.tvStatusImage?.text = "Disconnected"
                    binding.vShutterImage.visibility = View.VISIBLE
                    setKeepScreenOn(false)
                }
            }
            player.setOnVideoSizeChangedListener { _, w, h, sarNum, sarDen ->
                if (w > 0 && h > 0) {
                    ivVideoImageResolution = Pair(w, h)
                    tv.post { if (isAdded) applyIjkLayout(w, h, sarNum, sarDen) }
                }
            }

            player.dataSource = finalUrl
            player.prepareAsync()
            ijkPlayer = player
            startLiveResyncWatchdog()

            binding.pbLoadingImage.postDelayed({
                if (isAdded) {
                    binding.pbLoadingImage.visibility = View.GONE
                    binding.vShutterImage.visibility = View.GONE
                }
            }, 3000)
            setKeepScreenOn(true)
        }.onFailure {
            Log.e(TAG, "IJK start error", it)
            binding.pbLoadingImage.visibility = View.GONE
            Toast.makeText(requireContext(), "Gagal konek: ${it.message}", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun applyIjkLayout(
        width: Int = ivVideoImageResolution.first,
        height: Int = ivVideoImageResolution.second,
        sarNum: Int = 0,
        sarDen: Int = 0
    ) {
        val tv = textureView ?: return
        val cW = binding.videoContainer.width
        val cH = binding.videoContainer.height
        if (cW <= 0 || cH <= 0) {
            binding.videoContainer.postDelayed({
                applyIjkLayout(width, height, sarNum, sarDen)
            }, 100); return
        }
        var videoW = width.toFloat()
        var videoH = height.toFloat()
        if (sarNum > 0 && sarDen > 0) videoW = videoW * sarNum / sarDen

        val vAspect = videoW / videoH
        val cAspect = cW.toFloat() / cH.toFloat()
        val (finalW, finalH) = if (isLandscape()) {
            if (cAspect > vAspect) cW to (cW / vAspect).toInt() else (cH * vAspect).toInt() to cH
        } else {
            if (cAspect > vAspect) (cH * vAspect).toInt() to cH else cW to (cW / vAspect).toInt()
        }

        tv.layoutParams = tv.layoutParams.apply { this.width = finalW; this.height = finalH }
        baseScaleVlc = 1f
        baseTxVlc = (cW - finalW) / 2f; baseTyVlc = (cH - finalH) / 2f
        panTxVlc = 0f; panTyVlc = 0f
        if (currentScale <= 1.01f) {
            focusX = cW / 2f; focusY = cH / 2f
        }
        applyZoomAndPan()
    }

    private fun stopIjkStream() {
        liveResyncJob?.cancel()
        val oldPlayer = ijkPlayer
        ijkPlayer = null
        
        Thread {
            runCatching {
                oldPlayer?.stop()
                oldPlayer?.release()
            }
        }.start()
        
        binding.tvStatusImage?.text = "Disconnected"
        binding.vShutterImage.visibility = View.VISIBLE
        setKeepScreenOn(false)
        baseScaleVlc = 1f; baseTxVlc = 0f; baseTyVlc = 0f
        panTxVlc = 0f; panTyVlc = 0f; currentScale = 1f
    }

    private var resyncJob: Job? = null

    // autoResyncStream DIHAPUS — restart player di TV/STB menyebabkan hang berkepanjangan.
    // STB terlalu lemah untuk reconnect RTSP dengan cepat.
    // User melaporkan stream stuck setelah capture akibat fitur ini.

    /**
     * Anti-drift watchdog dimatikan sementara karena menyebabkan preview freeze.
     */
    private fun startLiveResyncWatchdog() {
        liveResyncJob?.cancel()
        // Watchdog dinonaktifkan sementara
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
            Toast.makeText(requireContext(), "Gagal membuat direktori video", Toast.LENGTH_LONG)
                .show(); return
        }

        val out = File(dir, "vid_${StorageUtils.timestampWIB()}.mp4")
        videoOutputFile = out

        runCatching {
            recorder = RealtimeBitmapEncoder(
                context = requireContext(),
                width = REC_WIDTH,
                height = REC_HEIGHT,
                outputFile = out,
                frameRate = STB_FPS,
                bitRate = STB_BITRATE
            )
            recorder.start()
            record.set(true)
            recordStartElapsedMs = android.os.SystemClock.elapsedRealtime()
            startFrameGrabber()

            binding.recordHud.visibility = View.VISIBLE
            hudHandler.post(hudTick)
            binding.btnRecordVideo.imageTintList =
                android.content.res.ColorStateList.valueOf(Color.RED)
            Toast.makeText(requireContext(), "⏺️ MULAI MEREKAM", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Log.e(TAG, "Recording ERROR", it)
            record.set(false)
            Toast.makeText(requireContext(), "Gagal merekam: ${it.message}", Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun startFrameGrabber() {
        recordingJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val frameIntervalNs = 1_000_000_000L / STB_FPS
            var nextFrameNs = System.nanoTime()

            var poolBitmap: Bitmap? = null

            try {
                while (record.get() && isActive) {

                    val sourceBmp: Bitmap? = withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext null
                        if (usePhoneCamera) {
                            phoneCameraView?.bitmap
                        } else {
                            val tv = textureView ?: return@withContext null
                            if (poolBitmap == null || poolBitmap!!.isRecycled) {
                                poolBitmap = Bitmap.createBitmap(
                                    REC_WIDTH,
                                    REC_HEIGHT,
                                    Bitmap.Config.ARGB_8888
                                )
                            }
                            tv.getBitmap(poolBitmap!!)
                            poolBitmap
                        }
                    }

                    if (sourceBmp != null && !sourceBmp.isRecycled) {
                        val finalBmp = processTextToBitmapSafe(sourceBmp)
                        recorder.submitBitmap(finalBmp)
                        if (finalBmp !== sourceBmp && sourceBmp !== poolBitmap && !sourceBmp.isRecycled) {
                            sourceBmp.recycle()
                        }
                    }

                    nextFrameNs += frameIntervalNs
                    val sleepNs = nextFrameNs - System.nanoTime()
                    if (sleepNs > 0) delay(sleepNs / 1_000_000L) else nextFrameNs =
                        System.nanoTime()
                }
            } finally {
                poolBitmap?.recycle()
                poolBitmap = null
            }
        }
    }

    private fun stopVideoRecording() {
        if (!record.get()) return
        recordingJob?.cancel()
        runCatching { recorder.stop() }
        record.set(false)
        hudHandler.removeCallbacks(hudTick)
        binding.recordHud.visibility = View.GONE
        binding.btnRecordVideo.imageTintList =
            android.content.res.ColorStateList.valueOf(Color.WHITE)
        
        val file = videoOutputFile; videoOutputFile = null
        if (file != null && file.exists()) {
            Toast.makeText(requireContext(), "🎥 VIDEO TERSIMPAN!", Toast.LENGTH_SHORT).show()
            binding.rvThumbs.postDelayed({ refreshThumbs() }, 300)
        } else {
            Toast.makeText(requireContext(), "Gagal menyimpan video", Toast.LENGTH_SHORT).show()
        }
        // autoResync DIHAPUS — menyebabkan hang di TV/STB
    }

    // =====================================================================
    // FIX: Skalakan ukuran text berdasarkan tinggi frame (bitmap.height)
    // =====================================================================
    private fun ensureOverlayTextSize(targetHeight: Int) {
        if (targetHeight <= 0) return
        if (targetHeight == lastOverlayTargetHeight && lastOverlayFontPx > 0f) {
            // sudah sesuai cache
            return
        }

        // === Atur proporsi text terhadap tinggi video ===
        // 0.045f = 4.5% dari tinggi video — lebih besar agar terbaca
        val fontPx = (targetHeight * TEXT_SCALE)
            .coerceIn(TEXT_MIN_PX, TEXT_MAX_PX)

        paintText.textSize = fontPx
        paintDateText.textSize = fontPx
        lastOverlayFontPx = fontPx
        lastOverlayTargetHeight = targetHeight
    }

    private fun processTextToBitmapSafe(src: Bitmap, abnormalityProb: Float = -1f): Bitmap {
        if (src.isRecycled) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        
        // Crop 4 pixel di atas untuk membuang list/garis biru artefak bawaan hardware kamera MS2
        val cropTop = 4
        val safeSrc = if (src.height > cropTop) {
            Bitmap.createBitmap(src, 0, cropTop, src.width, src.height - cropTop)
        } else src
        
        // Kita buat blank bitmap baru agar bisa menggambar safeSrc dengan Paint (untuk apply filter hasil)
        val bitmap = Bitmap.createBitmap(safeSrc.width, safeSrc.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Gambar gambar asli dengan filter enhancement untuk HASIL foto/video
        canvas.drawBitmap(safeSrc, 0f, 0f, paintCaptureEnhance)

        // >>> FIX UTAMA: font mengikuti ukuran frame <<<
        ensureOverlayTextSize(bitmap.height)

        val formatted = if (android.os.Build.VERSION.SDK_INT >= 26)
            ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
        else SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())

        val pad = (bitmap.height * 0.035f).coerceIn(14f, 36f) // padding ikut skala
        val baselineY = bitmap.height - pad

        // === Right-bottom date box (dynamic width) ===
        // Box padding merata agar teks berada tepat di tengah (centered)
        val boxPadX = pad * 1.5f
        val dateTextW = paintDateText.measureText(formatted)
        val dateBoxW = dateTextW + (boxPadX * 2f)
        val dateBoxH = (paintDateText.textSize + pad * 2.2f).coerceAtLeast(pad * 3f)

        val right = bitmap.width.toFloat()
        val left = (right - dateBoxW).coerceAtLeast(0f)
        val bottom = bitmap.height.toFloat()
        val top = (bottom - dateBoxH).coerceAtLeast(0f)

        // Baseline Y untuk menengahkan teks secara vertikal di dalam box
        val dateCenterY = top + (dateBoxH / 2f) - ((paintDateText.descent() + paintDateText.ascent()) / 2f)

        // Draw specific rounded corners: top-left & bottom-left
        val cornerRadius = dateBoxH / 2f
        val rightRadii = floatArrayOf(
            cornerRadius, cornerRadius, // top-left
            0f, 0f,                     // top-right
            0f, 0f,                     // bottom-right
            cornerRadius, cornerRadius  // bottom-left
        )
        val rightPath = android.graphics.Path().apply {
            addRoundRect(
                android.graphics.RectF(left, top, right, bottom),
                rightRadii,
                android.graphics.Path.Direction.CW
            )
        }
        canvas.drawPath(rightPath, paintDateBg)

        // Teks ditengah box (centered)
        canvas.drawText(formatted, left + boxPadX, dateCenterY, paintDateText)

        // === Left-bottom info box (dynamic width, rounded) ===
        val info = if (patientNrm.isEmpty()) patientRs else "$patientRs/$patientNrm"
        val infoTextW = paintText.measureText(info)
        val infoBoxW = (infoTextW + pad * 2f).coerceAtMost(bitmap.width * 0.75f)
        val infoLeft = 0f
        val infoRight = (infoLeft + infoBoxW).coerceAtMost(bitmap.width.toFloat())
        val infoTop = top // sejajarkan tinggi box bawah
        val infoBottom = bottom

        // Draw specific rounded corners: top-right & bottom-right
        val leftRadii = floatArrayOf(
            0f, 0f,                     // top-left
            cornerRadius, cornerRadius, // top-right
            cornerRadius, cornerRadius, // bottom-right
            0f, 0f                      // bottom-left
        )
        val leftPath = android.graphics.Path().apply {
            addRoundRect(
                android.graphics.RectF(infoLeft, infoTop, infoRight, infoBottom),
                leftRadii,
                android.graphics.Path.Direction.CW
            )
        }
        canvas.drawPath(leftPath, paintBox)

        // Kalau text terlalu panjang, potong
        val maxTextW = (infoRight - infoLeft - pad * 2f).coerceAtLeast(0f)
        val infoDraw = if (paintText.measureText(info) <= maxTextW) info
            else info.substring(0, ((info.length * maxTextW / paintText.measureText(info)).toInt()).coerceAtLeast(0)) + "…"
            
        // Hitung juga center Y untuk text sebelah kiri agar simetris
        val infoCenterY = infoTop + ((infoBottom - infoTop) / 2f) - ((paintText.descent() + paintText.ascent()) / 2f)
        canvas.drawText(infoDraw, infoLeft + pad, infoCenterY, paintText)

        // === AI Detection Overlay ===
        if (abnormalityProb >= 0f) {
            // Threshold klinis: lebih baik over-alert daripada miss kasus kanker
            // > 0.55 = ABNORMAL (merah)
            // 0.35 - 0.55 = SUSPECTED (kuning)
            // < 0.35 = NORMAL (hijau)
            val isAbnormal = abnormalityProb > 0.55f
            val isSuspected = abnormalityProb in 0.35f..0.55f
            val displayProb = if (isAbnormal || isSuspected) abnormalityProb else (1f - abnormalityProb)
            val label = when {
                isAbnormal  -> "AI: ABNORMAL (${(abnormalityProb * 100).toInt()}%)"
                isSuspected -> "AI: DICURIGAI (${(abnormalityProb * 100).toInt()}%)"
                else        -> "AI: NORMAL (${((1 - abnormalityProb) * 100).toInt()}%)"
            }
            val labelColor = when {
                isAbnormal  -> Color.RED
                isSuspected -> Color.YELLOW
                else        -> Color.GREEN
            }
            val aiPaintText = Paint(paintText).apply {
                color = labelColor
                isFakeBoldText = true
                setShadowLayer(5f, 0f, 0f, Color.BLACK)
            }
            val aiTextW = aiPaintText.measureText(label)
            canvas.drawText(label, bitmap.width - aiTextW - pad, pad + paintText.textSize, aiPaintText)
        }

        return bitmap
    }

    // =====================================================================
    // SNAPSHOT
    // =====================================================================

    private fun takeSnapshot() {
        var dir = snapshotsDir
        if (dir == null || !dir.exists()) {
            sessionDir?.let {
                dir = StorageUtils.ensureChildDir(it, "Snapshots").also { d -> snapshotsDir = d }
            }
        }
        if (dir == null || !dir!!.exists()) {
            Toast.makeText(requireContext(), "Gagal membuat direktori snapshot", Toast.LENGTH_LONG)
                .show(); return
        }
        // Capture bitmap di main thread (harus dari TextureView di UI thread)
        val bmp = if (usePhoneCamera) phoneCameraView?.bitmap else textureView?.bitmap
        if (bmp == null) {
            Toast.makeText(requireContext(), "Gagal capture bitmap", Toast.LENGTH_SHORT)
                .show(); return
        }
        // Salin bitmap agar aman diproses di background thread
        val bmpCopy = bmp.copy(Bitmap.Config.ARGB_8888, true)
        lastSnapshotMs = System.currentTimeMillis()
        val saveDir = dir!!

        // Pindahkan SEMUA heavy work ke background thread agar UI tidak freeze:
        // - TF Lite inference (detectAbnormality)
        // - Canvas overlay drawing (processTextToBitmapSafe)
        // - Disk I/O (saveJpegWithPrefix)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                StorageUtils.saveJpegWithPrefix(saveDir, processTextToBitmapSafe(bmpCopy), prefix = "ss")
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        Toast.makeText(requireContext(), "📸 SNAPSHOT TERSIMPAN!", Toast.LENGTH_SHORT).show()
                        refreshThumbs()
                    }
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        Toast.makeText(requireContext(), "Gagal menyimpan: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            // Recycle copy setelah selesai
            if (!bmpCopy.isRecycled) bmpCopy.recycle()
        }
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private fun toggleSystemUI() {
        val window = requireActivity().window
        if (isLandscape()) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).let {
                it.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                it.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            updateStatusBarColor()
        }
    }

    private fun updateStatusBarColor() {
        val color = if (isLandscape()) R.color.colorBlack else R.color.colorButton
        requireActivity().window.statusBarColor = ContextCompat.getColor(requireContext(), color)
    }

    private fun ensureResourcesAvailable() {
        if (!isAdded) return
        runCatching {
            if (usePhoneCamera) {
                if (phoneCameraView?.display == null) view?.post { if (isAdded) startPhoneCamera() }
            } else {
                // Media player check replaced by IJK check if needed
            }
        }
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

    private fun setKeepScreenOn(enable: Boolean) {
        activity?.apply {
            if (enable) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun stopStreamAndExit() {
        startActivity(Intent(requireContext(), HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("open_tab", "media")
        })
        requireActivity().finish()
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
            File(parent, "Snapshots").listFiles { f -> f.extension.equals("jpg", true) }.orEmpty()
        val vids =
            File(parent, "Video").listFiles { f -> f.extension.equals("mp4", true) }.orEmpty()
        val merged = (imgs.map { MediaItem(it, MediaType.IMAGE) } +
                vids.map { MediaItem(it, MediaType.VIDEO) })
            .sortedByDescending { it.file.lastModified() }
        allMediaItems = merged
        val empty = merged.isEmpty()
        binding.tvEmptyThumbs?.visibility = if (empty) View.VISIBLE else View.GONE
        binding.tvImgNoMedia?.visibility = if (empty) View.VISIBLE else View.GONE
        binding.tvImgSubtitleNoMedia?.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvThumbs.visibility = if (empty) View.GONE else View.VISIBLE
        binding.btnSimpanCase.visibility = if (empty) View.GONE else View.VISIBLE
        thumbsAdapter.submitList(merged)
    }

    private fun enterSelectionMode() {
        selectionMode = true
        binding.topAppBar.menu.clear()
        binding.topAppBar.inflateMenu(R.menu.menu_video_fragment_select)
        binding.topAppBar.title = "0 dipilih"
        binding.topAppBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_delete_selected -> confirmDeleteSelected()
                R.id.action_done_select -> exitSelectionMode()
            }; true
        }
        thumbsAdapter.setSelectionMode(true)
    }

    private fun confirmDeleteSelected() {
        val files = thumbsAdapter.getSelectedItems()
        if (files.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Hapus ${files.size} item?")
            .setPositiveButton("Hapus") { _, _ -> files.forEach { runCatching { it.delete() } }; refreshThumbs(); exitSelectionMode() }
            .setNegativeButton("Batal", null).show()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        binding.topAppBar.menu.clear()
        binding.topAppBar.inflateMenu(R.menu.menu_video_fragment)
        binding.topAppBar.title = "Cervexa Colposcope"
        binding.topAppBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_info_pasien -> showPatientInfoBottomSheet()
                R.id.action_pilih -> enterSelectionMode()
            }; true
        }
        thumbsAdapter.setSelectionMode(false)
    }

    private fun openPreview(position: Int) {
        val paths = ArrayList(allMediaItems.map { it.file.absolutePath })
        val types = ArrayList(allMediaItems.map { it.type.name })
        val target = if (isLandscape())
            com.idn.kmed.cervexa.gallery.MediaPagerActivityLand::class.java
        else
            com.idn.kmed.cervexa.gallery.MediaPagerActivity::class.java
        startActivity(Intent(requireContext(), target).apply {
            putStringArrayListExtra("paths", paths); putStringArrayListExtra("types", types)
            putExtra("index", position); putExtra("forceLandscape", isLandscape())
        })
    }

    // =====================================================================
    // DIALOGS
    // =====================================================================

    private fun showSaveConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Konfirmasi").setMessage("Simpan media dan tutup sesi?")
            .setPositiveButton("Simpan") { _, _ ->
                showSavingProgressAndExecute()
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun showSavingProgressAndExecute() {
        val pv = layoutInflater.inflate(R.layout.dialog_progress_saving, null)
        val pd =
            MaterialAlertDialogBuilder(requireContext()).setView(pv).setCancelable(false).create()
        pd.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom)
        pd.show()
        val bar = pv.findViewById<LinearProgressIndicator>(R.id.progress)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            repeat(10) { bar.setProgressCompat((it + 1) * 10, true); delay(50) }
            withContext(Dispatchers.IO) { delay(500) }
            pd.dismiss(); showSaveSuccessDialog()
        }
    }

    private fun showSaveSuccessDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_save_success, null)
        val d = MaterialAlertDialogBuilder(requireContext()).setView(v).create()
        d.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom); d.show()
        v.findViewById<TextView>(R.id.tvAction)?.apply {
            isClickable = true; requestFocus()
            setOnClickListener {
                d.dismiss()
                requireActivity().requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                stopStreamAndExit()
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
                Date(
                    patientDobUtc
                )
            ) else "-"
        dialog.findViewById<TextView>(R.id.tvNrm)?.text =
            patientNrm.ifBlank { "Tidak ada nomor rekam medis" }
        dialog.show()
    }

    private fun showImageEnhancementDialog() {
        Toast.makeText(
            requireContext(),
            "Enhancement: B=$brightness C=$contrast S=$saturation", Toast.LENGTH_LONG
        ).show()
    }

    // =====================================================================
    // SAVED STATE
    // =====================================================================

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat("currentScale", currentScale)
        outState.putFloat("panTxVlc", panTxVlc); outState.putFloat("panTyVlc", panTyVlc)
        outState.putFloat("panTxPhone", panTxPhone); outState.putFloat("panTyPhone", panTyPhone)
        outState.putBoolean("usePhoneCamera", usePhoneCamera)
        outState.putBoolean("recording", record.get())
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        savedInstanceState?.let {
            currentScale = it.getFloat("currentScale", 1f)
            panTxVlc = it.getFloat("panTxVlc", 0f); panTyVlc = it.getFloat("panTyVlc", 0f)
            panTxPhone = it.getFloat("panTxPhone", 0f); panTyPhone = it.getFloat("panTyPhone", 0f)
            usePhoneCamera = it.getBoolean("usePhoneCamera", false)
            view?.post { applyZoomAndPan() }
        }
    }

    private fun applyHardwareBrightness(tv: android.view.TextureView, brightnessOffset: Float = 55f) {
        val cm = android.graphics.ColorMatrix()
        // Saturasi diturunkan sedikit ke 55% (0.55f) agar tidak terlalu merah/pekat
        cm.setSaturation(0.55f)
        
        // Trik mengurangi semut: Gunakan Contrast (skala) daripada sekadar Brightness (offset)
        // Contrast 1.08x membuat warna terang cerah menyeimbangi MS2, brightness dinaikkan
        val contrast = 1.08f
        val brightnessAndContrast = android.graphics.ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightnessOffset,
            0f, contrast, 0f, 0f, brightnessOffset,
            0f, 0f, contrast, 0f, brightnessOffset,
            0f, 0f, 0f, 1f, 0f
        ))
        cm.postConcat(brightnessAndContrast)

        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        }
        tv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint)
    }

    companion object {
        private val TAG = VideoFragmentTv::class.java.simpleName

        /** Resolusi & fps rekaman — turunkan untuk STB KitKat-Nougat */
        const val REC_WIDTH = 640
        const val REC_HEIGHT = 480
        const val STB_FPS = 15
        const val STB_BITRATE = 1_500_000 // 1.5 Mbps cukup untuk 640×480

        // ===== Overlay Text Scaling =====
        // 0.045f = 4.5% dari tinggi frame — lebih besar agar terbaca
        private const val TEXT_SCALE = 0.045f

        // Batas aman agar tidak terlalu kecil / terlalu besar
        private const val TEXT_MIN_PX = 18f
        private const val TEXT_MAX_PX = 52f
    }
}