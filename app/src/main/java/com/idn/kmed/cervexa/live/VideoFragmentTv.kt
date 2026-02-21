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
import com.idn.kmed.cervexa.HomeActivity
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.databinding.FragmentVideoTvBinding
import com.idn.kmed.cervexa.record.RealtimeBitmapEncoder
import com.idn.kmed.cervexa.utils.*
import kotlinx.coroutines.*
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import java.io.File
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VideoFragmentTv - FIXED VERSION
 * Fix: Removed videoScale = SURFACE_FILL and setWindowSize() from startVlcStream()
 *      to prevent blank screen when container size is 0 during init.
 *      setWindowSize() is only used in reattachVlcViews() where layout is guaranteed ready.
 * Also includes: Session.json creation, Auto-Crop in Landscape, Image Enhancement
 */
class VideoFragmentTv : Fragment(), IVLCVout.Callback {

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
    private var patientAge: Int = 0
    private var snapshotsDir: File? = null

    // Timer untuk jam overlay
    private var clockJob: Job? = null

    // ==== VLC Components ====
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var textureView: TextureView? = null

    // ==== PHONE CAMERA COMPONENTS (CAMERAX) ====
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
    private val today = Date()
    private val formatter = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
    private val formattedDate = formatter.format(today)

    // === Gesture / Zoom ===
    private var currentScale = 1f
    private var minScale = 1f
    private var maxScale = 5f
    private var focusX = 0f
    private var focusY = 0f

    // === IMAGE ENHANCEMENT ===
    private var brightness = 0f
    private var contrast = 1.2f
    private var saturation = 1.1f
    private val colorMatrix = ColorMatrix()
    private val paint = Paint().apply {
        isAntiAlias = false
        isDither = false
    }

