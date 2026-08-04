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
import com.idn.kmed.cervexa.record.RealtimeBitmapEncoder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.idn.kmed.cervexa.home.HomeActivity
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.settings.SettingsActivity.Companion.KEY_CAMERA_ROTATION_DEG
import com.idn.kmed.cervexa.settings.SettingsActivity.Companion.KEY_USE_HW_DECODER
import com.idn.kmed.cervexa.databinding.FragmentVideoMobileBinding
import com.idn.kmed.cervexa.utils.*
import com.idn.kmed.cervexa.utils.PdfReportHelper
import com.idn.kmed.cervexa.utils.PrintHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.IMediaPlayer
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
 


    // ==== IjkMediaPlayer (menggantikan LibVLC) ====
    private var ijkPlayer: IjkMediaPlayer? = null
    private var textureView: android.view.TextureView? = null
    private var ivVideoImageResolution = Pair(0, 0)
    private var baseScaleVlc = 1f
    private var baseTxVlc = 0f
    private var baseTyVlc = 0f
    private var panTxVlc = 0f
    private var panTyVlc = 0f

    private var clockJob: Job? = null
    private var liveFrameJob: Job? = null
    private var liveResyncJob: Job? = null
    private var liveStreamStartMs: Long = 0L
    private var lastSnapshotMs: Long = 0L  // Catat waktu capture terakhir

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
        color = Color.WHITE  // Putih agar terbaca di background hitam
        // textSize JANGAN hardcode (akan diskalakan berdasarkan frame)
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    private val paintBox = Paint().apply {
        color = Color.BLACK
        alpha = 255
        style = Paint.Style.FILL
    }
    private val paintDateBg = Paint().apply {
        color = Color.BLACK
        alpha = 255
        style = Paint.Style.FILL
    }
    private val paintDateText = Paint().apply {
        color = Color.WHITE  // Putih agar kontras di background hitam
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
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
        liveFrameJob?.cancel()

        if (record.get()) stopVideoRecording()
        stopVlcStream()
        hudHandler.removeCallbacks(hudTick)
    }

    override fun onResume() {
        super.onResume()
        toggleSystemUI()
        liveViewModel.loadParams(requireContext())

        if (ijkPlayer?.isPlaying != true) startVlcStream()
        if (clockJob?.isActive != true) startOverlayClock()
    }

    override fun onPause() {
        super.onPause()
        if (record.get()) stopVideoRecording()
        hudHandler.removeCallbacks(hudTick)
        binding.recordHud.visibility = View.GONE

        liveViewModel.saveParams(requireContext())
        stopVlcStream()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.tvOverlayInfo.text = overlayInfoText()
        toggleSystemUI()
        // Re-apply layout setelah orientasi berubah
        textureView?.post { applyIjkLayout() }
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



        // Ambil TextureView dari layout & terapkan brightness GPU tanpa delay
        textureView = binding.root.findViewById<android.view.TextureView>(R.id.textureView)?.also {
            applyHardwareBrightness(it, 25f)
        }

        setupGestureDetectors()
        setupButtons()
        setupThumbs()
        setupDebugPanel()

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
                    focusX = d.focusX; focusY = d.focusY; applyZoomAndPan(); return true
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
                        panTxVlc -= dX; panTyVlc -= dY
                        applyZoomAndPan()
                    }; return true
                }
            })

        val touch = View.OnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev); gestureDetector.onTouchEvent(ev); true
        }
        binding.videoContainer.setOnTouchListener(touch)
        binding.vShutterImage.setOnTouchListener(touch)
    }

    private fun setupButtons() {
        binding.bnStartStopImage?.setOnClickListener {
            if (ijkPlayer?.isPlaying == true) stopVlcStream()
            else startVlcStream()
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
                override fun handleOnBackPressed() { showExitConfirmDialog() }
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
    // IJK STREAM  (zero-latency: nobuffer, tcp, framedrop)
    // =====================================================================

    private val ijkEventListener = IMediaPlayer.OnInfoListener { _, what, _ ->
        when (what) {
            IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                binding.pbLoadingImage.visibility = View.GONE
                binding.vShutterImage.animate().alpha(0f).setDuration(250).withEndAction {
                    binding.vShutterImage.visibility = View.GONE
                    binding.vShutterImage.alpha = 1f
                }.start()
                binding.tvStatusImage?.text = "Connected"
                setKeepScreenOn(true)
            }
        }
        true
    }

    private fun startVlcStream() {
        val rtspUrl = liveViewModel.rtspRequest.value
        if (rtspUrl.isNullOrBlank()) {
            Toast.makeText(requireContext(), "❌ URL RTSP tidak valid", Toast.LENGTH_LONG).show()
            return
        }
        val tv = textureView ?: return
        binding.pbLoadingImage.visibility = View.VISIBLE
        binding.vShutterImage.visibility = View.VISIBLE

        runCatching {
            val user = liveViewModel.rtspUsername.value.orEmpty()
            val pass = liveViewModel.rtspPassword.value.orEmpty()
            val finalUrl = if (user.isNotEmpty() && !rtspUrl.contains("//$user"))
                rtspUrl.replace("rtsp://", "rtsp://$user:$pass@") else rtspUrl

            // === KONFIGURASI ULTRA ZERO-LATENCY ===
            val player = IjkMediaPlayer().apply {
                IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_WARN)

                // ── FORMAT (FFmpeg demuxer) ──
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "nobuffer")
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "udp")
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "max_delay", 0L)
                // Deteksi stream secepat kilat — jangan buang waktu analisis
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 256L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 0L)
                // Jangan tunggu paket yang datang tidak urut — langsung render
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reorder_queue_size", 0L)

                // ── PLAYER (IjkPlayer internal) ──
                // PENTING: max_cached_duration=0 di IJK = UNLIMITED! Harus > 0
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 1L) // 1ms = hampir nol
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_buffer_size", 1024L)  // 1 KB saja
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0L)    // MATIKAN buffering
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", 1L)              // Jangan blok render meski buffer kosong
                // Buang frame yang terlambat secara agresif
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 5L)
                // Matikan audio sepenuhnya (tidak ada audio di mikroskop)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "an", 1L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1L)

                // ── CODEC (decoder) ──
                // SELALU gunakan HW decoder — jauh lebih cepat dari software
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L)
                // Skip deblocking filter — hemat CPU, kurangi latency ~0.5 detik
                setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48L) // AVDISCARD_ALL
                // Jangan proses frame B (bi-directional) — hemat latency
                setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_frame", 0L) // AVDISCARD_DEFAULT

                // HAPUS vfilter brightness — terlalu berat di CPU, menambah 0.3-1 detik delay
                // Brightness bisa diatur di level TextureView (ColorMatrix) tanpa delay
            }

            // Attach ke TextureView yang sudah ada di layout XML
            val st = tv.surfaceTexture ?: run {
                Log.w(TAG, "SurfaceTexture belum tersedia, menunggu...")
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        tv.surfaceTextureListener = null
                        if (isAdded) startVlcStream()
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
                    binding.tvStatusImage?.text = "Error"
                    binding.pbLoadingImage.visibility = View.GONE
                }
                true
            }
            player.setOnCompletionListener {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    binding.tvStatusImage?.text = "Disconnected"
                    binding.vShutterImage.apply { alpha = 1f; visibility = View.VISIBLE }
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
            liveStreamStartMs = android.os.SystemClock.elapsedRealtime()
            startLiveFrameGrabber()
            startLiveResyncWatchdog()
        }.onFailure {
            Log.e(TAG, "IJK start error", it)
            binding.pbLoadingImage.visibility = View.GONE
            Toast.makeText(requireContext(), "❌ Gagal konek: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVlcStream() {
        liveResyncJob?.cancel()
        liveFrameJob?.cancel()
        val oldPlayer = ijkPlayer
        ijkPlayer = null
        
        // Hancurkan player lama di background thread agar UI tidak macet (ANR/Freeze)
        Thread {
            runCatching {
                oldPlayer?.stop()
                oldPlayer?.release()
            }
        }.start()
        
        setKeepScreenOn(false)
        baseScaleVlc = 1f; baseTxVlc = 0f; baseTyVlc = 0f
        panTxVlc = 0f; panTyVlc = 0f; currentScale = 1f
    }

    private var resyncJob: Job? = null

    /**
     * Resync halus: merestart player sekedip mata (300ms) untuk mereset delay 
     * yang menumpuk akibat beban CPU saat capture/record berlebihan.
     */
    private fun autoResyncStream() {
        if (!isAdded || ijkPlayer?.isPlaying != true) return
        activity?.runOnUiThread {
            Log.d(TAG, "[Auto-Resync] Restarting stream safely to flush CPU-induced delay")
            stopVlcStream()
            
            // Beri jeda 500ms (bukan 300ms) agar socket UDP RTSP server sempat ter-close
            binding.root.postDelayed({
                if (isAdded && ijkPlayer == null) startVlcStream()
            }, 500)
        }
    }

    /**
     * Jadwalkan resync 3 detik setelah user Selesai klik-klik capture.
     * Jika saat itu sedang merekam video, resync dibatalkan (karena akan merusak file video).
     */
    private fun scheduleAutoResync() {
        resyncJob?.cancel()
        resyncJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(3000L)
            if (record.get() || !isAdded) return@launch
            autoResyncStream()
        }
    }

    /**
     * Anti-drift watchdog dimatikan sementara karena menyebabkan preview freeze 
     * setelah reconnect pada beberapa STB.
     */
    private fun startLiveResyncWatchdog() {
        liveResyncJob?.cancel()
        // Watchdog dinonaktifkan sementara
    }

    /**
     * Hitung ukuran TextureView agar video mengisi seluruh container (Center Crop).
     * Dipanggil saat onVideoSizeChanged dan setelah orientasi berubah.
     */
    private fun applyIjkLayout(
        videoW: Int = ivVideoImageResolution.first,
        videoH: Int = ivVideoImageResolution.second,
        sarNum: Int = 0,
        sarDen: Int = 0
    ) {
        val tv = textureView ?: return
        val container = binding.videoContainer
        val cW = container.width; val cH = container.height
        if (cW <= 0 || cH <= 0) {
            container.postDelayed({ applyIjkLayout(videoW, videoH, sarNum, sarDen) }, 100); return
        }
        if (videoW <= 0 || videoH <= 0) return

        var vW = videoW.toFloat()
        val vH = videoH.toFloat()
        if (sarNum > 0 && sarDen > 0) vW = vW * sarNum / sarDen

        // STRETCH: video ditarik penuh memenuhi seluruh layar, abaikan rasio aspek
        val finalW = cW
        val finalH = cH

        tv.layoutParams = android.widget.FrameLayout.LayoutParams(finalW, finalH).also {
            it.gravity = android.view.Gravity.CENTER
        }
        lastFrameSize = Pair(finalW, finalH)
        baseScaleVlc = 1f
        baseTxVlc = 0f; baseTyVlc = 0f; panTxVlc = 0f; panTyVlc = 0f
        if (currentScale <= 1.01f) { focusX = finalW / 2f; focusY = finalH / 2f }
        applyZoomAndPan()
    }


    private fun applyZoomAndPan() {
        val tv = textureView ?: return
        val scale = currentScale.coerceIn(minScale, maxScale)
        tv.pivotX = focusX; tv.pivotY = focusY
        tv.scaleX = baseScaleVlc * scale; tv.scaleY = baseScaleVlc * scale
        if (scale <= 1.01f) { panTxVlc = 0f; panTyVlc = 0f }
        tv.translationX = baseTxVlc + panTxVlc; tv.translationY = baseTyVlc + panTyVlc
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
            ivVideoImageResolution.first.takeIf { it > 0 } ?: lastFrameSize.first.coerceAtLeast(640)
        val height =
            ivVideoImageResolution.second.takeIf { it > 0 } ?: lastFrameSize.second.coerceAtLeast(360)
        val out = File(dir, "vid_${StorageUtils.timestampWIB()}.mp4")
        videoOutputFile = out

        runCatching {
            recorder = RealtimeBitmapEncoder(
                context = requireContext(),
                width = width,
                height = height,
                outputFile = out,
                frameRate = 15,
                bitRate = 1_500_000
            )
            recorder.start()
            record.set(true)
            recordStartElapsedMs = android.os.SystemClock.elapsedRealtime()

            binding.recordHud.visibility = View.VISIBLE
            binding.tvRecordTimer.text = "00:00:00"
            hudHandler.removeCallbacks(hudTick)
            hudHandler.post(hudTick)
            binding.btnRecordVideo.setImageResource(R.drawable.ic_btn_stop)
            Toast.makeText(requireContext(), "⏺️ MULAI MEREKAM", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Log.e(TAG, "Recording ERROR", it); record.set(false)
            Toast.makeText(requireContext(), "❌ Gagal merekam: ${it.message}", Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun startLiveFrameGrabber() {
        liveFrameJob?.cancel()
        liveFrameJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val frameIntervalNs = 1_000_000_000L / 15
            var nextFrameNs = System.nanoTime()
            var poolBitmap: Bitmap? = null

            try {
                while (isActive) {
                    val isRecording = record.get()
                    val isSnapshotRequested = ss.get()

                    if (!isRecording && !isSnapshotRequested) {
                        delay(100)
                        continue
                    }

                    val sourceBmp = withContext(Dispatchers.Main) {
                        if (!isAdded || textureView == null) return@withContext null
                        if (poolBitmap == null || poolBitmap!!.isRecycled) {
                            poolBitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
                        }
                        textureView?.getBitmap(poolBitmap!!)
                        poolBitmap
                    } ?: continue

                    // Prepare Overlay (text only, no AI)
                    val bmWithOverlay = processTextToBitmapSafe(sourceBmp)

                    // 3. Record Video
                    if (isRecording && ::recorder.isInitialized) {
                        runCatching {
                            recorder.submitBitmap(bmWithOverlay.copy(Bitmap.Config.ARGB_8888, false))
                        }.onFailure { Log.e(TAG, "submitBitmap error", it) }
                    }

                    if (isSnapshotRequested && ss.compareAndSet(true, false)) {
                        lastSnapshotMs = System.currentTimeMillis() // catat waktu capture
                        processSnapshot(bmWithOverlay)
                        // autoResync DIHAPUS — menyebabkan stream stuck
                    }

                    if (bmWithOverlay !== sourceBmp && !bmWithOverlay.isRecycled) {
                        bmWithOverlay.recycle()
                    }

                    val loopEndNs = System.nanoTime()
                    if (loopEndNs < nextFrameNs) {
                        val waitMs = (nextFrameNs - loopEndNs) / 1_000_000
                        if (waitMs > 0) delay(waitMs)
                    }
                    nextFrameNs += frameIntervalNs
                    // Cegah nextFrameNs tertinggal jauh yang menyebabkan loop berjalan tanpa delay
                    if (nextFrameNs < System.nanoTime()) nextFrameNs = System.nanoTime() + frameIntervalNs
                }
            } catch (e: Exception) {
                if (e !is CancellationException) Log.e(TAG, "Frame grabber error", e)
            } finally {
                poolBitmap?.recycle()
                poolBitmap = null
            }
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
            Toast.makeText(requireContext(), "✅ VIDEO TERSIMPAN!", Toast.LENGTH_SHORT).show()
            binding.rvThumbs.postDelayed({ refreshThumbs() }, 300)
        } else {
            Toast.makeText(requireContext(), "⚠️ File video tidak ditemukan", Toast.LENGTH_SHORT)
                .show()
        }

        // autoResync DIHAPUS — menyebabkan stream stuck di perangkat lemah
    }

    // =====================================================================
    // SNAPSHOT
    // =====================================================================

    private fun onSnapshotClicked() {
        if (ijkPlayer?.isPlaying != true) {
            Toast.makeText(requireContext(), "⚠️ Stream belum aktif", Toast.LENGTH_LONG)
                .show(); return
        }
        if (sessionDir == null) {
            Toast.makeText(requireContext(), "❌ Direktori sesi tidak tersedia", Toast.LENGTH_LONG)
                .show(); return
        }
        ss.set(true)
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
        paintDateText.textSize = fontPx

        lastOverlayTargetHeight = frameHeight
        lastOverlayTextSizePx = fontPx
    }

    private fun overlayPadding(frameHeight: Int): Float {
        // padding ikut skala agar box tidak terlalu tebal di resolusi kecil
        return (frameHeight * PADDING_SCALE).coerceIn(PADDING_MIN_PX, PADDING_MAX_PX)
    }

    private fun processTextToBitmapSafe(src: Bitmap, aiProb: Float = -1f): Bitmap {
        if (src.isRecycled) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        
        // Crop 4 pixel di atas untuk membuang list/garis biru artefak bawaan hardware kamera MS2
        val cropTop = 4
        val safeSrc = if (src.height > cropTop) {
            Bitmap.createBitmap(src, 0, cropTop, src.width, src.height - cropTop)
        } else src
        
        val bitmap = if (safeSrc.isMutable) safeSrc else safeSrc.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)

        // >>> FIX UTAMA: font & padding mengikuti ukuran frame <<<
        ensureOverlayTextSize(bitmap.height)
        val pad = overlayPadding(bitmap.height)

        val formatted = if (android.os.Build.VERSION.SDK_INT >= 26)
            ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
        else SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())

        val info = if (patientNrm.isEmpty()) patientRs else "$patientRs/$patientNrm"

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

        // === Left-bottom info box (dynamic width) ===
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

        // Kalau text terlalu panjang sampai melewati box, kita potong (ellipsize manual sederhana)
        val maxTextW = (infoRight - infoLeft - pad * 2f).coerceAtLeast(0f)
        val infoDraw = ellipsizeToWidth(info, paintText, maxTextW)

        // Hitung juga center Y untuk text sebelah kiri agar simetris
        val infoCenterY = infoTop + ((infoBottom - infoTop) / 2f) - ((paintText.descent() + paintText.ascent()) / 2f)
        canvas.drawText(infoDraw, infoLeft + pad, infoCenterY, paintText)

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


    // =====================================================================
    // STATISTICS (REMOVED)
    // =====================================================================    // =====================================================================
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


    private fun setKeepScreenOn(enable: Boolean) {
        activity?.apply {
            if (enable) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun toggleSystemUI() {
        val window = requireActivity().window
        if (isLandscape()) {
            // Landscape: sembunyikan status bar + navigation bar (full immersive)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).let {
                it.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                it.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Portrait: tampilkan kembali status bar + navigation bar
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val color = ContextCompat.getColor(requireContext(), R.color.colorButton)
            window.statusBarColor = color
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

    private fun stopStreamAndExit() {
        stopVideoRecording()
        stopVlcStream()
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
                stopStreamAndExit()
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

    private var currentBrightness = 25f
    private var currentContrast = 1.25f
    private var currentSaturation = 0.85f
    private var currentRed = 1.05f
    private var currentGreen = 1.05f
    private var currentBlue = 0.95f

    private fun applyHardwareBrightness(
        tv: android.view.TextureView, 
        brightnessOffset: Float = currentBrightness,
        contrast: Float = currentContrast,
        saturation: Float = currentSaturation,
        redBoost: Float = currentRed,
        greenBoost: Float = currentGreen,
        blueBoost: Float = currentBlue
    ) {
        val cm = android.graphics.ColorMatrix()
        cm.setSaturation(saturation)

        val brightnessAndContrast = android.graphics.ColorMatrix(floatArrayOf(
            contrast * redBoost, 0f, 0f, 0f, brightnessOffset,
            0f, contrast * greenBoost, 0f, 0f, brightnessOffset,
            0f, 0f, contrast * blueBoost, 0f, brightnessOffset,
            0f, 0f, 0f, 1f, 0f
        ))
        cm.postConcat(brightnessAndContrast)

        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        }
        tv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint)
    }

    private fun setupDebugPanel() {
        val updateFilter = {
            textureView?.let { 
                applyHardwareBrightness(it) 
            }
        }
        
        val sbContrast = binding.root.findViewById<android.widget.SeekBar>(R.id.sbContrast)
        val tvContrastVal = binding.root.findViewById<android.widget.TextView>(R.id.tvContrastVal)
        sbContrast?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                currentContrast = progress / 100f
                tvContrastVal?.text = String.format("%.2f", currentContrast)
                updateFilter()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        val sbBrightness = binding.root.findViewById<android.widget.SeekBar>(R.id.sbBrightness)
        val tvBrightnessVal = binding.root.findViewById<android.widget.TextView>(R.id.tvBrightnessVal)
        sbBrightness?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                currentBrightness = (progress - 100).toFloat()
                tvBrightnessVal?.text = currentBrightness.toString()
                updateFilter()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        val sbSaturation = binding.root.findViewById<android.widget.SeekBar>(R.id.sbSaturation)
        val tvSaturationVal = binding.root.findViewById<android.widget.TextView>(R.id.tvSaturationVal)
        sbSaturation?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                currentSaturation = progress / 100f
                tvSaturationVal?.text = String.format("%.2f", currentSaturation)
                updateFilter()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        val sbRed = binding.root.findViewById<android.widget.SeekBar>(R.id.sbRed)
        val tvRedVal = binding.root.findViewById<android.widget.TextView>(R.id.tvRedVal)
        sbRed?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                currentRed = progress / 100f
                tvRedVal?.text = String.format("%.2f", currentRed)
                updateFilter()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        val sbGreen = binding.root.findViewById<android.widget.SeekBar>(R.id.sbGreen)
        val tvGreenVal = binding.root.findViewById<android.widget.TextView>(R.id.tvGreenVal)
        sbGreen?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                currentGreen = progress / 100f
                tvGreenVal?.text = String.format("%.2f", currentGreen)
                updateFilter()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        val sbBlue = binding.root.findViewById<android.widget.SeekBar>(R.id.sbBlue)
        val tvBlueVal = binding.root.findViewById<android.widget.TextView>(R.id.tvBlueVal)
        sbBlue?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                currentBlue = progress / 100f
                tvBlueVal?.text = String.format("%.2f", currentBlue)
                updateFilter()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        val btnHideDebug = binding.root.findViewById<android.widget.Button>(R.id.btnHideDebug)
        val svDebugPanel = binding.root.findViewById<android.view.View>(R.id.svDebugPanel)
        btnHideDebug?.setOnClickListener {
            svDebugPanel?.visibility = android.view.View.GONE
        }
    }

    companion object {
        private val TAG = VideoFragmentMobile::class.java.simpleName



        // ===== Overlay scaling =====
        // 0.045f = 4.5% tinggi frame (480 -> ~21.6px) — lebih besar dari sebelumnya
        private const val TEXT_SCALE = 0.045f
        private const val TEXT_MIN_PX = 18f
        private const val TEXT_MAX_PX = 52f

        // Padding scale
        private const val PADDING_SCALE = 0.035f
        private const val PADDING_MIN_PX = 14f
        private const val PADDING_MAX_PX = 36f
    }
}