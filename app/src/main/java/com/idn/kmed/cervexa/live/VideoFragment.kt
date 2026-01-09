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
import com.idn.kmed.cervexa.SettingsActivity.Companion.KEY_CAMERA_ROTATION_DEG
import com.idn.kmed.cervexa.databinding.FragmentVideoBinding
import com.idn.kmed.cervexa.record.RealtimeBitmapEncoder
import com.idn.kmed.cervexa.utils.*
import kotlinx.coroutines.*
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

// Implement IVLCVout.Callback di level Class
class VideoFragment : Fragment() {

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

    // ==== VLC Components ====
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var textureView: TextureView? = null // View untuk render video

    // ==== Encode / Flags ====
    private lateinit var recorder: RealtimeBitmapEncoder

    // Recording Logic
    private var recordingJob: Job? = null
    private val record = AtomicBoolean(false)
    private var videoOutputFile: File? = null
    private var videosDir: File? = null

    // === HUD durasi rekam ===
    private var recordStartElapsedMs = 0L
    private val hudHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // === Selection Mode & Media ===
    private var selectionMode = false
    private lateinit var thumbsAdapter: ThumbAdapter
    private var allMediaItems: List<MediaItem> = emptyList()

    // === Tanggal Media ===
    private val today = Date()
    private val formatter = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
    private val formattedDate = formatter.format(today)

    // === Gesture / Zoom ===
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

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

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

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        if (DEBUG) Log.v(TAG, "onCreateView()")

        liveViewModel = ViewModelProvider(this)[LiveViewModel::class.java]
        binding = FragmentVideoBinding.inflate(inflater, container, false)

        // Bind TextureView dari XML baru
        textureView = binding.textureView