    // ====== STATE (BASE CENTER + PAN) ======
    private var baseScaleVlc = 1f
    private var baseTxVlc = 0f
    private var baseTyVlc = 0f
    private var panTxVlc = 0f
    private var panTyVlc = 0f
    private var panTxPhone = 0f
    private var panTyPhone = 0f

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let { args ->
            patientNama = args.getString("patient_nama").orEmpty()
            patientNik = args.getString("patient_nik").orEmpty()
            patientRs = args.getString("patient_rs").orEmpty()
            patientNrm = args.getString("patient_nrm").orEmpty()
            patientDobUtc = args.getLong("patient_dob_utc", -1L)
            patientAge = PatientUtils.calculateAge(patientDobUtc)

            sessionDir = args.getString("sessionDirPath")
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it) }

            Log.d(TAG, "=== ARGUMENTS RECEIVED ===")
            Log.d(TAG, "sessionDirPath: ${args.getString("sessionDirPath")}")
            Log.d(TAG, "sessionDir: ${sessionDir?.absolutePath}")
        }

        // FALLBACK: Gunakan StorageUtils untuk konsistensi
        if (sessionDir == null) {
            Log.w(TAG, "sessionDir is NULL! Creating fallback directories...")

            val dateFolder = StorageUtils.todayDateFolderWIB()
            val patientFolder = if (patientNik.isNotBlank()) {
                "${patientNik}_${patientNama.replace(" ", "_")}"
            } else {
                "Patient_Unknown_${System.currentTimeMillis()}"
            }

            sessionDir = StorageUtils.ensureSessionDir(
                requireContext(),
                dateFolder,
                patientFolder
            )

            Log.d(TAG, "Fallback sessionDir created: ${sessionDir?.absolutePath}")
        }

        // Create subdirectories + session.json
        sessionDir?.let { parent ->
            snapshotsDir = StorageUtils.ensureChildDir(parent, "Snapshots")
            videosDir = StorageUtils.ensureChildDir(parent, "Video")

            Log.d(TAG, "Snapshots dir: ${snapshotsDir?.absolutePath}")
            Log.d(TAG, "Video dir: ${videosDir?.absolutePath}")

            // CREATE session.json FILE
            val sessionJson = File(parent, "session.json")
            if (!sessionJson.exists()) {
                try {
                    val jsonContent = org.json.JSONObject().apply {
                        put("nama", patientNama)
                        put("nik", patientNik)
                        put("rs", patientRs)
                        put("nrm", patientNrm)
                        put("dob_utc", patientDobUtc)
                        put(
                            "created_at",
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                        )
                        put("session_type", "colposcopy")
                        put("app_version", "1.0")
                    }
                    StorageUtils.writeSessionMetadata(parent, jsonContent.toString(2))
                    Log.d(TAG, "session.json created: ${sessionJson.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create session.json: ${e.message}", e)
                }
            }
        } ?: run {
            Log.e(TAG, "CRITICAL: sessionDir is still NULL!")
        }

        // Load image enhancement settings
        brightness = prefs.getFloat("image_brightness", 0f)
        contrast = prefs.getFloat("image_contrast", 1.2f)
        saturation = prefs.getFloat("image_saturation", 1.1f)
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
        val infoText = if (patientNrm.isEmpty()) "$patientRs" else "$patientRs/$patientNrm"
        binding.tvOverlayInfo.text = infoText
        startOverlayClock()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged: orientation=${newConfig.orientation}")

        toggleSystemUI()

        val infoText = if (patientNrm.isEmpty()) "$patientRs" else "$patientRs/$patientNrm"
        binding.tvOverlayInfo.text = infoText

        binding.videoContainer.postDelayed({ reattachVlcViews() }, 300)

        view?.post {
            if (usePhoneCamera && phoneCameraView != null) {
                currentScale = 1f
                applyZoomAndPan()
            }
            if (::thumbsAdapter.isInitialized) {
                binding.rvThumbs.adapter = thumbsAdapter
            }
            ensureResourcesAvailable()
        }
    }

    private fun toggleSystemUI() {
        val window = requireActivity().window
        if (isLandscape()) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                .let { controller ->
                    controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior =
                        androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
        } else {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            updateStatusBarColor()
        }
    }

    private fun ensureResourcesAvailable() {
        if (!isAdded) return
        try {
            if (usePhoneCamera) {
                val pv = phoneCameraView
                if (pv?.display == null) {
                    Log.w(TAG, "Phone camera lost display, re-initializing...")
                    view?.post { if (isAdded) startPhoneCamera() }
                }
            } else {
                val mp = mediaPlayer
                if (mp != null && !mp.isPlaying) {
                    Log.w(TAG, "MediaPlayer stopped, attempting to resume...")
                    view?.post {
                        if (isAdded) {
                            try {
                                mp.play()
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to resume", e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in ensureResourcesAvailable", e)
        }
    }

    // ===== ZOOM/PAN + COLOR ENHANCEMENT =====
    private fun applyZoomAndPan() {
        if (!isAdded) return

        if (usePhoneCamera) {
            val pv = phoneCameraView ?: return
            val scale = currentScale.coerceIn(minScale, maxScale)
            pv.pivotX = focusX
            pv.pivotY = focusY
            pv.scaleX = scale
            pv.scaleY = scale
            if (scale <= 1.01f) {
                panTxPhone = 0f
                panTyPhone = 0f
            }
            pv.translationX = panTxPhone
            pv.translationY = panTyPhone
            return
        }

        val tv = textureView ?: return
        val scale = currentScale.coerceIn(minScale, maxScale)
        tv.pivotX = focusX
        tv.pivotY = focusY
        tv.scaleX = baseScaleVlc * scale
        tv.scaleY = baseScaleVlc * scale
        if (scale <= 1.01f) {
            panTxVlc = 0f
            panTyVlc = 0f
        }
        tv.translationX = baseTxVlc + panTxVlc
        tv.translationY = baseTyVlc + panTyVlc

        applyColorEnhancement()
    }

    private fun applyColorEnhancement() {
        val tv = textureView ?: return

        val brightnessMatrix = ColorMatrix().apply {
            val scale = 1f + brightness
            setScale(scale, scale, scale, 1f)
        }

        val contrastMatrix = ColorMatrix().apply {
            val scale = contrast
            val translate = (1f - scale) / 2f * 255f
            set(
                floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }

        val saturationMatrix = ColorMatrix().apply {
            setSaturation(saturation)
        }

        colorMatrix.reset()
        colorMatrix.postConcat(brightnessMatrix)
        colorMatrix.postConcat(contrastMatrix)
        colorMatrix.postConcat(saturationMatrix)

        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        tv.post { tv.invalidate() }
    }

    // ===== PHONE CAMERA LOGIC =====
    private fun checkAndStartPhoneCamera() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startPhoneCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startPhoneCamera() {
        textureView?.visibility = View.GONE
        binding.pbLoadingImage.visibility = View.GONE
        binding.vShutterImage.visibility = View.GONE

        phoneCameraView?.let { binding.videoContainer.removeView(it) }

        val pv = PreviewView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        phoneCameraView = pv
        binding.videoContainer.addView(pv)

        currentScale = 1f
        panTxPhone = 0f
        panTyPhone = 0f
        focusX = (binding.videoContainer.width / 2f)
        focusY = (binding.videoContainer.height / 2f)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(pv.surfaceProvider)
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview)
                binding.tvStatusImage?.text = "Mode: Kamera Smartphone"
                Log.d(TAG, "Phone camera started successfully")
            } catch (exc: Exception) {
                Log.e(TAG, "Gagal start CameraX", exc)
                Toast.makeText(
                    requireContext(),
                    "Gagal buka kamera HP: ${exc.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun stopPhoneCamera() {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(requireContext()).get()
            cameraProvider.unbindAll()
        } catch (_: Exception) {
        }
        phoneCameraView?.let { binding.videoContainer.removeView(it) }
        phoneCameraView = null
        textureView?.visibility = View.VISIBLE
        currentScale = 1f
        panTxPhone = 0f
        panTyPhone = 0f
    }

    private fun toggleSourceMode() {
        if (usePhoneCamera) {
            usePhoneCamera = false
            stopPhoneCamera()
            binding.videoContainer.postDelayed({ startVlcStream() }, 300)
            Toast.makeText(requireContext(), "Mode: Alat (RTSP)", Toast.LENGTH_SHORT).show()
        } else {
            usePhoneCamera = true
            stopVlcStream()
            checkAndStartPhoneCamera()
            Toast.makeText(requireContext(), "Mode: Kamera HP", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat("currentScale", currentScale)
        outState.putFloat("panTxVlc", panTxVlc)
        outState.putFloat("panTyVlc", panTyVlc)
        outState.putFloat("panTxPhone", panTxPhone)
        outState.putFloat("panTyPhone", panTyPhone)
        outState.putBoolean("usePhoneCamera", usePhoneCamera)
        outState.putBoolean("recording", record.get())
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        savedInstanceState?.let { bundle ->
            currentScale = bundle.getFloat("currentScale", 1f)
            panTxVlc = bundle.getFloat("panTxVlc", 0f)
            panTyVlc = bundle.getFloat("panTyVlc", 0f)
            panTxPhone = bundle.getFloat("panTxPhone", 0f)
            panTyPhone = bundle.getFloat("panTyPhone", 0f)
            usePhoneCamera = bundle.getBoolean("usePhoneCamera", false)
            view?.post { applyZoomAndPan() }
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
        binding = FragmentVideoTvBinding.inflate(inflater, container, false)

        textureView = binding.textureView
        textureView?.apply {
            scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f
        }

        scaleDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    currentScale =
                        (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
                    focusX = detector.focusX
                    focusY = detector.focusY
                    applyZoomAndPan()
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
                    applyZoomAndPan()
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (currentScale > 1.01f) {
                        if (usePhoneCamera) {
                            panTxPhone -= distanceX
                            panTyPhone -= distanceY
                        } else {
                            panTxVlc -= distanceX
                            panTyVlc -= distanceY
                        }
                        applyZoomAndPan()
                    }
                    return true
                }
            }
        )

        val touchListener = View.OnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            gestureDetector.onTouchEvent(ev)
            true
        }

        textureView?.setOnTouchListener(touchListener)
        binding.videoContainer.setOnTouchListener(touchListener)

        binding.bnStartStopImage?.setOnClickListener {
            if (usePhoneCamera) stopPhoneCamera()
            else if (mediaPlayer?.isPlaying == true) stopStreamAndExit()
            else startVlcStream()
        }

        binding.bnStartStopImage?.setOnLongClickListener {
            toggleSourceMode(); true
        }

        binding.btnEnterLandscape?.setOnClickListener {
            val isPortrait =
                resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            requireActivity().requestedOrientation = if (isPortrait) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }

        binding.btnSnapshot.setOnClickListener {
            Log.d(TAG, ">>> Snapshot button CLICKED <<<")
            takeSnapshot()
        }

        binding.btnRecordVideo.setOnClickListener {
            Log.d(TAG, ">>> Record button CLICKED - state: ${record.get()} <<<")
            if (record.get()) stopVideoRecording() else startVideoRecording()
        }

        binding.btnBackLite?.setOnLongClickListener {
            showImageEnhancementDialog(); true
        }

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
            thumbsAdapter.onStartSelectionRequested = {
                if (!selectionMode) enterSelectionMode()
            }
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

        val infoText = if (patientNrm.isEmpty()) "$patientRs" else "$patientRs/$patientNrm"
        binding.tvOverlayInfo.text = infoText
        startOverlayClock()

        return binding.root
    }

    private fun showImageEnhancementDialog() {
        Toast.makeText(
            requireContext(),
            "Enhancement: Brightness=$brightness, Contrast=$contrast, Saturation=$saturation",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun startOverlayClock() {
        if (clockJob?.isActive == true) return
        clockJob?.cancel()
        clockJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val now = if (android.os.Build.VERSION.SDK_INT >= 26) {
                    ZonedDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
                } else {
                    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())
                }
                binding.tvOverlayClock.text = now
                delay(1000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        toggleSystemUI()
        liveViewModel.loadParams(requireContext())

        if (usePhoneCamera) {
            checkAndStartPhoneCamera()
        } else if (mediaPlayer == null || mediaPlayer?.isPlaying == false) {
            startVlcStream()
        }
    }

    override fun onPause() {
        super.onPause()
        updateStatusBarColor()
        liveViewModel.saveParams(requireContext())
        if (record.get()) stopVideoRecording()
        if (!usePhoneCamera) stopVlcStream()
    }

    private fun updateStatusBarColor() {
        val color = if (isLandscape()) R.color.colorBlack else R.color.colorButton
        requireActivity().window.statusBarColor =
            ContextCompat.getColor(requireContext(), color)
    }

    private fun reattachVlcViews() {
        if (usePhoneCamera) return
        val player = mediaPlayer ?: return
        val vout = player.vlcVout

        // Guard: container must be ready before setWindowSize
        val w = binding.videoContainer.width
        val h = binding.videoContainer.height
        if (w == 0 || h == 0) {
            Log.w(TAG, "reattachVlcViews: container not ready (${w}x${h}), skipping")
            return
        }

        try {
            vout.detachViews()
            vout.setVideoView(textureView)
            // Safe to call setWindowSize here because container is guaranteed ready
            vout.setWindowSize(w, h)
            vout.addCallback(this)
            vout.attachViews(object : IVLCVout.OnNewVideoLayoutListener {
                override fun onNewVideoLayout(
                    vlcVout: IVLCVout?,
                    width: Int,
                    height: Int,
                    visibleWidth: Int,
                    visibleHeight: Int,
                    sarNum: Int,
                    sarDen: Int
                ) {
                    if (width * height == 0) return
                    ivVideoImageResolution = Pair(width, height)
                    textureView?.post {
                        if (!isAdded || textureView == null) return@post
                        applyVlcLayoutAndBaseTransform(
                            width, height, visibleWidth, visibleHeight, sarNum, sarDen
                        )
                    }
                }
            })
            Log.d(TAG, "VLC reattached: ${w}x${h}")
        } catch (e: Exception) {
            Log.e(TAG, "Error reattaching VLC", e)
        }
    }

    private fun startVlcStream() {
        if (usePhoneCamera) return
        binding.pbLoadingImage.visibility = View.VISIBLE
        binding.vShutterImage.visibility = View.VISIBLE

        try {
            val options = ArrayList<String>().apply {
                add("--rtsp-tcp")
                add("--network-caching=150")
                add("--live-caching=150")
                add("--no-audio")
                // FIX: Removed "--vout=gles2" - causes blank screen on some devices/TV
                add("--drop-late-frames")
                add("--skip-frames")
                add("--video-filter=adjust")
                add("--brightness=1.15")
                add("--contrast=1.2")
                add("--saturation=1.1")
                add("--gamma=1.0")
            }

            libVlc = LibVLC(requireContext(), options)

            // FIX: Do NOT set videoScale = SURFACE_FILL — conflicts with applyVlcLayoutAndBaseTransform()
            mediaPlayer = MediaPlayer(libVlc)

            val vout = mediaPlayer!!.vlcVout
            vout.setVideoView(textureView)
            // FIX: Do NOT call vout.setWindowSize() here — container.width/height may be 0 at this point
            // setWindowSize is handled in reattachVlcViews() after layout is ready
            vout.addCallback(this)

            vout.attachViews(object : IVLCVout.OnNewVideoLayoutListener {
                override fun onNewVideoLayout(
                    vlcVout: IVLCVout?,
                    width: Int,
                    height: Int,
                    visibleWidth: Int,
                    visibleHeight: Int,
                    sarNum: Int,
                    sarDen: Int
                ) {
                    if (width * height == 0) return
                    ivVideoImageResolution = Pair(width, height)
                    textureView?.post {
                        if (!isAdded || textureView == null) return@post
                        applyVlcLayoutAndBaseTransform(
                            width, height, visibleWidth, visibleHeight, sarNum, sarDen
                        )
                    }
                }
            })

            val rawUrl = liveViewModel.rtspRequest.value ?: ""
            val user = liveViewModel.rtspUsername.value ?: ""
            val pass = liveViewModel.rtspPassword.value ?: ""
            val finalUrl = if (user.isNotEmpty() && !rawUrl.contains("//$user")) {
                rawUrl.replace("rtsp://", "rtsp://$user:$pass@")
            } else rawUrl

            Log.d(TAG, "Starting RTSP stream: $finalUrl")

            val media = Media(libVlc, Uri.parse(finalUrl))
            media.addOption(":network-caching=300")
            media.addOption(":no-audio")
            mediaPlayer?.media = media
            media.release()

            // Add event listener for debug
            mediaPlayer?.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.EncounteredError -> {
                        Log.e(TAG, "VLC EncounteredError!")
                        activity?.runOnUiThread {
                            binding.tvStatusImage?.text = "Error - Retrying..."
                        }
                    }

                    MediaPlayer.Event.Playing -> {
                        Log.d(TAG, "VLC Playing")
                        activity?.runOnUiThread {
                            binding.pbLoadingImage.visibility = View.GONE
                            binding.vShutterImage.visibility = View.GONE
                            binding.tvStatusImage?.text = "RTSP Connected"
                        }
                    }

                    MediaPlayer.Event.Buffering -> {
                        Log.d(TAG, "VLC Buffering: ${event.buffering}%")
                    }

                    MediaPlayer.Event.EndReached -> {
                        Log.w(TAG, "VLC EndReached")
                    }
                }
            }

            mediaPlayer?.play()

            binding.pbLoadingImage.postDelayed({
                if (isAdded) {
                    binding.pbLoadingImage.visibility = View.GONE
                    binding.vShutterImage.visibility = View.GONE
                }
            }, 3000)

            setKeepScreenOn(true)
            Log.d(TAG, "VLC startVlcStream() called")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VLC: ${e.message}", e)
            binding.pbLoadingImage.visibility = View.GONE
            Toast.makeText(requireContext(), "Gagal konek: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // CORE LOGIC FOR AUTO-CROP IN LANDSCAPE / LETTERBOX IN PORTRAIT
    private fun applyVlcLayoutAndBaseTransform(
        width: Int,
        height: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        sarNum: Int,
        sarDen: Int
    ) {
        val tv = textureView ?: return
        val containerW = binding.videoContainer.width
        val containerH = binding.videoContainer.height

        if (containerW <= 0 || containerH <= 0) {
            Log.w(TAG, "Container not ready, retry...")
            binding.videoContainer.postDelayed({
                applyVlcLayoutAndBaseTransform(
                    width, height, visibleWidth, visibleHeight, sarNum, sarDen
                )
            }, 100)
            return
        }

        var videoW = if (visibleWidth > 0) visibleWidth.toFloat() else width.toFloat()
        var videoH = if (visibleHeight > 0) visibleHeight.toFloat() else height.toFloat()

        if (sarNum > 0 && sarDen > 0) {
            videoW = videoW * sarNum / sarDen
        }

        val videoAspect = videoW / videoH
        val containerAspect = containerW.toFloat() / containerH.toFloat()
        val isLandscapeMode = isLandscape()

        Log.d(
            TAG,
            "Layout: Container=${containerW}x${containerH}, Video=${videoW.toInt()}x${videoH.toInt()}, Landscape=$isLandscapeMode"
        )

        val finalW: Int
        val finalH: Int

        if (isLandscapeMode) {
            // LANDSCAPE: CROP (Fill Screen) — zoom video until it covers the whole container
            if (containerAspect > videoAspect) {
                finalW = containerW
                finalH = (containerW / videoAspect).toInt()
            } else {
                finalH = containerH
                finalW = (containerH * videoAspect).toInt()
            }
        } else {
            // PORTRAIT: FIT (Letterbox) — fit video inside container
            if (containerAspect > videoAspect) {
                finalH = containerH
                finalW = (containerH * videoAspect).toInt()
            } else {
                finalW = containerW
                finalH = (containerW / videoAspect).toInt()
            }
        }

        tv.layoutParams = tv.layoutParams.apply {
            this.width = finalW
            this.height = finalH
        }

        baseScaleVlc = 1f
        baseTxVlc = (containerW - finalW) / 2f
        baseTyVlc = (containerH - finalH) / 2f
        panTxVlc = 0f
        panTyVlc = 0f

        if (currentScale <= 1.01f) {
            focusX = containerW / 2f
            focusY = containerH / 2f
        }

        applyZoomAndPan()
        Log.d(TAG, "Transform: Final=${finalW}x${finalH}, BaseTrans=($baseTxVlc, $baseTyVlc)")
    }

    private fun stopVlcStream() {
        mediaPlayer?.setEventListener(null)
        mediaPlayer?.stop()
        mediaPlayer?.vlcVout?.detachViews()
        mediaPlayer?.release()
        libVlc?.release()
        mediaPlayer = null
        libVlc = null

        binding.tvStatusImage?.text = "Disconnected"
        binding.vShutterImage.visibility = View.VISIBLE
        setKeepScreenOn(false)

        baseScaleVlc = 1f
        baseTxVlc = 0f
        baseTyVlc = 0f
        panTxVlc = 0f
        panTyVlc = 0f
        currentScale = 1f
    }

    private fun stopStreamAndExit() {
        val intent = Intent(requireContext(), HomeActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra("open_tab", "media")
        startActivity(intent)
        requireActivity().finish()
    }

    private fun startVideoRecording() {
        Log.d(TAG, "=== startVideoRecording() ===")

        var dir = videosDir
        if (dir == null || !dir.exists()) {
            Log.w(TAG, "Video dir unavailable, creating...")
            sessionDir?.let { parent ->
                dir = StorageUtils.ensureChildDir(parent, "Video")
                videosDir = dir
            }
        }

        if (dir == null || !dir.exists()) {
            Log.e(TAG, "Failed to create video directory!")
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), "Gagal membuat direktori video", Toast.LENGTH_LONG)
                    .show()
            }
            return
        }

        val out = File(dir, "vid_${StorageUtils.timestampWIB()}.mp4")
        videoOutputFile = out
        val recWidth = 1280
        val recHeight = 720

        try {
            recorder = RealtimeBitmapEncoder(requireContext(), recWidth, recHeight, out)
            recorder.start()
            record.set(true)
            recordStartElapsedMs = android.os.SystemClock.elapsedRealtime()
            startFrameGrabber(recWidth, recHeight)

            binding.recordHud.visibility = View.VISIBLE
            hudHandler.post(hudTick)
            binding.btnRecordVideo.imageTintList =
                android.content.res.ColorStateList.valueOf(Color.RED)

            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), "⏺️ MULAI MEREKAM", Toast.LENGTH_SHORT).show()
            }

            Log.d(TAG, "Recording started: ${out.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Recording ERROR: ${e.message}", e)
            record.set(false)
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), "Gagal merekam: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun takeSnapshot() {
        Log.d(TAG, "=== takeSnapshot() ===")

        var dir = snapshotsDir
        if (dir == null || !dir.exists()) {
            Log.w(TAG, "Snapshots dir unavailable, creating...")
            sessionDir?.let { parent ->
                dir = StorageUtils.ensureChildDir(parent, "Snapshots")
                snapshotsDir = dir
            }
        }

        if (dir == null || !dir.exists()) {
            requireActivity().runOnUiThread {
                Toast.makeText(
                    requireContext(),
                    "Gagal membuat direktori snapshot",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        val bmp = if (usePhoneCamera) phoneCameraView?.bitmap else textureView?.bitmap

        if (bmp != null) {
            runCatching {
                StorageUtils.saveJpegWithPrefix(dir, processTextToBitmapSafe(bmp), prefix = "ss")
            }.onSuccess { savedFile ->
                Log.d(TAG, "Snapshot saved: ${savedFile.absolutePath}")
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "📸 SNAPSHOT TERSIMPAN!", Toast.LENGTH_SHORT)
                        .show()
                }
                refreshThumbs()
            }.onFailure { error ->
                Log.e(TAG, "Snapshot save failed: ${error.message}", error)
                requireActivity().runOnUiThread {
                    Toast.makeText(
                        requireContext(),
                        "Gagal menyimpan: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            Log.e(TAG, "Bitmap is null!")
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), "Gagal capture bitmap", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopVideoRecording() {
        Log.d(TAG, "=== stopVideoRecording() ===")
        if (!record.get()) return

        recordingJob?.cancel()
        runCatching { recorder.stop() }
        record.set(false)
        hudHandler.removeCallbacks(hudTick)

        binding.recordHud.visibility = View.GONE
        binding.btnRecordVideo.imageTintList =
            android.content.res.ColorStateList.valueOf(Color.WHITE)

        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), "✅ VIDEO TERSIMPAN!", Toast.LENGTH_LONG).show()
        }

        binding.rvThumbs.postDelayed({ refreshThumbs() }, 300)
        Log.d(TAG, "Recording stopped")
    }

    private fun startFrameGrabber(width: Int, height: Int) {
        recordingJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {

            val ENCODER_FPS = 25
            val frameIntervalNs = 1_000_000_000L / ENCODER_FPS // 40_000_000 ns = 40ms
            var nextFrameNs = System.nanoTime()

            while (record.get() && isActive) {

                // Catat waktu SEBELUM getBitmap (termasuk Main dispatcher overhead)
                // nextFrameNs sudah di-set di awal atau di akhir loop sebelumnya
                val bmp = withContext(Dispatchers.Main) {
                    if (usePhoneCamera) phoneCameraView?.bitmap
                    else textureView?.getBitmap(width, height)
                }

                if (bmp != null) {
                    val scaled = if (bmp.width != width || bmp.height != height) {
                        val s = Bitmap.createScaledBitmap(bmp, width, height, true)
                        bmp.recycle()
                        s
                    } else bmp

                    // PTS distamp di dalam submitBitmap() (waktu capture tepat)
                    recorder.submitBitmap(processTextToBitmapSafe(scaled))
                }

                // Hitung sleep: dari nextFrameNs (sudah di-set SEBELUM getBitmap di iterasi ini)
                // sehingga waktu getBitmap + processing sudah ter-absorb
                nextFrameNs += frameIntervalNs
                val sleepNs = nextFrameNs - System.nanoTime()
                when {
                    sleepNs > 1_000_000L -> {
                        // Sleep presisi: ms + sisa ns
                        delay(sleepNs / 1_000_000L)
                    }

                    sleepNs < -frameIntervalNs -> {
                        // Terlalu jauh tertinggal: reset timer
                        nextFrameNs = System.nanoTime()
                    }
                    // else: sedikit terlambat, langsung lanjut tanpa sleep
                }
            }
        }
    }

    private fun processTextToBitmapSafe(src: Bitmap): Bitmap {
        val bitmap = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)

        val formatted = if (android.os.Build.VERSION.SDK_INT >= 26)
            ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
        else SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())

        val canvas = Canvas(bitmap)
        val paintText = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }

        val wText = paintText.measureText(formatted)
        canvas.drawText(formatted, bitmap.width - wText - 20f, bitmap.height - 20f, paintText)

        val info = if (patientNrm.isEmpty()) "$patientRs" else "$patientRs/$patientNrm"
        canvas.drawText(info, 20f, bitmap.height - 20f, paintText)

        return bitmap
    }

    private fun setKeepScreenOn(enable: Boolean) {
        activity?.apply {
            if (enable) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private val hudTick = object : Runnable {
        override fun run() {
            if (!record.get()) return
            val elapsed = android.os.SystemClock.elapsedRealtime() - recordStartElapsedMs
            binding.tvRecordTimer.text = String.format(
                "%02d:%02d:%02d",
                (elapsed / 1000) / 3600,
                ((elapsed / 1000) % 3600) / 60,
                (elapsed / 1000) % 60
            )
            hudHandler.postDelayed(this, 1000L)
        }
    }

    override fun onSurfacesCreated(vlcVout: IVLCVout?) {}
    override fun onSurfacesDestroyed(vlcVout: IVLCVout?) {}

    private fun showSaveConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Konfirmasi")
            .setMessage("Simpan media dan tutup sesi?")
            .setPositiveButton("Simpan") { _, _ -> showSavingProgressAndExecute() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun enterSelectionMode() {
        selectionMode = true
        binding.topAppBar.menu.clear()
        binding.topAppBar.inflateMenu(R.menu.menu_video_fragment_select)
        binding.topAppBar.title = "0 dipilih"
        binding.topAppBar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_delete_selected) confirmDeleteSelected()
            else if (it.itemId == R.id.action_done_select) exitSelectionMode()
            true
        }
        thumbsAdapter.setSelectionMode(true)
    }

    private fun confirmDeleteSelected() {
        val files = thumbsAdapter.getSelectedItems()
        if (files.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Hapus ${files.size} item?")
            .setPositiveButton("Hapus") { _, _ ->
                files.forEach { runCatching { it.delete() } }
                refreshThumbs()
                exitSelectionMode()
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        binding.topAppBar.menu.clear()
        binding.topAppBar.inflateMenu(R.menu.menu_video_fragment)
        binding.topAppBar.title = "Cervexa Colposcope"
        binding.topAppBar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_info_pasien) showPatientInfoBottomSheet()
            else if (it.itemId == R.id.action_pilih) enterSelectionMode()
            true
        }
        thumbsAdapter.setSelectionMode(false)
    }

    private fun refreshThumbs() {
        val parent = sessionDir ?: return
        val imgs = File(parent, "Snapshots").listFiles { f ->
            f.extension.equals("jpg", true)
        }.orEmpty()
        val vids = File(parent, "Video").listFiles { f ->
            f.extension.equals("mp4", true)
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
        binding.btnSimpanCase.visibility = if (isEmpty) View.GONE else View.VISIBLE

        thumbsAdapter.submitList(merged)
    }

    private fun showPatientInfoBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(layoutInflater.inflate(R.layout.bs_patient_info, null))

        val btnClose = dialog.findViewById<ImageButton>(R.id.btnClose)
        val tvNama = dialog.findViewById<TextView>(R.id.tvNama)
        val tvNik = dialog.findViewById<TextView>(R.id.tvNik)
        val tvDob = dialog.findViewById<TextView>(R.id.tvDob)
        val tvNrm = dialog.findViewById<TextView>(R.id.tvNrm)
        val tvTanggal = dialog.findViewById<TextView>(R.id.tvTanggal)

        val sdfNow = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("id", "ID")).apply {
            timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        }
        tvTanggal?.text = sdfNow.format(Date())

        val namaSafe = patientNama.ifBlank { "-" }
        tvNama?.text = if (patientAge > 0) "$namaSafe ($patientRs)" else namaSafe
        tvNik?.text = patientNik.ifBlank { "-" }
        tvDob?.text = if (patientDobUtc > 0L) {
            SimpleDateFormat("dd/MM/yyyy", Locale("id", "ID")).format(Date(patientDobUtc))
        } else "-"
        tvNrm?.text = patientNrm.ifBlank { "Tidak ada nomor rekam medis" }

        btnClose?.setOnClickListener { dialog.dismiss() }
        dialog.show()
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
            pd.dismiss()
            showSaveSuccessDialog()
        }
    }

    private fun showSaveSuccessDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_save_success, null)
        val d = MaterialAlertDialogBuilder(requireContext()).setView(v).create()
        d.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom)
        d.show()

        val tvAction = v.findViewById<TextView>(R.id.tvAction)
        tvAction?.apply {
            isClickable = true
            requestFocus()
            setOnClickListener {
                d.dismiss()
                stopStreamAndExit()
                requireActivity().requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }

    private fun openPreview(position: Int) {
        val paths = ArrayList(allMediaItems.map { it.file.absolutePath })
        val types = ArrayList(allMediaItems.map { it.type.name })

        val targetActivity = if (isLandscape())
            com.idn.kmed.cervexa.gallery.MediaPagerActivityLand::class.java
        else
            com.idn.kmed.cervexa.gallery.MediaPagerActivity::class.java

        val intent = Intent(requireContext(), targetActivity).apply {
            putStringArrayListExtra("paths", paths)
            putStringArrayListExtra("types", types)
            putExtra("index", position)
            putExtra("forceLandscape", isLandscape())
        }
        startActivity(intent)
    }

    companion object {
        private val TAG: String = VideoFragmentTv::class.java.simpleName
        private const val DEBUG = true
    }
}