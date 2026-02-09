package com.idn.kmed.cervexa.live

import android.annotation.SuppressLint
import android.content.Context.MODE_PRIVATE
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.alexvas.rtsp.codec.VideoDecodeThread
import com.idn.kmed.cervexa.record.RealtimeBitmapEncoder
import com.idn.kmed.cervexa.utils.PatientUtils
import com.idn.kmed.cervexa.utils.StorageUtils
import com.alexvas.rtsp.widget.RtspDataListener
import com.alexvas.rtsp.widget.RtspImageView
import com.alexvas.rtsp.widget.RtspProcessor.Statistics
import com.alexvas.rtsp.widget.RtspStatusListener
import com.alexvas.rtsp.widget.toHexString
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import androidx.core.graphics.toColorInt

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.OnBackPressedCallback
import com.idn.kmed.cervexa.HomeActivity
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.utils.MediaItem
import com.idn.kmed.cervexa.utils.MediaType
import com.idn.kmed.cervexa.utils.ThumbAdapter
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.content.res.Configuration
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.idn.kmed.cervexa.SettingsActivity.Companion.KEY_CAMERA_ROTATION_DEG
import com.idn.kmed.cervexa.SettingsActivity.Companion.KEY_USE_HW_DECODER
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.idn.kmed.cervexa.databinding.FragmentVideoMobileBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import java.util.TimeZone
import org.json.JSONObject


class VideoFragmentMobile : Fragment() {

    private lateinit var binding: FragmentVideoMobileBinding
    private lateinit var liveViewModel: LiveViewModel
    private var lastBitmap: Bitmap? = null
    private val bitmapLock = Any()

    private var statisticsTimer: Timer? = null
    private var ivVideoImageResolution = Pair(0, 0)

    // ==== Clock Timer ====
    private var clockJob: Job? = null

    // ==== Session / Storage (dari VideoActivity via arguments) ====
    private var sessionDir: File? = null
    private var patientNama: String = ""
    private var patientNik: String = ""
    private var patientRs: String = ""
    private var patientNrm: String = ""
    private var patientDobUtc: Long = -1L
    private var patientAge: Int = 0
    private var snapshotsDir: File? = null

    // ✅ Flag untuk track apakah metadata sudah disimpan
    private var isMetadataSaved = false

    // ==== Encode / Flags ====
    private lateinit var recorder: RealtimeBitmapEncoder
    private val ss = AtomicBoolean(false)      // single snapshot trigger

    private var lastAutoSaveAtMs = 0L
    private val minAutoSaveIntervalMs = 5_000L // anti-spam autosave (opsional)

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

    private var currentScale = 1f
    private var minScale = 1f
    private var maxScale = 5f
    private var focusX = 0f
    private var focusY = 0f

    private val prefs by lazy {
        requireContext().getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
    }

