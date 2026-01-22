package com.idn.kmed.cervexa.live

import android.annotation.SuppressLint
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.pm.ActivityInfo
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
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
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

    // ==== DEBUG PLACEHOLDER ====
    private var debugPlaceholder: View? = null
    private var useDebugPlaceholder = false // Set false untuk disable

    private val prefs by lazy {
        requireContext().getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
    }

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // ===============================
    // FIX LANDSCAPE: FIT CENTER MATRIX
    // ===============================
    private val fitMatrix = Matrix()
    private var videoDisplayW = 0f
    private var videoDisplayH = 0f

    private fun applyFitCenterTransform() {
        val tv = textureView ?: return
        if (!isAdded) return

        val container = binding.videoContainer
        val viewW = container.width.toFloat()
        val viewH = container.height.toFloat()

        // Validasi ukuran container
        if (viewW <= 0f || viewH <= 0f) {
            Log.w(TAG, "Container size invalid: ${viewW}x${viewH}")
            return
        }

        // Validasi ukuran video
        if (videoDisplayW <= 0f || videoDisplayH <= 0f) {
            Log.w(TAG, "Video size invalid: ${videoDisplayW}x${videoDisplayH}")
            return
        }

        // Reset translasi
        tv.translationX = 0f
        tv.translationY = 0f

        // Hitung aspect ratio
        val videoAspect = videoDisplayW / videoDisplayH
        val viewAspect = viewW / viewH

        val scaleX: Float
        val scaleY: Float

        if (videoAspect > viewAspect) {
            // Video lebih lebar - fit by width
            scaleX = viewW / videoDisplayW
            scaleY = scaleX
        } else {
            // Video lebih tinggi - fit by height
            scaleY = viewH / videoDisplayH
            scaleX = scaleY
        }

        // Aplikasikan matrix transform
        fitMatrix.reset()
        fitMatrix.setScale(scaleX, scaleY)

        // Center video di container
        val scaledW = videoDisplayW * scaleX
        val scaledH = videoDisplayH * scaleY
        val dx = (viewW - scaledW) / 2f
        val dy = (viewH - scaledH) / 2f

        fitMatrix.postTranslate(dx, dy)

        tv.setTransform(fitMatrix)
        tv.invalidate()

        Log.d(
            TAG, "Transform applied - Video: ${videoDisplayW}x${videoDisplayH}, " +
                    "View: ${viewW}x${viewH}, Scale: ${scaleX}x${scaleY}"
        )
    }

    // ==========================================
    // DEBUG PLACEHOLDER
    // ==========================================
    private fun showDebugPlaceholder() {
        if (!useDebugPlaceholder) return

        // Hapus placeholder lama jika ada
        debugPlaceholder?.let { binding.videoContainer.removeView(it) }

        // Buat placeholder baru
        val placeholder = View(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            // Buat pattern kotak-kotak untuk debugging
            setBackgroundDrawable(object : android.graphics.drawable.Drawable() {
                override fun draw(canvas: Canvas) {
                    val paint = Paint()

                    // Background hitam
                    paint.color = Color.BLACK
                    canvas.drawRect(bounds, paint)

                    // Grid putih
                    paint.color = Color.WHITE
                    paint.strokeWidth = 2f
                    val gridSize = 100f

                    // Vertikal lines
                    var x = 0f
                    while (x < bounds.width()) {
                        canvas.drawLine(x, 0f, x, bounds.height().toFloat(), paint)
                        x += gridSize
                    }

                    // Horizontal lines
                    var y = 0f
                    while (y < bounds.height()) {
                        canvas.drawLine(0f, y, bounds.width().toFloat(), y, paint)
                        y += gridSize
                    }

                    // Garis tengah (merah vertikal, biru horizontal)
                    paint.strokeWidth = 4f
                    paint.color = Color.RED
                    canvas.drawLine(
                        bounds.width() / 2f, 0f,
                        bounds.width() / 2f, bounds.height().toFloat(),
                        paint
                    )

                    paint.color = Color.BLUE
                    canvas.drawLine(
                        0f, bounds.height() / 2f,
                        bounds.width().toFloat(), bounds.height() / 2f,
                        paint
                    )

                    // Diagonal corners (hijau)
                    paint.color = Color.GREEN
                    paint.strokeWidth = 3f
                    val cornerSize = 100f

                    // Top-left
                    canvas.drawLine(0f, 0f, cornerSize, 0f, paint)
                    canvas.drawLine(0f, 0f, 0f, cornerSize, paint)

                    // Top-right
                    canvas.drawLine(
                        bounds.width().toFloat(),
                        0f,
                        bounds.width() - cornerSize,
                        0f,
                        paint
                    )
                    canvas.drawLine(
                        bounds.width().toFloat(),
                        0f,
                        bounds.width().toFloat(),
                        cornerSize,
                        paint
                    )

                    // Bottom-left
                    canvas.drawLine(
                        0f,
                        bounds.height().toFloat(),
                        cornerSize,
                        bounds.height().toFloat(),
                        paint
                    )
                    canvas.drawLine(
                        0f,
                        bounds.height().toFloat(),
                        0f,
                        bounds.height() - cornerSize,
                        paint
                    )

                    // Bottom-right
                    canvas.drawLine(
                        bounds.width().toFloat(), bounds.height().toFloat(),
                        bounds.width() - cornerSize, bounds.height().toFloat(), paint
                    )
                    canvas.drawLine(
                        bounds.width().toFloat(), bounds.height().toFloat(),
                        bounds.width().toFloat(), bounds.height() - cornerSize, paint
                    )

                    // Text info di tengah
                    paint.color = Color.YELLOW
                    paint.textSize = 40f
                    paint.textAlign = Paint.Align.CENTER
                    paint.style = Paint.Style.FILL
                    paint.isFakeBoldText = true

                    val text = "${bounds.width()} x ${bounds.height()}"
                    canvas.drawText(
                        text,
                        bounds.width() / 2f,
                        bounds.height() / 2f - 50f,
                        paint
                    )

                    paint.textSize = 30f
                    canvas.drawText(
                        "DEBUG PLACEHOLDER",
                        bounds.width() / 2f,
                        bounds.height() / 2f + 50f,
                        paint
                    )

                    // Simulasi aspect ratio 16:9 (1280x720)
                    paint.color = Color.CYAN
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 5f

                    val videoW = 1280f
                    val videoH = 720f
                    val videoAspect = videoW / videoH
                    val viewW = bounds.width().toFloat()
                    val viewH = bounds.height().toFloat()
                    val viewAspect = viewW / viewH

                    val rectW: Float
                    val rectH: Float

                    if (videoAspect > viewAspect) {
                        rectW = viewW
                        rectH = viewW / videoAspect
                    } else {
                        rectH = viewH
                        rectW = viewH * videoAspect
                    }

                    val left = (viewW - rectW) / 2f
                    val top = (viewH - rectH) / 2f

                    canvas.drawRect(left, top, left + rectW, top + rectH, paint)

                    paint.textSize = 25f
                    paint.style = Paint.Style.FILL
                    canvas.drawText(
                        "Expected 1280x720 area",
                        bounds.width() / 2f,
                        top - 20f,
                        paint
                    )

                    // Info orientasi
                    paint.textSize = 20f
                    paint.color = Color.WHITE
                    val orientation = if (isLandscape()) "LANDSCAPE" else "PORTRAIT"
                    canvas.drawText(
                        "Orientation: $orientation",
                        bounds.width() / 2f,
                        bounds.height() - 50f,
                        paint
                    )
                }

                override fun setAlpha(alpha: Int) {}
                override fun setColorFilter(colorFilter: ColorFilter?) {}
                override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
            })
        }

        debugPlaceholder = placeholder
        binding.videoContainer.addView(placeholder, 0) // Add di bawah TextureView

        Log.d(TAG, "Debug placeholder shown")
    }

    private fun toggleDebugMode() {
//        useDebugPlaceholder = !useDebugPlaceholder
        if (debugPlaceholder != null) {
            // Hapus placeholder, mulai stream asli
            debugPlaceholder?.let { binding.videoContainer.removeView(it) }
            debugPlaceholder = null
            startVlcStream()
            Toast.makeText(requireContext(), "Debug OFF - Camera ON", Toast.LENGTH_SHORT).show()
        } else {
            // Stop stream, tampilkan placeholder
            stopVlcStream()
            showDebugPlaceholder()
            Toast.makeText(requireContext(), "Debug ON - Camera OFF", Toast.LENGTH_SHORT).show()
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

            sessionDir =
                args.getString("sessionDirPath")?.takeIf { it.isNotBlank() }?.let { File(it) }
            sessionDir?.let { parent ->
                snapshotsDir = File(parent, "Snapshots").apply { if (!exists()) mkdirs() }
                videosDir = File(parent, "Video").apply { if (!exists()) mkdirs() }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockJob?.cancel()
        debugPlaceholder?.let { binding.videoContainer.removeView(it) }
        debugPlaceholder = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Overlay info kiri bawah
        val infoText = if (patientNrm.isEmpty()) "$patientRs" else "$patientRs/$patientNrm"
        binding.tvOverlayInfo.text = infoText
        // Jam kanan bawah
        startOverlayClock()
        super.onViewCreated(view, savedInstanceState)
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

        // Re-apply transform ketika container berubah ukuran (rotate / overlay / stb)
        binding.videoContainer.viewTreeObserver.addOnGlobalLayoutListener {
            if (videoDisplayW > 0f && videoDisplayH > 0f) {
                applyFitCenterTransform()
            }
        }

        // Gesture: pinch to zoom
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
                    e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
                ): Boolean {
                    if (currentScale > 1.01f) {
                        textureView?.translationX = (textureView?.translationX ?: 0f) - distanceX
                        textureView?.translationY = (textureView?.translationY ?: 0f) - distanceY
                    }
                    return true
                }
            }
        )

        textureView?.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            gestureDetector.onTouchEvent(ev)
            true
        }

        binding.bnStartStopImage?.setOnClickListener {
            if (mediaPlayer?.isPlaying == true) stopStreamAndExit() else startVlcStream()
        }

        binding.btnEnterLandscape?.setOnClickListener {
            val infoText = if (patientNrm.isEmpty()) "$patientRs" else "$patientRs/$patientNrm"
            binding.tvOverlayInfo.text = infoText
            startOverlayClock()
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        binding.btnSnapshot.setOnClickListener { takeSnapshot() }

        binding.btnRecordVideo.setOnClickListener {
            if (record.get()) stopVideoRecording() else startVideoRecording()
        }

        // Long press untuk toggle debug mode
//        binding.btnBackLite?.setOnLongClickListener {
//            toggleDebugMode()
//            true
//        }

        // Back button
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

        // Thumbs Adapter
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

        // Overlay init
        val infoText = if (patientNrm.isEmpty()) "$patientRs" else "$patientRs/$patientNrm"
        binding.tvOverlayInfo.text = infoText
        startOverlayClock()

        return binding.root
    }

    private fun startOverlayClock() {
        if (clockJob?.isActive == true) {
            val now = if (android.os.Build.VERSION.SDK_INT >= 26) {
                ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
            } else {
                SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())
            }
            binding.tvOverlayClock.text = now
        } else {
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
    }

    private fun applyZoomMatrix() {
        textureView?.apply {
            pivotX = focusX
            pivotY = focusY
            scaleX = currentScale
            scaleY = currentScale

            if (currentScale <= 1.01f) {
                translationX = 0f
                translationY = 0f
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusBarColor()
        liveViewModel.loadParams(requireContext())
        if (useDebugPlaceholder) {
            showDebugPlaceholder()
        } else if (mediaPlayer == null || mediaPlayer?.isPlaying == false) {
            startVlcStream()
        }
    }

    override fun onPause() {
        super.onPause()
        updateStatusBarColor()
        liveViewModel.saveParams(requireContext())
        if (record.get()) stopVideoRecording()
        if (!useDebugPlaceholder) stopVlcStream()
    }

    private fun updateStatusBarColor() {
        val color = if (isLandscape()) R.color.colorBlack else R.color.colorButton
        requireActivity().window.statusBarColor = ContextCompat.getColor(requireContext(), color)
    }

    // ==========================================
    // VLC STREAMING LOGIC (ULTRA LOW LATENCY)
    // ==========================================
    private fun startVlcStream() {
        if (useDebugPlaceholder) {
            // Mode debug: tampilkan placeholder saja
            binding.pbLoadingImage.visibility = View.GONE
            binding.vShutterImage.visibility = View.GONE
            showDebugPlaceholder()
            binding.tvStatusImage?.text = "DEBUG MODE - Placeholder Active"
            return
        }

        binding.pbLoadingImage.visibility = View.VISIBLE
        binding.vShutterImage.visibility = View.VISIBLE

        try {
            val options = ArrayList<String>().apply {
                add("--rtsp-tcp")
                add("--network-caching=10")
                add("--live-caching=10")
                add("--file-caching=10")
                add("--clock-jitter=0")
                add("--clock-synchro=0")
                add("--no-audio")
                add("--no-stats")
                add("--quiet")
                add("--codec=all")
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

                        // Hitung aspect ratio yang benar
                        val vW = if (visibleWidth > 0) visibleWidth else width
                        val vH = if (visibleHeight > 0) visibleHeight else height

                        var dispW = vW.toFloat()
                        var dispH = vH.toFloat()

                        // Apply SAR (Sample Aspect Ratio)
                        if (sarNum > 0 && sarDen > 0) {
                            dispW = dispW * sarNum / sarDen
                        }

                        videoDisplayW = dispW
                        videoDisplayH = dispH

                        Log.d(
                            TAG, "Video layout: ${width}x${height}, visible: ${vW}x${vH}, " +
                                    "SAR: ${sarNum}:${sarDen}, display: ${dispW}x${dispH}"
                        )

                        // Set TextureView full container
                        textureView?.layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )

                        applyFitCenterTransform()
                    }
                }
            })

            val rawUrl = liveViewModel.rtspRequest.value ?: ""
            val user = liveViewModel.rtspUsername.value ?: ""
            val pass = liveViewModel.rtspPassword.value ?: ""
            val finalUrl = if (user.isNotEmpty() && !rawUrl.contains("//$user")) {
                rawUrl.replace("rtsp://", "rtsp://$user:$pass@")
            } else rawUrl

            val media = Media(libVlc, Uri.parse(finalUrl)).apply {
                addOption(":network-caching=0")
                addOption(":live-caching=0")
                addOption(":clock-jitter=0")
                addOption(":clock-synchro=0")
                addOption(":no-audio")
            }
            mediaPlayer?.media = media
            media.release()

            mediaPlayer?.play()

            binding.tvStatusImage?.text = "RTSP Connected (SW 720P)"
            binding.bnStartStopImage?.text = "Stop RTSP"

            binding.pbLoadingImage.postDelayed({
                binding.pbLoadingImage.visibility = View.GONE
                binding.vShutterImage.visibility = View.GONE
            }, 1500)

            setKeepScreenOn(true)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VLC", e)
            Toast.makeText(requireContext(), "Gagal Start Stream: ${e.message}", Toast.LENGTH_SHORT)
                .show()
        }
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
                val bmp = withContext(Dispatchers.Main) { textureView?.getBitmap(width, height) }
                if (bmp != null) {
                    recorder.submitBitmap(processTextToBitmapSafe(bmp))
                }
                val wait = (50 - (System.currentTimeMillis() - start)).coerceAtLeast(0)
                delay(wait)
            }
        }
    }

    private fun takeSnapshot() {
        val dir = snapshotsDir ?: sessionDir ?: return
        val bmp = textureView?.bitmap
        if (bmp != null) {
            runCatching {
                StorageUtils.saveJpegWithPrefix(dir, processTextToBitmapSafe(bmp), prefix = "ss")
            }.onSuccess {
                Toast.makeText(requireContext(), "Snapshot Tersimpan", Toast.LENGTH_SHORT).show()
                refreshThumbs()
            }.onFailure {
                Toast.makeText(
                    requireContext(),
                    "Gagal snapshot: ${it.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ==== OVERLAY NAMA RS & NRM ====
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
        }
        val paintBox = Paint().apply { color = Color.argb(128, 0, 0, 0); style = Paint.Style.FILL }

        // Overlay Timestamp (Kanan Bawah)
        canvas.drawRect(
            bitmap.width.toFloat() - 360f,
            bitmap.height.toFloat() - 60f,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            Paint().apply { color = "#3F3F3F".toColorInt() }
        )
        canvas.drawText(
            formatted,
            bitmap.width.toFloat() - 350f,
            bitmap.height.toFloat() - 20f,
            paintText
        )

        // Overlay Nama RS & NRM (Kiri Bawah)
        canvas.drawRect(0f, bitmap.height.toFloat() - 65f, 650f, bitmap.height.toFloat(), paintBox)
        if (patientNrm.isEmpty()) {
            canvas.drawText("$patientRs", 20f, bitmap.height.toFloat() - 20f, paintText)
        } else {
            canvas.drawText("$patientRs/$patientNrm", 20f, bitmap.height.toFloat() - 20f, paintText)
        }

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

    // ==== IMPLEMENTASI IVLCVout.Callback ====
    override fun onSurfacesCreated(vlcVout: IVLCVout?) {}
    override fun onSurfacesDestroyed(vlcVout: IVLCVout?) {}

    // ==== DIALOGS & HELPER ====
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
        val imgs =
            File(parent, "Snapshots").listFiles { f -> f.extension.equals("jpg", true) }.orEmpty()
        val vids =
            File(parent, "Video").listFiles { f -> f.extension.equals("mp4", true) }.orEmpty()

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
            val sdfDob = SimpleDateFormat("dd/MM/yyyy", Locale("id", "ID"))
            sdfDob.format(Date(patientDobUtc))
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
        if (tvAction != null) {
            tvAction.isFocusable = true
            tvAction.isClickable = true
            tvAction.requestFocus()

            tvAction.setOnClickListener {
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

        val targetActivity = if (isLandscape()) {
            com.idn.kmed.cervexa.gallery.MediaPagerActivityLand::class.java
        } else {
            com.idn.kmed.cervexa.gallery.MediaPagerActivity::class.java
        }

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