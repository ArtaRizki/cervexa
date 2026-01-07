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
import com.idn.kmed.cervexa.databinding.FragmentVideoBinding
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
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.OnBackPressedCallback
import com.idn.kmed.cervexa.HomeActivity
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.utils.MediaItem
import com.idn.kmed.cervexa.utils.MediaType
import com.idn.kmed.cervexa.utils.ThumbAdapter
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.shape.CornerFamily
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.idn.kmed.cervexa.SettingsActivity.Companion.KEY_CAMERA_ROTATION_DEG
import com.idn.kmed.cervexa.SettingsActivity.Companion.KEY_USE_HW_DECODER
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay


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
//                vShutterImage.visibility = View.VISIBLE
                // Saat putus, tutup lagi hanya area video
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
//                vShutterImage.visibility = View.GONE
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
            args.getString("sessionDirPath")?.let { p ->
                if (p.isNotBlank()) sessionDir = File(p)
            }
            patientNama = args.getString("patient_nama").orEmpty()
            patientNik = args.getString("patient_nik").orEmpty()
            patientRs = args.getString("patient_rs").orEmpty()
            patientNrm = args.getString("patient_nrm").orEmpty()
            patientDobUtc = args.getLong("patient_dob_utc", -1L)
            patientAge = PatientUtils.calculateAge(patientDobUtc)

            // ... existing
            sessionDir =
                args.getString("sessionDirPath")?.takeIf { it.isNotBlank() }?.let { File(it) }
            // buat subfolder Snapshots and Video
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
        scaleDetector = android.view.ScaleGestureDetector(
            requireContext(),
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    val prev = currentScale
                    currentScale =
                        (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
                    // titik fokus agar zoom terasa natural
                    focusX = detector.focusX
                    focusY = detector.focusY
                    applyZoomMatrix()
                    return true
                }
            })

        gestureDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {

                override fun onDown(e: MotionEvent): Boolean =
                    true  // wajib return true agar onScroll dipanggil

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    currentScale = if (currentScale > 1.01f) 1f else 2f
                    focusX = e.x
                    focusY = e.y
                    applyZoomMatrix()
                    return true
                }

                // Perbaikan signature: e2 non-null
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

        // terapkan ke view video & shutter agar sentuhan tetap tertangkap
        val touch = View.OnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            gestureDetector.onTouchEvent(ev)
            true
        }
        binding.ivVideoImage.setOnTouchListener(touch)
        binding.vShutterImage.setOnTouchListener(touch)

        // Listener ke widget image RTSP
        binding.ivVideoImage.setStatusListener(rtspStatusImageListener)
        binding.ivVideoImage.setDataListener(rtspDataListener)
        // aktifkan pinch zoom di live view
        binding.ivVideoImage.enablePinchZoom()

        // Setting Rotation and Encoder from sharePref
        binding.ivVideoImage.videoRotation = prefs.getInt(KEY_CAMERA_ROTATION_DEG, 0)
        binding.ivVideoImage.videoDecoderType = VideoDecodeThread.DecoderType.HARDWARE

        // Tombol start/stop stream
        binding.bnStartStopImage?.setOnClickListener {
            if (binding.ivVideoImage.isStarted()) {
                binding.ivVideoImage.stop()
                stopStatistics()
            } else {
                startRtspStream()
            }
        }

        //Eneter Lancscape
        binding.btnEnterLandscape?.setOnClickListener {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        // Tombol Save Image (Snapshot)
        binding.btnSnapshot.setOnClickListener {
            ss.set(true)
//            Toast.makeText(requireContext(), "Snapshot dijadwalkan (akan tersimpan pada frame berikutnya)", Toast.LENGTH_SHORT).show()
        }

        // Tombol Record Video
        binding.btnRecordVideo.setOnClickListener {
            if (record.get()) {
                stopVideoRecording()
//                refreshThumbs()
            } else {
                startVideoRecording()
            }
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

        // Back di toolbar
        binding.topAppBar.setNavigationOnClickListener {
            showExitConfirmDialog()
        }

        //Back Landscap
        binding.btnBackLite?.setOnClickListener {
            // Ubah ke mode portrait
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // setup thumbs recycler
        binding.rvThumbs.apply {
//            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
//                requireContext(),
//                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
//                false
//            )
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
                if (!selectionMode) enterSelectionMode()  // ini fungsi yang sudah kamu punya
            }
            adapter = thumbsAdapter

        }

        // di onCreateView setelah binding di-set
        /*binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_info_pasien -> {
                    showPatientInfoBottomSheet()
                    true
                }
                R.id.action_pilih -> {
                    toggleSelectionMode(item)
                    true
                }
                else -> false
            }
        }

        binding.topAppBar.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                R.id.action_info_pasien -> { showPatientInfoBottomSheet(); true }
                R.id.action_pilih -> { toggleSelectionMode(mi); true }
                else -> false
            }
        }*/

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

        // Set Media Tanggal
        binding.tvMediaTgl?.text = formattedDate

        // pertama kali load
        refreshThumbs()


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
                putExtra("forceLandscape", isLandscape()) // <— penting
            }
        )
    }

    private fun applyZoomMatrix() {
        val m = android.graphics.Matrix()
        // skala dari titik fokus (supaya zoom ke titik pinch/double tap)
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

        // --- PERBAIKAN POIN 6: OPTIMALISASI DECODER ---
        // Paksa cek Hardware Decoder dari sini sebelum stream mulai
        val useHwDecoder = prefs.getBoolean(
            KEY_USE_HW_DECODER,
            true
        ) // Default ke TRUE (Hardware) agar tidak delay
        binding.ivVideoImage.videoDecoderType = if (useHwDecoder)
            VideoDecodeThread.DecoderType.HARDWARE
        else
            VideoDecodeThread.DecoderType.SOFTWARE

        Log.i(TAG, "Decoder set to: ${binding.ivVideoImage.videoDecoderType}")

        // AUTOPLAY jika belum jalan
        if (!binding.ivVideoImage.isStarted()) {
            startRtspStream()
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
        if (record.get()) {
            stopVideoRecording()
        } else {
            //Untuk safety net HUD
            hudHandler.removeCallbacks(hudTick)
            binding.recordHud.visibility = View.GONE
        }
    }

    private fun stopStreamAndExit() {
        // Hentikan perekaman jika sedang aktif
//        if (this::recorder.isInitialized && record.get()) {
//            runCatching { recorder.stop() }
//
//            record.set(false)
//        }
        stopVideoRecording()

        // Hentikan stream & statistik
        if (binding.ivVideoImage.isStarted()) {
            binding.ivVideoImage.stop()
        }
        stopStatistics()

        // (opsional) tampilkan shutter agar layar tidak freeze frame
        binding.vShutterImage.apply { alpha = 1f; visibility = View.VISIBLE }

        // Tutup Activity sekarang (kembali ke layar sebelumnya)
        // requireActivity().finish()
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

        // simpan ke field supaya bisa diakses saat klik
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
            binding.tvRecordTimer.text = formatHmsFixed(elapsed) // HH:MM:SS
            hudHandler.postDelayed(this, 1000L) // update tiap 1 detik
        }
    }

    private fun enterSelectionMode() {
        selectionMode = true
        binding.topAppBar.menu.clear()
        binding.topAppBar.inflateMenu(R.menu.menu_video_fragment_select)
        binding.topAppBar.title = "0 dipilih"

        // aksi di contextual menu
        binding.topAppBar.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
//                R.id.action_share_selected -> { shareSelected(); true }
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
        var ok = 0;
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

        // refresh galeri internal di app
        refreshThumbs()

        // update galeri sistem (supaya hilang juga dari Gallery/Google Photos)
        if (deletedPaths.isNotEmpty()) {
            android.media.MediaScannerConnection.scanFile(
                requireContext(),
                deletedPaths.toTypedArray(),
                null,
                null
            )
        }

        // keluar mode pilih
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
        binding.topAppBar.inflateMenu(R.menu.menu_video_fragment) // kembali menu normal
        binding.topAppBar.title = "Cervexa Colposcope"
        // restore listener menu normal
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
            // Hapus semua file yang TIDAK dipilih
            val all = thumbsAdapter.currentList.map { it.file }
            val selected = thumbsAdapter.getSelectedItems().toSet()
            val toDelete = all.filterNot { selected.contains(it) }

            var ok = 0;
            var fail = 0
            toDelete.forEach { f ->
                if (runCatching { f.delete() }.isSuccess) ok++ else fail++
            }

            refreshThumbs()
            exitSelectionMode()
        }

        // Popup sukses lalu balik ke HomeActivity
        MaterialAlertDialogBuilder(ctx, R.style.MyAlertDialogTheme)
            .setTitle("Simpan Berhasil")
            .setMessage("Data sesi telah disimpan.")
            .setPositiveButton("OK") { _, _ ->
                stopStreamAndExit() // fungsi kamu untuk tutup stream & pindah ke HomeActivity
            }
            .show()
    }

    private fun showSaveConfirmDialog() {
        val dialogConfirm = MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
            .setTitle("Konfirmasi")
            .setMessage("Pastikan pekerjaan telah selesai, sebelum menyimpan media")
            .setNegativeButton("Kembali", null)
            .setPositiveButton("Simpan") { _, _ ->
                // Setelah user menekan Simpan → tampilkan progress & mulai proses simpan
                showSavingProgressAndExecute()
            }
            .create()
        // ubah background → putih + rounded
        dialogConfirm.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom)

        dialogConfirm.show()
    }

    private fun showSavingProgressAndExecute() {
        // 1) Tampilkan dialog progress (JANGAN dipakai ulang untuk sukses)
        val progressView = layoutInflater.inflate(R.layout.dialog_progress_saving, null)
        val progressDialog =
            MaterialAlertDialogBuilder(requireContext(), R.style.MyAlertDialogTheme)
                .setView(progressView)
                .setCancelable(false)
                .create()
        // ubah background jadi drawable custom (rounded + warna)
        progressDialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom)
        progressDialog.show()

        // Optional: animasi dummy determinate
        val bar = progressView.findViewById<LinearProgressIndicator>(R.id.progress)
        bar.isIndeterminate = false
        bar.max = 100
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            repeat(15) {
                bar.setProgressCompat((it + 1) * (100 / 10), true)
                delay(50)
            }
        }

        // 2) Kerjaan simpan/hapus di background
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            if (selectionMode) {
                val all = thumbsAdapter.currentList.map { it.file }
                val keep = thumbsAdapter.getSelectedItems().toSet()
                val toDelete = all.filterNot { keep.contains(it) }
                toDelete.forEach { runCatching { it.delete() } }
            }
            // (tidak publish ke galeri; semua tetap di private folder)

            // Simulasikan sedikit waktu proses (opsional)
            delay(1000)

            withContext(Dispatchers.Main) {
                // 3) Tutup progress DULU
                if (progressDialog.isShowing) progressDialog.dismiss()

                if (selectionMode) {
                    exitSelectionMode()
                    refreshThumbs()
                }

                // 4) Tampilkan dialog sukses BARU (dialog berbeda)
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

        // Action "Lihat" (opsi): mis. buka MediaPagerActivity dengan media tersisa
        v.findViewById<TextView>(R.id.tvAction)?.setOnClickListener {
            dialog.dismiss()
            // Kalau kamu ingin langsung kembali ke Home tanpa "Lihat", hapus blok ini
            // dan langsung panggil stopStreamAndExit() saja.

            // Contoh: langsung kembali Home sesuai requirement
            stopStreamAndExit()
        }
        // Jika lebih sesuai flow kamu: langsung kembali Home tanpa menunggu klik
        // dialog.setOnShowListener {
        //     v.postDelayed({ dialog.dismiss(); stopStreamAndExit() }, 800)
        // }
    }

    // ====== panggil ini saat klik menu "Informasi Pasien" ======
    private fun showPatientInfoBottomSheet() {
        val ctx = requireContext()

        // Pakai tema aman-umum; kalau project-mu sudah Material3 penuh boleh ganti ke:
        // BottomSheetDialog(ctx, com.google.android.material.R.style.ThemeOverlay_Material3_BottomSheetDialog)
        val dialog = BottomSheetDialog(
            ctx,
            com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog
        )
        val v = layoutInflater.inflate(R.layout.bs_patient_info, null)
        dialog.setContentView(v)

        // ---- Rounded top programatik (jalan di minSdk 25) ----
        dialog.setOnShowListener {
            val sheet =
                dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                val radius =
                    resources.getDimension(R.dimen.bs_top_radius) // mis. 16dp (lihat dimens di bawah)
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

        // ---- Bind views ----
        val btnClose = v.findViewById<ImageButton>(R.id.btnClose)
        val tvTanggal = v.findViewById<TextView>(R.id.tvTanggal)
        val tvNama = v.findViewById<TextView>(R.id.tvNama)
        val tvNik = v.findViewById<TextView>(R.id.tvNik)
        val tvDob = v.findViewById<TextView>(R.id.tvDob)
        val tvNrm = v.findViewById<TextView>(R.id.tvNrm)

        // ---- Isi data AKTUAL dari VideoFragment ----
        // Tanggal & waktu saat ini (WIB) → “17 Juli 2025, 10:12”
        val sdfNow =
            java.text.SimpleDateFormat("d MMMM yyyy, HH:mm", java.util.Locale("id", "ID")).apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta")
            }
        tvTanggal.text = sdfNow.format(java.util.Date())

        // Nama + usia (opsional taruh di nama)
        val namaSafe = patientNama.ifBlank { "-" }
        tvNama.text = if (patientAge > 0) "$namaSafe ($patientRs)" else namaSafe

        // NIK
        tvNik.text = patientNik.ifBlank { "-" }

        // DOB (dd/MM/yyyy) dari patientDobUtc
        tvDob.text = if (patientDobUtc > 0L) {
            val sdfDob = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("id", "ID"))
            sdfDob.format(java.util.Date(patientDobUtc))
        } else "-"

        // NRM
        tvNrm.text = patientNrm.ifBlank { "Tidak ada nomor rekam medis" }

        // Tombol X
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun toggleSelectionMode(menuItem: android.view.MenuItem) {
        selectionMode = !selectionMode
        thumbsAdapter.setSelectionMode(selectionMode)

        if (selectionMode) {
            menuItem.title = "Selesai"
            // ubah nav icon menjadi close? opsional
            // binding.topAppBar.navigationIcon = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_close_24)
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

            // TODO: lakukan aksi massal (hapus/share/kirim ke pager, dll)
            // contoh share banyak:
            // shareMultiple(selected)
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
        val dir = videosDir ?: sessionDir
        if (dir == null) {
            Toast.makeText(requireContext(), "Folder sesi belum siap", Toast.LENGTH_SHORT).show()
            return
        }

        // Nama file: vid_yyyyMMdd_HHmmss.mp4
        val out = File(dir, "vid_${StorageUtils.timestampWIB()}.mp4")
        videoOutputFile = out

        // Tentukan ukuran: pakai resolusi stream jika sudah ada; kalau belum, fallback
        val width =
            if (ivVideoImageResolution.first > 0) ivVideoImageResolution.first else lastFrameSize.first.coerceAtLeast(
                640
            )
        val height =
            if (ivVideoImageResolution.second > 0) ivVideoImageResolution.second else lastFrameSize.second.coerceAtLeast(
                360
            )

        try {
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
//            binding.btnRecordVideo.setBackgroundColor(Color.RED)
//            Toast.makeText(requireContext(), "Rekaman dimulai", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            record.set(false)
            Toast.makeText(requireContext(), "Gagal mulai rekam: ${e.message}", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun stopVideoRecording() {
        if (!record.get()) return

        var isSuccess = false
        try {
            // Coba stop recorder
            recorder.stop()
            isSuccess = true
        } catch (e: Exception) {
            Log.e(TAG, "Gagal stop recording: ${e.message}")
            // Jika gagal stop (misal durasi terlalu pendek), file biasanya korup
        }

        record.set(false)
        hudHandler.removeCallbacks(hudTick)
        binding.recordHud.visibility = View.GONE
        binding.btnRecordVideo.setImageResource(R.drawable.majesticons_video) // Ganti icon balik

        val file = videoOutputFile
        videoOutputFile = null

        if (file != null) {
            if (isSuccess) {
                // HANYA jika sukses stop, refresh galeri
                Toast.makeText(requireContext(), "Media Tersimpan", Toast.LENGTH_SHORT).show()
                // Beri jeda agar file benar-benar tertulis
                binding.rvThumbs.postDelayed({ refreshThumbs() }, 500)
            } else {
                // Jika gagal (MediaMuxer error), HAPUS file sampah agar tidak bikin crash saat dibuka
                file.delete()
                Toast.makeText(requireContext(), "Gagal menyimpan video (Terlalu singkat?)", Toast.LENGTH_SHORT).show()
                refreshThumbs()
            }
        }
    }
    private fun startRtspStream() {
        val originalUriString = liveViewModel.rtspRequest.value ?: ""
        if (originalUriString.isBlank()) return


        // --- PERBAIKAN POIN 6: STABILISASI GAMBAR (ANTI PECAH) ---
        // Mencoba memaksa mode UDP melalui URL parameter (workaround umum untuk RTSP)
        // Jika kamera mendukung, ini akan mengurangi artifact/gambar pecah.
        val finalUriString = if (!originalUriString.contains("?")) {
            "$originalUriString?transport=udp" // Ganti ke UDP untuk Low Latency
        } else {
            originalUriString
        }
        val uri = Uri.parse(finalUriString)

        binding.ivVideoImage.apply {
            init(
                uri,
                liveViewModel.rtspUsername.value,
                liveViewModel.rtspPassword.value,
                "cervexa-client-android"
            )

            onRtspImageBitmapListener = object : RtspImageView.RtspImageBitmapListener {
                override fun onRtspImageBitmapObtained(bitmap: Bitmap) {
                    // [OPTIMASI] Cek dulu apakah perlu diproses
                    val isRecording = record.get()
                    val isSnapshot = ss.get()

                    // Jika TIDAK rekam & TIDAK snapshot, biarkan saja (jangan bebani CPU)
                    if (!isRecording && !isSnapshot) return

                    // Baru proses bitmap jika diperlukan
                    val bmWithOverlay = processTextToBitmapSafe(bitmap)

                    if (isRecording) recorder.submitBitmap(bmWithOverlay)

                    if (isSnapshot) {
                        // ... (kode simpan snapshot kamu) ...
                        val dir = snapshotsDir ?: sessionDir
                        if (dir != null) {
                            // ... logika simpan ...
                        }
                        ss.set(false)
                    }
                }
            }
            start(
                requestVideo = true,
                requestAudio = false,
                requestApplication = false
            )
        }
//        startStatistics()
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

    /**
     * Gambar overlay (timestamp + identitas) di atas bitmap. Aman jika bitmap immutable.
     */
    private fun processTextToBitmapSafe(src: Bitmap): Bitmap {
        val bitmap = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)

        // Timestamp string
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
            textSize = 48f
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            // Membuat teks Tebal (Bold)
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                android.graphics.Typeface.BOLD
            )
            // Menambah bayangan hitam agar kontras dengan background apapun
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        // Background box transparan (opsional, bisa dibuat lebih gelap jika perlu)
        val paintBox = Paint().apply {
            color = Color.argb(
                100,
                0,
                0,
                0
            ) // Sedikit lebih transparan (100) agar tidak terlalu menutupi
            style = Paint.Style.FILL
        }

        // --- Menggambar ---

        // 1. Timestamp (Pojok Kanan Bawah)
        // Hitung lebar teks agar posisi kanan rapi
        val timeWidth = paintTextWhite.measureText(formatted)
        val margin = 30f

        // Tutup timestamp video bawaan (pojok kanan bawah)
        canvas.drawRect(
            bitmap.width.toFloat() - timeWidth - (margin * 2),
            bitmap.height.toFloat() - 70f,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            paintBox
        )

        // Tulis timestamp kita (pojok kanan bawah)
        canvas.drawText(
            formatted,
            bitmap.width.toFloat() - timeWidth - margin,
            bitmap.height.toFloat() - 20f,
            paintTextWhite
        )

        // 2. Identitas Pasien (Pojok Kiri Bawah)
        // Tampilkan RS dan NRM/Nama
        val textIdentitas = if (patientNrm.isEmpty()) "$patientRs" else "$patientRs / $patientNrm"

        // Background box kiri
        canvas.drawRect(0f, bitmap.height.toFloat() - 70f, 750f, bitmap.height.toFloat(), paintBox)

        canvas.drawText(textIdentitas, 20f, bitmap.height.toFloat() - 20f, paintTextWhite)

//        if(patientNrm.isEmpty()){
//            canvas.drawText("-", 20f, bitmap.height.toFloat() - 20f, paintTextWhite)
//        }else{
//            canvas.drawText(patientNrm, 20f, bitmap.height.toFloat() - 20f, paintTextWhite)
//            canvas.drawText("-", 20f, bitmap.height.toFloat() - 20f, paintTextWhite)
//        }

        return bitmap
    }

    /**
     * Simpan satu frame ke folder sesi (pakai StorageUtils).
     */
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
                    // pivot di titik pinch
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
                    // reset
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
                    // geser hanya jika sudah membesar
                    if (scale > 1f) {
                        translationX -= dx
                        translationY -= dy
                        // clamp sederhana: jangan terlalu jauh
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
            // konsumsi event jika sedang zooming (biar tak mengganggu tombol)
            scaleDetector.isInProgress || scale > 1f
        }
    }

    /**
     * (Opsional) auto save tiap interval agar tidak spam.
     */
    private fun autoSaveEveryInterval(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastAutoSaveAtMs >= minAutoSaveIntervalMs) {
            lastAutoSaveAtMs = now
            saveFrame(bitmap)
        }
    }

    companion object {
        private val TAG: String = VideoFragment::class.java.simpleName
        private const val DEBUG = true
    }
}