    private lateinit var scaleDetector: android.view.ScaleGestureDetector
    private lateinit var gestureDetector: android.view.GestureDetector

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // ✅ FUNGSI BARU: Simpan metadata session ke session.json
    private fun saveSessionMetadata() {
        val dir = sessionDir ?: run {
            Log.w(TAG, "⚠️ Cannot save metadata: sessionDir is null")
            return
        }

        if (isMetadataSaved) {
            Log.d(TAG, "Metadata already saved, skipping")
            return
        }

        val json = JSONObject().apply {
            put("nama", patientNama)
            put("nik", patientNik)
            put("nrm", patientNrm)
            put("rs", patientRs)
            put("dob_utc", patientDobUtc)
            put("saved_at", System.currentTimeMillis())
        }

        try {
            val metaFile = File(dir, "session.json")
            metaFile.writeText(json.toString(2))
            isMetadataSaved = true
            Log.d(TAG, "✅ session.json saved: ${metaFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save session.json: ${e.message}", e)
        }
    }

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
                tvStatusImage?.text = "RTSP connecting..."
                pbLoadingImage.visibility = View.VISIBLE
                vShutterImage.visibility = View.VISIBLE
            }
        }

        override fun onRtspStatusConnected() {
            if (DEBUG) Log.v(TAG, "onRtspStatusConnected()")
            Log.d(TAG, "✅ RTSP CONNECTED successfully")
            binding.apply {
                tvStatusImage?.text = "RTSP connected ✓"
                bnStartStopImage?.text = "Stop RTSP"
            }
            setKeepScreenOn(true)
        }

        override fun onRtspStatusDisconnected() {
            if (DEBUG) Log.v(TAG, "onRtspStatusDisconnected()")
            Log.w(TAG, "⚠️ RTSP DISCONNECTED")
            binding.apply {
                tvStatusImage?.text = "RTSP disconnected"
                bnStartStopImage?.text = "Start RTSP"
                pbLoadingImage.visibility = View.GONE
                vShutterImage.apply { alpha = 1f; visibility = View.VISIBLE }
                pbLoadingImage.isEnabled = false
            }
            setKeepScreenOn(false)

            synchronized(bitmapLock) {
                lastBitmap = null
            }
        }

        override fun onRtspStatusFailed(message: String?) {
            if (DEBUG) Log.e(TAG, "onRtspStatusFailed(message='$message')")
            Log.e(TAG, "❌ RTSP FAILED: $message")
            if (context == null) return
            onRtspStatusDisconnected()
            binding.apply {
                tvStatusImage?.text = "Error: $message"
                pbLoadingImage.visibility = View.GONE
            }

            Toast.makeText(
                requireContext(),
                "❌ Koneksi RTSP gagal: $message",
                Toast.LENGTH_LONG
            ).show()
        }

        override fun onRtspFirstFrameRendered() {
            if (DEBUG) Log.v(TAG, "onRtspFirstFrameRendered()")
            Log.i(TAG, "✅ First frame rendered - stream is ACTIVE")
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
        }

        // ✅ FALLBACK: Buat direktori jika null
        if (sessionDir == null) {
            Log.w(TAG, "⚠️ sessionDir is NULL! Creating fallback...")

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

            Log.d(TAG, "✅ Fallback sessionDir: ${sessionDir?.absolutePath}")
        }

        // ✅ Buat subdirektori
        sessionDir?.let { parent ->
            snapshotsDir = StorageUtils.ensureChildDir(parent, "Snapshots")
            videosDir = StorageUtils.ensureChildDir(parent, "Video")

            Log.d(TAG, "✅ Snapshots: ${snapshotsDir?.absolutePath}")
            Log.d(TAG, "✅ Videos: ${videosDir?.absolutePath}")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        Log.d(TAG, "=== onDestroyView() ===")

        // ✅ Save metadata sebelum destroy (safety net)
        if (allMediaItems.isNotEmpty() && !isMetadataSaved) {
            Log.d(TAG, "Auto-saving metadata on destroy")
            saveSessionMetadata()
        }

        clockJob?.cancel()

        if (record.get()) {
            stopVideoRecording()
        }

        if (binding.ivVideoImage.isStarted()) {
            binding.ivVideoImage.stop()
        }

        binding.ivVideoImage.onRtspImageBitmapListener = null
        binding.ivVideoImage.setStatusListener(null)
        binding.ivVideoImage.setDataListener(null)

        stopStatistics()
        hudHandler.removeCallbacks(hudTick)

        binding.root.postDelayed({
            synchronized(bitmapLock) {
                lastBitmap?.recycle()
                lastBitmap = null
            }
        }, 100)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged: orientation=${newConfig.orientation}")

        val infoText = if (patientNrm.isEmpty()) "$patientRs" else "$patientRs/$patientNrm"
        binding.tvOverlayInfo.text = infoText

        if (clockJob?.isActive != true) {
            startOverlayClock()
        }

        if (isLandscape()) {
            requireActivity().window.statusBarColor =
                ContextCompat.getColor(requireContext(), R.color.colorBlack)
        } else {
            requireActivity().window.statusBarColor =
                ContextCompat.getColor(requireContext(), R.color.colorButton)
        }
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (DEBUG) Log.v(TAG, "onCreateView()")

        liveViewModel = ViewModelProvider(this)[LiveViewModel::class.java]
        binding = FragmentVideoMobileBinding.inflate(inflater, container, false)
        binding.ivVideoImage.apply {
            setStatusListener(rtspStatusImageListener)
            setDataListener(rtspDataListener)
            enablePinchZoom()

            videoDecoderType = VideoDecodeThread.DecoderType.HARDWARE
            videoRotation = prefs.getInt(KEY_CAMERA_ROTATION_DEG, 0)
        }

        scaleDetector = android.view.ScaleGestureDetector(
            requireContext(),
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
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

        binding.ivVideoImage.setStatusListener(rtspStatusImageListener)
        binding.ivVideoImage.setDataListener(rtspDataListener)
        binding.ivVideoImage.enablePinchZoom()

        binding.ivVideoImage.videoRotation = prefs.getInt(KEY_CAMERA_ROTATION_DEG, 0)
        binding.ivVideoImage.videoDecoderType = if (prefs.getBoolean(
                KEY_USE_HW_DECODER,
                false
            )
        ) VideoDecodeThread.DecoderType.HARDWARE else VideoDecodeThread.DecoderType.SOFTWARE

        binding.bnStartStopImage?.setOnClickListener {
            if (binding.ivVideoImage.isStarted()) {
                binding.ivVideoImage.stop()
                stopStatistics()
            } else {
                startRtspStream()
            }
        }

        binding.btnEnterLandscape?.setOnClickListener {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        binding.btnSnapshot.setOnClickListener {
            Log.d(TAG, ">>> Snapshot button CLICKED <<<")

            if (!binding.ivVideoImage.isStarted()) {
                Log.w(TAG, "Stream not started")
                Toast.makeText(
                    requireContext(),
                    "⚠️ Stream belum aktif, tekan tombol START untuk memulai stream",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val hasBitmap = synchronized(bitmapLock) { lastBitmap != null }
            if (!hasBitmap) {
                Log.w(TAG, "No bitmap available yet")
                Toast.makeText(
                    requireContext(),
                    "⚠️ Belum ada frame video, tunggu beberapa detik...",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            if (sessionDir == null) {
                Log.e(TAG, "Session directory is null")
                Toast.makeText(
                    requireContext(),
                    "❌ Direktori sesi tidak tersedia",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            ss.set(true)

            Log.d(TAG, "Snapshot scheduled for next frame")
            Toast.makeText(
                requireContext(),
                "📸 Mengambil snapshot...",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnRecordVideo.setOnClickListener {
            if (record.get()) {
                stopVideoRecording()
            } else {
                startVideoRecording()
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    showExitConfirmDialog()
                }
            }
        )

        binding.topAppBar.setNavigationOnClickListener {
            showExitConfirmDialog()
        }

        binding.btnBackLite?.setOnClickListener {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        binding.rvThumbs.apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(
                requireContext(),
                4
            )
            thumbsAdapter = ThumbAdapter { item, position ->
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
                    if (selectionMode) {
                        binding.topAppBar.title = "$count dipilih"
                    }
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

    private fun applyZoomMatrix() {
        val m = android.graphics.Matrix()
        m.postScale(currentScale, currentScale, focusX, focusY)
        binding.ivVideoImage.imageMatrix = m
    }

    override fun onResume() {
        if (DEBUG) Log.v(TAG, "onResume()")
        super.onResume()

        if (isLandscape()) {
            requireActivity().window.statusBarColor =
                ContextCompat.getColor(requireContext(), R.color.colorBlack)
        } else {
            requireActivity().window.statusBarColor =
                ContextCompat.getColor(requireContext(), R.color.colorButton)
        }

        liveViewModel.loadParams(requireContext())

        if (!binding.ivVideoImage.isStarted()) {
            Log.d(TAG, "Stream not started, auto-starting...")
            startRtspStream()
        } else {
            Log.d(TAG, "Stream already running")
        }

        if (clockJob?.isActive != true) {
            startOverlayClock()
        }
    }

    override fun onPause() {
        super.onPause()

        if (record.get()) {
            Log.w(TAG, "⚠️ Stopping recording on pause")
            stopVideoRecording()
        }

        hudHandler.removeCallbacks(hudTick)
        binding.recordHud.visibility = View.GONE

        liveViewModel.saveParams(requireContext())

        if (isLandscape()) {
            requireActivity().window.statusBarColor =
                ContextCompat.getColor(requireContext(), R.color.colorBlack)
        } else {
            requireActivity().window.statusBarColor =
                ContextCompat.getColor(requireContext(), R.color.colorButton)
        }
    }

    private fun stopStreamAndExit() {
        stopVideoRecording()

        if (binding.ivVideoImage.isStarted()) {
            binding.ivVideoImage.stop()
        }
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

    private val hudTick = object : Runnable {
        override fun run() {
            if (!record.get()) return
            val elapsed = android.os.SystemClock.elapsedRealtime() - recordStartElapsedMs
            binding.tvRecordTimer.text = formatHmsFixed(elapsed)
            hudHandler.postDelayed(this, 1000L)
        }
    }

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

    private fun shareSelected() {
        val files = thumbsAdapter.getSelectedItems()
        if (files.isEmpty()) {
            Toast.makeText(requireContext(), "Belum ada yang dipilih", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = arrayListOf<android.net.Uri>()
        var hasVideo = false
        files.forEach { f ->
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", f
            )
            uris.add(uri)
            if (f.extension.equals("mp4", true)) hasVideo = true
        }
        val mime = if (hasVideo) "*/*" else "image/*"
        val send = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("shared", uris.first())
        }
        startActivity(android.content.Intent.createChooser(send, "Bagikan"))
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

    private fun formatHmsFixed(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun onSavePressed() {
        val ctx = requireContext()

        if (selectionMode) {
            val all = thumbsAdapter.currentList.map { it.file }
            val selected = thumbsAdapter.getSelectedItems().toSet()
            val toDelete = all.filterNot { selected.contains(it) }

            var ok = 0
            var fail = 0
            toDelete.forEach { f ->
                if (runCatching { f.delete() }.isSuccess) ok++ else fail++
            }

            refreshThumbs()
            exitSelectionMode()
        }

        MaterialAlertDialogBuilder(ctx, R.style.MyAlertDialogTheme)
            .setTitle("Simpan Berhasil")
            .setMessage("Data sesi telah disimpan.")
            .setPositiveButton("OK") { _, _ ->
                stopStreamAndExit()
            }
            .show()
    }

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
            repeat(10) {
                bar.setProgressCompat((it + 1) * 10, true)
                delay(50)
            }

            withContext(Dispatchers.IO) {
                delay(500)

                // ✅ SIMPAN METADATA SEBELUM KELUAR
                saveSessionMetadata()
            }

            if (selectionMode) {
                val all = thumbsAdapter.currentList.map { it.file }
                val keep = thumbsAdapter.getSelectedItems().toSet()
                val toDelete = all.filterNot { keep.contains(it) }
                toDelete.forEach { runCatching { it.delete() } }
            }

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

    private fun toggleSelectionMode(menuItem: android.view.MenuItem) {
        selectionMode = !selectionMode
        thumbsAdapter.setSelectionMode(selectionMode)

        if (selectionMode) {
            menuItem.title = "Selesai"
            Toast.makeText(
                requireContext(),
                "Pilih item dengan mengetuk thumbnail",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            menuItem.title = "Pilih"
            val selected = thumbsAdapter.getSelectedItems()
            Toast.makeText(requireContext(), "Terpilih: ${selected.size} item", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun shareMultiple(files: List<File>) {
        if (files.isEmpty()) return
        val uris = java.util.ArrayList<android.net.Uri>()
        val mime = if (files.any { it.extension.equals("mp4", true) }) "*/*" else "image/*"
        files.forEach { f ->
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", f
            )
            uris.add(uri)
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("shared", uris.first())
        }
        startActivity(android.content.Intent.createChooser(intent, "Bagikan"))
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

    private fun startVideoRecording() {
        Log.d(TAG, "=== startVideoRecording() ===")

        var dir = videosDir
        if (dir == null || !dir.exists()) {
            Log.w(TAG, "⚠️ Video dir unavailable, creating...")
            sessionDir?.let { parent ->
                dir = StorageUtils.ensureChildDir(parent, "Video")
                videosDir = dir
                Log.d(TAG, "✅ Video dir created: ${dir?.absolutePath}")
            }
        }

        if (dir == null || !dir.exists()) {
            Log.e(TAG, "❌ Failed to create video directory!")
            Toast.makeText(
                requireContext(),
                "❌ Gagal membuat direktori video",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        Log.d(TAG, "✅ Recording to: ${dir.absolutePath}")

        val out = File(dir, "vid_${StorageUtils.timestampWIB()}.mp4")
        videoOutputFile = out

        val width = if (ivVideoImageResolution.first > 0) {
            ivVideoImageResolution.first
        } else {
            lastFrameSize.first.coerceAtLeast(640)
        }

        val height = if (ivVideoImageResolution.second > 0) {
            ivVideoImageResolution.second
        } else {
            lastFrameSize.second.coerceAtLeast(360)
        }

        Log.d(TAG, "Recording resolution: ${width}x${height}")

        try {
            Log.d(TAG, "Creating encoder...")
            recorder = RealtimeBitmapEncoder(
                context = requireContext(),
                width = width,
                height = height,
                outputFile = out
            )
            recorder.start()

            record.set(true)
            recordStartElapsedMs = android.os.SystemClock.elapsedRealtime()

            binding.recordHud.visibility = View.VISIBLE
            binding.tvRecordTimer.text = "00:00:00"
            hudHandler.removeCallbacks(hudTick)
            hudHandler.post(hudTick)

            binding.btnRecordVideo.setImageResource(R.drawable.btn_stop)

            Log.d(TAG, "✅ Recording started to: ${out.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Recording ERROR: ${e.message}", e)
            record.set(false)

            Toast.makeText(
                requireContext(),
                "❌ Gagal memulai rekaman: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopVideoRecording() {
        Log.d(TAG, "=== stopVideoRecording() ===")

        if (!record.get()) {
            Log.w(TAG, "Not recording")
            return
        }

        record.set(false)

        hudHandler.removeCallbacks(hudTick)
        binding.recordHud.visibility = View.GONE
        binding.btnRecordVideo.setImageResource(R.drawable.majesticons_video)

        try {
            if (this::recorder.isInitialized) {
                recorder.stop()
                Log.d(TAG, "✅ Encoder stopped successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping encoder: ${e.message}", e)
        }

        val file = videoOutputFile
        videoOutputFile = null

        if (file != null && file.exists()) {
            // ✅ SAVE METADATA saat video pertama disimpan
            if (!isMetadataSaved) {
                saveSessionMetadata()
            }

            Toast.makeText(requireContext(), "✅ VIDEO TERSIMPAN!", Toast.LENGTH_SHORT).show()
            binding.rvThumbs.postDelayed({ refreshThumbs() }, 300)
        } else {
            Toast.makeText(requireContext(), "⚠️ File video tidak ditemukan", Toast.LENGTH_SHORT)
                .show()
        }

        Log.d(TAG, "✅ Recording stopped")
    }

    private fun startRtspStream() {
        Log.d(TAG, "=== startRtspStream() ===")

        val rtspUrl = liveViewModel.rtspRequest.value
        Log.d(TAG, "RTSP URL: $rtspUrl")

        if (rtspUrl.isNullOrBlank()) {
            Log.e(TAG, "❌ RTSP URL is empty!")
            Toast.makeText(requireContext(), "❌ URL RTSP tidak valid", Toast.LENGTH_LONG).show()
            return
        }

        val uri = Uri.parse(rtspUrl)
        binding.ivVideoImage.apply {
            init(
                uri = uri,
                username = liveViewModel.rtspUsername.value,
                password = liveViewModel.rtspPassword.value,
                userAgent = "cervexa-client-android"
            )

            videoDecoderType = VideoDecodeThread.DecoderType.HARDWARE

            onRtspImageBitmapListener = object : RtspImageView.RtspImageBitmapListener {
                override fun onRtspImageBitmapObtained(bitmap: Bitmap) {
                    if (!isAdded || view == null) {
                        Log.w(TAG, "Fragment not attached, skipping frame")
                        return
                    }

                    val safeBitmap = synchronized(bitmapLock) {
                        lastBitmap?.recycle()
                        lastBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        lastBitmap
                    } ?: return

                    val bmWithOverlay = processTextToBitmapSafe(safeBitmap)

                    if (record.get() && this@VideoFragmentMobile::recorder.isInitialized) {
                        try {
                            recorder.submitBitmap(bmWithOverlay)
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error submitting bitmap: ${e.message}", e)
                        }
                    }

                    if (ss.get()) {
                        ss.set(false)
                        Log.d(TAG, "=== Processing snapshot ===")

                        var dir = snapshotsDir
                        if (dir == null || !dir.exists()) {
                            Log.w(TAG, "⚠️ Snapshots dir unavailable, creating...")
                            sessionDir?.let { parent ->
                                dir = StorageUtils.ensureChildDir(parent, "Snapshots")
                                snapshotsDir = dir
                                Log.d(TAG, "✅ Snapshots dir created: ${dir?.absolutePath}")
                            }
                        }

                        if (dir == null || !dir.exists()) {
                            Log.e(TAG, "❌ Failed to create snapshot directory!")
                            requireActivity().runOnUiThread {
                                Toast.makeText(
                                    requireContext(),
                                    "❌ Gagal membuat direktori snapshot",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            return
                        }

                        Log.d(TAG, "✅ Snapshot dir: ${dir.absolutePath}")

                        runCatching {
                            StorageUtils.saveJpegWithPrefix(dir, bmWithOverlay, prefix = "ss")
                        }.onSuccess { savedFile ->
                            Log.d(TAG, "✅ Snapshot saved: ${savedFile.absolutePath}")

                            // ✅ SAVE METADATA saat snapshot pertama disimpan
                            if (!isMetadataSaved) {
                                saveSessionMetadata()
                            }

                            requireActivity().runOnUiThread {
                                Toast.makeText(
                                    requireContext(),
                                    "📸 SNAPSHOT TERSIMPAN!",
                                    Toast.LENGTH_SHORT
                                ).show()

                                refreshThumbs()
                            }
                        }.onFailure { error ->
                            Log.e(TAG, "❌ Save failed: ${error.message}", error)

                            requireActivity().runOnUiThread {
                                Toast.makeText(
                                    requireContext(),
                                    "❌ Gagal menyimpan: ${error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "Starting RTSP stream with low latency settings...")

            start(
                requestVideo = true,
                requestAudio = false,
                requestApplication = false
            )
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

    private fun processTextToBitmapSafe(src: Bitmap): Bitmap {
        if (src == null || src.isRecycled) {
            Log.e(TAG, "❌ Invalid bitmap in processTextToBitmapSafe")
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        val bitmap = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)

        val formatted: String = if (android.os.Build.VERSION.SDK_INT >= 26) {
            val current = java.time.ZonedDateTime.now()
            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
            current.format(formatter)
        } else {
            val dateFormat: DateFormat =
                SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
            dateFormat.format(Date())
        }

        val canvas = Canvas(bitmap)

        val paintTextWhite = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        val paintBox = Paint().apply {
            color = Color.argb(128, 0, 0, 0)
            style = Paint.Style.FILL
        }

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
            paintTextWhite
        )

        canvas.drawRect(0f, bitmap.height.toFloat() - 65f, 650f, bitmap.height.toFloat(), paintBox)
        if (patientNrm.isEmpty()) {
            canvas.drawText("$patientRs", 20f, bitmap.height.toFloat() - 20f, paintTextWhite)
        } else {
            canvas.drawText(
                "$patientRs/$patientNrm",
                20f,
                bitmap.height.toFloat() - 20f,
                paintTextWhite
            )
        }

        return bitmap
    }

    private fun saveFrame(bitmap: Bitmap) {
        val dir = sessionDir
        if (dir == null || !isAdded) return

        runCatching {
            StorageUtils.saveJpeg(dir, bitmap)
        }.onSuccess { saved ->
            Toast.makeText(requireContext(), "Tersimpan: ${saved.name}", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(requireContext(), "Gagal simpan: ${it.message}", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun View.enablePinchZoom(
        minScale: Float = 1f,
        maxScale: Float = 3.5f
    ) {
        var scale = 1f
        var lastFocusX = 0f
        var lastFocusY = 0f

        val scaleDetector = android.view.ScaleGestureDetector(
            context,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    val factor = detector.scaleFactor
                    val newScale = (scale * factor).coerceIn(minScale, maxScale)
                    pivotX = detector.focusX
                    pivotY = detector.focusY
                    scaleX = newScale
                    scaleY = newScale
                    scale = newScale
                    return true
                }

                override fun onScaleBegin(detector: android.view.ScaleGestureDetector): Boolean {
                    lastFocusX = detector.focusX
                    lastFocusY = detector.focusY
                    return true
                }
            })

        val tapDetector = android.view.GestureDetector(
            context,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                        .setDuration(150).start()
                    scale = 1f
                    return true
                }

                override fun onScroll(
                    e1: android.view.MotionEvent?,
                    e2: android.view.MotionEvent,
                    dx: Float, dy: Float
                ): Boolean {
                    if (scale > 1f) {
                        translationX -= dx
                        translationY -= dy
                        val maxTransX = (width * (scale - 1f)) / 2f
                        val maxTransY = (height * (scale - 1f)) / 2f
                        translationX = translationX.coerceIn(-maxTransX, maxTransX)
                        translationY = translationY.coerceIn(-maxTransY, maxTransY)
                        return true
                    }
                    return false
                }
            })

        setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            tapDetector.onTouchEvent(ev)
            scaleDetector.isInProgress || scale > 1f
        }
    }

    private fun autoSaveEveryInterval(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastAutoSaveAtMs >= minAutoSaveIntervalMs) {
            lastAutoSaveAtMs = now
            saveFrame(bitmap)
        }
    }

    companion object {
        private val TAG: String = VideoFragmentMobile::class.java.simpleName
        private const val DEBUG = true
    }
}