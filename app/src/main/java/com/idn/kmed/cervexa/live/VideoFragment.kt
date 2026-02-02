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
import com.idn.kmed.cervexa.databinding.FragmentVideoBinding
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
import kotlin.math.max

class VideoFragment : Fragment(), IVLCVout.Callback {

    private lateinit var binding: FragmentVideoBinding
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

    // ====== IMPORTANT FIX STATE (BASE CENTER + PAN) ======
    private var baseScaleVlc = 1f
    private var baseTxVlc = 0f
    private var baseTyVlc = 0f
    private var panTxVlc = 0f
    private var panTyVlc = 0f

    private var panTxPhone = 0f
    private var panTyPhone = 0f

    // Kalau true: landscape full-screen (center-crop) => hilang black bar kanan/kiri
    private val CROP_IN_LANDSCAPE = true

    private val prefs by lazy {
        requireContext().getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
    }

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    // Permission Launcher untuk Kamera HP
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startPhoneCamera()
        } else {
            Toast.makeText(requireContext(), "Izin kamera diperlukan", Toast.LENGTH_SHORT).show()
            usePhoneCamera = false
        }
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // =====================================================
    // APPLY ZOOM/PAN (VLC + Phone) - FIXED (NO RESET CENTER)
    // =====================================================
    private fun applyZoomAndPan() {
        if (!isAdded) return

        if (usePhoneCamera) {
            val pv = phoneCameraView ?: return
            val scale = currentScale.coerceIn(minScale, maxScale)

            pv.pivotX = focusX
            pv.pivotY = focusY

            pv.scaleX = scale
            pv.scaleY = scale

            // Saat kembali normal, reset PAN saja
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

        // SCALE = baseScaleVlc * zoom
        tv.scaleX = baseScaleVlc * scale
        tv.scaleY = baseScaleVlc * scale

        // Saat kembali normal, reset PAN saja (center base tetap)
        if (scale <= 1.01f) {
            panTxVlc = 0f
            panTyVlc = 0f
        }
        tv.translationX = baseTxVlc + panTxVlc
        tv.translationY = baseTyVlc + panTyVlc
    }

    // ==========================================
    // PHONE CAMERA LOGIC (CAMERAX)
    // ==========================================
    private fun checkAndStartPhoneCamera() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startPhoneCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startPhoneCamera() {
        // Sembunyikan TextureView VLC agar tidak menutupi kamera HP
        textureView?.visibility = View.GONE
        binding.pbLoadingImage.visibility = View.GONE
        binding.vShutterImage.visibility = View.GONE

        // Hapus view lama jika ada
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

        // Reset gesture state
        currentScale = 1f
        panTxPhone = 0f
        panTyPhone = 0f
        focusX = (binding.videoContainer.width / 2f)
        focusY = (binding.videoContainer.height / 2f)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
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

        // Reset gesture state
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            args.getString("sessionDirPath")?.let { p ->
                if (p.isNotBlank()) sessionDir = File(p)
            }
            patientNama = args.getString("patient_nama").orEmpty()
            patientNik = args.getString("patient_nik").orEmpty()
            patientRs = args.getString("patient_rs").orEmpty()
            patientNrm = args.getString("patient_nrm").orEmpty()
            patientDobUtc = args.getLong("patient_dob_utc", -1L)
            patientAge = PatientUtils.calculateAge(patientDobUtc)

            sessionDir = args.getString("sessionDirPath")
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it) }

            sessionDir?.let { parent ->
                snapshotsDir = File(parent, "Snapshots").apply { if (!exists()) mkdirs() }
                videosDir = File(parent, "Video").apply { if (!exists()) mkdirs() }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockJob?.cancel()
        stopPhoneCamera()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val infoText = if (patientNrm.isEmpty()) "$patientRs" else "$patientRs/$patientNrm"
        binding.tvOverlayInfo.text = infoText
        startOverlayClock()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val infoText = if (patientNrm.isEmpty()) "$patientRs" else "$patientRs/$patientNrm"
        binding.tvOverlayInfo.text = infoText

        binding.videoContainer.postDelayed({
            reattachVlcViews()
        }, 300)
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
        textureView = binding.textureView

        textureView?.apply {
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
        }

        // Gesture: pinch to zoom
        scaleDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    currentScale = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
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
            toggleSourceMode()
            true
        }

        binding.btnEnterLandscape?.setOnClickListener {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            val intent = requireActivity().intent
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            stopVlcStream()
            requireActivity().finish()
            startActivity(intent)
        }

        binding.btnSnapshot.setOnClickListener { takeSnapshot() }
        binding.btnRecordVideo.setOnClickListener {
            if (record.get()) stopVideoRecording() else startVideoRecording()
        }

        binding.btnBackLite?.setOnLongClickListener {
            toggleSourceMode()
            true
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    showSaveConfirmDialog()
                }
            }
        )
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

        val infoText = if (patientNrm.isEmpty()) "$patientRs" else "$patientRs/$patientNrm"
        binding.tvOverlayInfo.text = infoText
        startOverlayClock()

        return binding.root
    }

    private fun startOverlayClock() {
        if (clockJob?.isActive == true) return
        clockJob?.cancel()
        clockJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val now = if (android.os.Build.VERSION.SDK_INT >= 26) {
                    ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
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
        updateStatusBarColor()
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
        requireActivity().window.statusBarColor = ContextCompat.getColor(requireContext(), color)
    }

    private fun reattachVlcViews() {
        if (usePhoneCamera) return

        val player = mediaPlayer ?: return
        val vout = player.vlcVout

        try {
            vout.detachViews()
            vout.setVideoView(textureView)
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
                        applyVlcLayoutAndBaseTransform(width, height, visibleWidth, visibleHeight, sarNum, sarDen)
                    }
                }
            })

            Log.d(TAG, "VLC views reattached successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error reattaching VLC views", e)
        }
    }

    // ==========================================
    // VLC STREAMING
    // ==========================================
    private fun startVlcStream() {
        if (usePhoneCamera) return

        binding.pbLoadingImage.visibility = View.VISIBLE
        binding.vShutterImage.visibility = View.VISIBLE

        try {
            val options = ArrayList<String>().apply {
                add("--rtsp-tcp")
                add("--network-caching=10")
                add("--live-caching=10")
                add("--no-audio")
                add("--vout=gles2")
                add("--drop-late-frames")
                add("--skip-frames")
            }

            libVlc = LibVLC(requireContext(), options)
            mediaPlayer = MediaPlayer(libVlc)

            val vout = mediaPlayer!!.vlcVout
            vout.setVideoView(textureView)
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
                        applyVlcLayoutAndBaseTransform(width, height, visibleWidth, visibleHeight, sarNum, sarDen)
                    }
                }
            })

            val rawUrl = liveViewModel.rtspRequest.value ?: ""
            val user = liveViewModel.rtspUsername.value ?: ""
            val pass = liveViewModel.rtspPassword.value ?: ""
            val finalUrl = if (user.isNotEmpty() && !rawUrl.contains("//$user")) {
                rawUrl.replace("rtsp://", "rtsp://$user:$pass@")
            } else rawUrl

            val media = Media(libVlc, Uri.parse(finalUrl))
            media.addOption(":network-caching=0")
            media.addOption(":no-audio")
            mediaPlayer?.media = media
            media.release()
            mediaPlayer?.play()

            binding.tvStatusImage?.text = "RTSP Connected"
            binding.pbLoadingImage.postDelayed({
                binding.pbLoadingImage.visibility = View.GONE
                binding.vShutterImage.visibility = View.GONE
            }, 1500)

            setKeepScreenOn(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VLC", e)
        }
    }

    /**
     * Inti FIX:
     * - base center (baseTx/baseTy) tidak pernah di-reset 0
     * - zoom/pan hanya menambah layer di atas base
     * - landscape optional crop (full screen)
     */
    private fun applyVlcLayoutAndBaseTransform(
        width: Int,
        height: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        sarNum: Int,
        sarDen: Int
    ) {
        val tv = textureView ?: return
        val screenW = binding.videoContainer.width
        val screenH = binding.videoContainer.height
        if (screenW <= 0 || screenH <= 0) return

        var vidW = if (visibleWidth > 0) visibleWidth.toFloat() else width.toFloat()
        var vidH = if (visibleHeight > 0) visibleHeight.toFloat() else height.toFloat()

        if (sarNum > 0 && sarDen > 0) {
            vidW = vidW * sarNum / sarDen
        }

        val videoRatio = vidW / vidH
        val screenRatio = screenW.toFloat() / screenH.toFloat()

        val finalW: Int
        val finalH: Int
        if (screenRatio > videoRatio) {
            finalH = screenH
            finalW = (screenH * videoRatio).toInt()
        } else {
            finalW = screenW
            finalH = (screenW / videoRatio).toInt()
        }

        tv.layoutParams = tv.layoutParams.apply {
            this.width = finalW
            this.height = finalH
        }

        baseTxVlc = (screenW - finalW) / 2f
        baseTyVlc = (screenH - finalH) / 2f

        baseScaleVlc = if (CROP_IN_LANDSCAPE && isLandscape()) {
            max(
                screenW.toFloat() / finalW.toFloat(),
                screenH.toFloat() / finalH.toFloat()
            )
        } else 1f

        panTxVlc = 0f
        panTyVlc = 0f

        if (currentScale <= 1.01f) {
            focusX = finalW / 2f
            focusY = finalH / 2f
        }

        applyZoomAndPan()

        Log.d(
            "VLC_FIX",
            "Screen:${screenW}x${screenH} Video:${vidW}x${vidH} Fit:${finalW}x${finalH} baseTx=$baseTxVlc baseTy=$baseTyVlc baseScale=$baseScaleVlc"
        )
    }

    private fun stopVlcStream() {
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

    // ==========================================
    // RECORDING LOGIC
    // ==========================================
    private fun startVideoRecording() {
        val dir = videosDir ?: sessionDir ?: return
        val out = File(dir, "vid_${StorageUtils.timestampWIB()}.mp4")
        videoOutputFile = out
        val recWidth = 1280
        val recHeight = 720

        try {
            recorder = RealtimeBitmapEncoder(requireContext(), recWidth, recHeight, out)
            recorder.start()
            record.set(true)
            recordStartElapsedMs = android.os.SystemClock.elapsedRealtime()
            binding.recordHud.visibility = View.VISIBLE
            hudHandler.post(hudTick)
            binding.btnRecordVideo.setImageResource(R.drawable.btn_stop)
            startFrameGrabber(recWidth, recHeight)
        } catch (e: Exception) {
            record.set(false)
            Toast.makeText(requireContext(), "Gagal rekam: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVideoRecording() {
        if (!record.get()) return
        recordingJob?.cancel()
        runCatching { recorder.stop() }
        record.set(false)
        hudHandler.removeCallbacks(hudTick)
        binding.recordHud.visibility = View.GONE
        binding.btnRecordVideo.setImageResource(R.drawable.majesticons_video)
        binding.rvThumbs.postDelayed({ refreshThumbs() }, 300)
        Toast.makeText(requireContext(), "Video Tersimpan", Toast.LENGTH_SHORT).show()
    }

    private fun startFrameGrabber(width: Int, height: Int) {
        recordingJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            while (record.get() && isActive) {
                val start = System.currentTimeMillis()

                val bmp = withContext(Dispatchers.Main) {
                    if (usePhoneCamera) phoneCameraView?.bitmap
                    else textureView?.getBitmap(width, height)
                }

                if (bmp != null) {
                    val scaled = if (bmp.width != width || bmp.height != height) {
                        Bitmap.createScaledBitmap(bmp, width, height, true)
                    } else bmp
                    recorder.submitBitmap(processTextToBitmapSafe(scaled))
                }

                val wait = (50 - (System.currentTimeMillis() - start)).coerceAtLeast(0)
                delay(wait)
            }
        }
    }

    private fun takeSnapshot() {
        val dir = snapshotsDir ?: sessionDir ?: return

        val bmp = if (usePhoneCamera) phoneCameraView?.bitmap else textureView?.bitmap
        if (bmp != null) {
            runCatching {
                StorageUtils.saveJpegWithPrefix(dir, processTextToBitmapSafe(bmp), prefix = "ss")
            }.onSuccess {
                Toast.makeText(requireContext(), "Snapshot Tersimpan", Toast.LENGTH_SHORT).show()
                refreshThumbs()
            }.onFailure {
                Toast.makeText(requireContext(), "Gagal snapshot: ${it.message}", Toast.LENGTH_SHORT).show()
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
        val imgs = File(parent, "Snapshots").listFiles { f -> f.extension.equals("jpg", true) }.orEmpty()
        val vids = File(parent, "Video").listFiles { f -> f.extension.equals("mp4", true) }.orEmpty()

        val merged = (imgs.map { MediaItem(it, MediaType.IMAGE) } + vids.map { MediaItem(it, MediaType.VIDEO) })
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
            val sdfDob = SimpleDateFormat("dd/MM/yyyy", Locale("id", "ID"))
            sdfDob.format(Date(patientDobUtc))
        } else "-"
        tvNrm?.text = patientNrm.ifBlank { "Tidak ada nomor rekam medis" }

        btnClose?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showSavingProgressAndExecute() {
        val pv = layoutInflater.inflate(R.layout.dialog_progress_saving, null)
        val pd = MaterialAlertDialogBuilder(requireContext()).setView(pv).setCancelable(false).create()
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
            isFocusable = true
            isClickable = true
            requestFocus()
            setOnClickListener {
                d.dismiss()
                stopStreamAndExit()
                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }

    private fun openPreview(position: Int) {
        val paths = ArrayList(allMediaItems.map { it.file.absolutePath })
        val types = ArrayList(allMediaItems.map { it.type.name })

        val targetActivity =
            if (isLandscape()) com.idn.kmed.cervexa.gallery.MediaPagerActivityLand::class.java
            else com.idn.kmed.cervexa.gallery.MediaPagerActivity::class.java

        val intent = Intent(requireContext(), targetActivity).apply {
            putStringArrayListExtra("paths", paths)
            putStringArrayListExtra("types", types)
            putExtra("index", position)
            putExtra("forceLandscape", isLandscape())
        }
        startActivity(intent)
    }

    companion object {
        private val TAG: String = VideoFragment::class.java.simpleName
        private const val DEBUG = true
    }
}