        // Gesture: pinch to zoom + double tap reset
        scaleDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val prev = currentScale
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
                        // Implementasi scroll sederhana pada TextureView
                        val currentTransX = textureView?.translationX ?: 0f
                        val currentTransY = textureView?.translationY ?: 0f
                        textureView?.translationX = currentTransX - distanceX
                        textureView?.translationY = currentTransY - distanceY
                    }
                    return true
                }
            }
        )

        // Pasang Touch Listener di TextureView
        textureView?.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            gestureDetector.onTouchEvent(ev)
            true
        }

        // Start/Stop Stream
        binding.bnStartStopImage?.setOnClickListener {
            if (mediaPlayer?.isPlaying == true) {
                stopStreamAndExit() // Atau stopVlcStream saja
            } else {
                startVlcStream()
            }
        }

        binding.btnEnterLandscape?.setOnClickListener {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        // SNAPSHOT (Mengambil bitmap dari TextureView)
        binding.btnSnapshot.setOnClickListener {
            takeSnapshot()
        }

        // RECORD VIDEO
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

        // Setup Thumbs Adapter
        binding.rvThumbs.apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 4)
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

        return binding.root
    }

    private fun applyZoomMatrix() {
        // Zoom pada TextureView menggunakan ScaleX/ScaleY
        textureView?.apply {
            pivotX = focusX
            pivotY = focusY
            scaleX = currentScale
            scaleY = currentScale
        }
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

        // Auto Start VLC
        if (mediaPlayer == null || mediaPlayer?.isPlaying == false) {
            startVlcStream()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isLandscape()) {
            requireActivity().window.statusBarColor =
                ContextCompat.getColor(requireContext(), R.color.colorBlack)
        } else {
            requireActivity().window.statusBarColor =
                ContextCompat.getColor(requireContext(), R.color.colorButton)
        }
        liveViewModel.saveParams(requireContext())

        // Stop recording jika pause (pindah aplikasi)
        if (record.get()) {
            stopVideoRecording()
        }
        stopVlcStream() // Release VLC resources
    }

    // ==========================================
    // VLC STREAMING LOGIC
    // ==========================================

    private fun startVlcStream() {
        binding.pbLoadingImage.visibility = View.VISIBLE
        binding.vShutterImage.visibility = View.VISIBLE

        try {
            val options = ArrayList<String>().apply {
                add("--rtsp-tcp")
                add("--network-caching=200")
                add("--drop-late-frames")
                add("--avcodec-hw=any")
            }

            libVlc = LibVLC(requireContext(), options)
            mediaPlayer = MediaPlayer(libVlc)

            val vout = mediaPlayer!!.vlcVout
            vout.setVideoView(textureView)

            // 1. Pasang Callback untuk Surface (Hanya Created & Destroyed)
            vout.addCallback(object : IVLCVout.Callback {
                override fun onSurfacesCreated(vlcVout: IVLCVout?) {
                    // Kosongkan
                }

                override fun onSurfacesDestroyed(vlcVout: IVLCVout?) {
                    // Kosongkan
                }
            })

            // 2. Pasang Listener Layout di dalam attachViews()
            //    Method onNewVideoLayout ADA DI SINI, bukan di Callback
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

                        val container = binding.videoContainer
                        val viewWidth = container.width
                        val viewHeight = container.height

                        val vidRatio = width.toFloat() / height.toFloat()
                        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()

                        val lp = textureView?.layoutParams
                        if (vidRatio > viewRatio) {
                            lp?.width = viewWidth
                            lp?.height = (viewWidth / vidRatio).toInt()
                        } else {
                            lp?.height = viewHeight
                            lp?.width = (viewHeight * vidRatio).toInt()
                        }
                        textureView?.layoutParams = lp
                    }
                }
            })

            // Setup URL & Play
            val rawUrl = liveViewModel.rtspRequest.value ?: ""
            val user = liveViewModel.rtspUsername.value ?: ""
            val pass = liveViewModel.rtspPassword.value ?: ""

            val finalUrl = if (user.isNotEmpty() && !rawUrl.contains("//$user")) {
                rawUrl.replace("rtsp://", "rtsp://$user:$pass@")
            } else {
                rawUrl
            }

            val media = Media(libVlc, Uri.parse(finalUrl))
            media.setHWDecoderEnabled(true, false)
            media.addOption(":network-caching=200")

            mediaPlayer?.media = media
            media.release()

            mediaPlayer?.play()

            binding.tvStatusImage?.text = "RTSP Connected (VLC HW)"
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

        binding.tvStatusImage?.text = "RTSP Disconnected"
        binding.vShutterImage.visibility = View.VISIBLE
        setKeepScreenOn(false)
    }

    private fun stopStreamAndExit() {
        stopVideoRecording()
        stopVlcStream()

        val intent = Intent(requireContext(), HomeActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra("open_tab", "media")
        startActivity(intent)
    }

    // ==========================================
    // RECORDING LOGIC (Frame Grabber)
    // ==========================================

    private fun startVideoRecording() {
        val dir = videosDir ?: sessionDir
        if (dir == null) {
            Toast.makeText(requireContext(), "Folder sesi belum siap", Toast.LENGTH_SHORT).show()
            return
        }

        val out = File(dir, "vid_${StorageUtils.timestampWIB()}.mp4")
        videoOutputFile = out

        // Downscale resolusi rekam ke 720p untuk performa Mi TV Stick
        // Mengambil bitmap 1080p setiap frame berat di CPU.
        val recWidth = 1280
        val recHeight = 720

        try {
            recorder = RealtimeBitmapEncoder(requireContext(), recWidth, recHeight, out)
            recorder.start()
            record.set(true)

            recordStartElapsedMs = android.os.SystemClock.elapsedRealtime()
            binding.recordHud.visibility = View.VISIBLE
            binding.tvRecordTimer.text = "00:00:00"
            hudHandler.removeCallbacks(hudTick)
            hudHandler.post(hudTick)
            binding.btnRecordVideo.setImageResource(R.drawable.btn_stop)

            // Jalankan Grabber Loop
            startFrameGrabber(recWidth, recHeight)

        } catch (e: Exception) {
            record.set(false)
            Toast.makeText(requireContext(), "Gagal mulai rekam: ${e.message}", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun stopVideoRecording() {
        if (!record.get()) return

        // Hentikan loop grabber
        recordingJob?.cancel()

        runCatching { recorder.stop() }
        record.set(false)

        hudHandler.removeCallbacks(hudTick)
        binding.recordHud.visibility = View.GONE
        binding.btnRecordVideo.setImageResource(R.drawable.majesticons_video)

        videoOutputFile = null
        binding.rvThumbs.postDelayed({ refreshThumbs() }, 300)
        Toast.makeText(requireContext(), "Video Tersimpan", Toast.LENGTH_SHORT).show()
    }

    /**
     * Mengambil frame dari TextureView secara manual dan mengirim ke Encoder.
     */
    private fun startFrameGrabber(width: Int, height: Int) {
        recordingJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            while (record.get() && isActive) {
                val start = System.currentTimeMillis()

                // Ambil bitmap di Main Thread
                val bmp = withContext(Dispatchers.Main) {
                    // getBitmap melakukan resize otomatis, hemat memori
                    textureView?.getBitmap(width, height)
                }

                if (bmp != null) {
                    val overlayBmp = processTextToBitmapSafe(bmp)
                    recorder.submitBitmap(overlayBmp)
                }

                // Kalkulasi delay untuk menjaga ~20 FPS (50ms)
                val elapsed = System.currentTimeMillis() - start
                val wait = (50 - elapsed).coerceAtLeast(0)
                delay(wait)
            }
        }
    }

    private fun takeSnapshot() {
        val dir = snapshotsDir ?: sessionDir ?: return

        // Ambil bitmap resolusi penuh layar (atau di-clamp jika perlu)
        val bmp = textureView?.bitmap
        if (bmp != null) {
            val withOverlay = processTextToBitmapSafe(bmp)

            runCatching {
                StorageUtils.saveJpegWithPrefix(dir, withOverlay, prefix = "ss")
            }.onSuccess {
                Toast.makeText(requireContext(), "Snapshot Tersimpan", Toast.LENGTH_SHORT).show()
                refreshThumbs()
            }.onFailure {
                Toast.makeText(
                    requireContext(),
                    "Gagal Snapshot: ${it.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            Toast.makeText(requireContext(), "Gagal mengambil gambar stream", Toast.LENGTH_SHORT)
                .show()
        }
    }

    // ==========================================
    // UTILITIES
    // ==========================================

    private fun processTextToBitmapSafe(src: Bitmap): Bitmap {
        val bitmap = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)

        val formatted: String = if (android.os.Build.VERSION.SDK_INT >= 26) {
            val current = ZonedDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
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
        }
        val paintBox = Paint().apply {
            color = Color.argb(128, 0, 0, 0)
            style = Paint.Style.FILL
        }

        // Tutup timestamp bawaan jika ada (pojok kanan bawah)
        canvas.drawRect(
            bitmap.width.toFloat() - 360f,
            bitmap.height.toFloat() - 60f,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            Paint().apply { color = "#3F3F3F".toColorInt() }
        )

        // Timestamp
        canvas.drawText(
            formatted,
            bitmap.width.toFloat() - 350f,
            bitmap.height.toFloat() - 20f,
            paintTextWhite
        )

        // Identitas Pasien
        canvas.drawRect(0f, bitmap.height.toFloat() - 65f, 650f, bitmap.height.toFloat(), paintBox)
        val infoText = if (patientNrm.isEmpty()) "$patientRs" else "$patientRs/$patientNrm"
        canvas.drawText(infoText, 20f, bitmap.height.toFloat() - 20f, paintTextWhite)

        return bitmap
    }

    private fun setKeepScreenOn(enable: Boolean) {
        activity?.apply {
            if (enable) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // === HUD Timer ===
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

    // === Selection Mode & Dialogs ===

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
        if (files.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
            .setTitle("Hapus ${files.size} item?")
            .setPositiveButton("Hapus") { _, _ -> deleteFiles(files) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun deleteFiles(files: List<File>) {
        val deletedPaths = mutableListOf<String>()
        files.forEach { f ->
            if (runCatching { f.delete() }.isSuccess) deletedPaths.add(f.absolutePath)
        }
        refreshThumbs()
        exitSelectionMode()
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

    private fun showExitConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
            .setTitle("Selesaikan Sesi?")
            .setMessage("Keluar dan selesaikan sesi?")
            .setPositiveButton("Selesai") { _, _ -> stopStreamAndExit() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun refreshThumbs() {
        val parent = sessionDir ?: return
        val imgs =
            File(parent, "Snapshots").listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
                .orEmpty()
        val vids =
            File(parent, "Video").listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
                .orEmpty()

        val merged = (imgs.map { MediaItem(it, MediaType.IMAGE) } + vids.map {
            MediaItem(
                it,
                MediaType.VIDEO
            )
        })
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
        val dialog = BottomSheetDialog(
            requireContext(),
            com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog
        )
        val v = layoutInflater.inflate(R.layout.bs_patient_info, null)
        dialog.setContentView(v)

        // Setup Views in BS
        val btnClose = v.findViewById<ImageButton>(R.id.btnClose)
        val tvNama = v.findViewById<TextView>(R.id.tvNama)
        val tvNik = v.findViewById<TextView>(R.id.tvNik)
        val tvDob = v.findViewById<TextView>(R.id.tvDob)
        val tvNrm = v.findViewById<TextView>(R.id.tvNrm)

        tvNama.text = patientNama
        tvNik.text = patientNik
        tvNrm.text = patientNrm.ifEmpty { "-" }
        tvDob.text = if (patientDobUtc > 0) SimpleDateFormat("dd/MM/yyyy", Locale("id")).format(
            Date(patientDobUtc)
        ) else "-"

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showSaveConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
            .setTitle("Konfirmasi")
            .setMessage("Simpan media dan tutup sesi?")
            .setPositiveButton("Simpan") { _, _ -> showSavingProgressAndExecute() }
            .setNegativeButton("Batal", null)
            .show()
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
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            repeat(10) {
                bar.setProgressCompat((it + 1) * 10, true)
                delay(50)
            }
            // Simulate work
            withContext(Dispatchers.IO) { delay(500) }

            progressDialog.dismiss()
            showSaveSuccessDialog()
        }
    }

    private fun showSaveSuccessDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_save_success, null)
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
            .setView(v).create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom)
        dialog.show()

        v.findViewById<TextView>(R.id.tvAction)?.setOnClickListener {
            dialog.dismiss()
            stopStreamAndExit()
        }
    }

    companion object {
        private val TAG: String = VideoFragment::class.java.simpleName
        private const val DEBUG = true
    }
}