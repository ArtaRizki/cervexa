package com.idn.kmed.cervexa.live

import android.annotation.SuppressLint
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
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
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

// Hapus implementasi IVLCVout.Callback di level class agar tidak error
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
    private var textureView: TextureView? = null // Wajib pakai TextureView di XML

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

        // Pastikan di XML ID-nya textureView
        textureView = binding.textureView

        // Setup Gesture
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
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
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

        // Button Listeners
        binding.bnStartStopImage?.setOnClickListener {
            if (mediaPlayer?.isPlaying == true) stopStreamAndExit() else startVlcStream()
        }

        binding.btnEnterLandscape?.setOnClickListener {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        binding.btnSnapshot.setOnClickListener { takeSnapshot() }

        binding.btnRecordVideo.setOnClickListener {
            if (record.get()) stopVideoRecording() else startVideoRecording()
        }

        // Back Handler (Show Dialog)
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

        // Setup RecyclerView Thumbs
        binding.rvThumbs.apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 4)
            thumbsAdapter = ThumbAdapter { _, position ->
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

        return binding.root
    }

    private fun applyZoomMatrix() {
        textureView?.apply {
            pivotX = focusX
            pivotY = focusY
            scaleX = currentScale
            scaleY = currentScale
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusBarColor()
        liveViewModel.loadParams(requireContext())
        if (mediaPlayer == null || mediaPlayer?.isPlaying == false) startVlcStream()
    }

    override fun onPause() {
        super.onPause()
        updateStatusBarColor()
        liveViewModel.saveParams(requireContext())
        if (record.get()) stopVideoRecording()
        stopVlcStream()
    }

    private fun updateStatusBarColor() {
        val color = if (isLandscape()) R.color.colorBlack else R.color.colorButton
        requireActivity().window.statusBarColor = ContextCompat.getColor(requireContext(), color)
    }

    // ==========================================
    // VLC STREAMING LOGIC (Fixed for Mi Stick)
    // ==========================================

    private fun startVlcStream() {
        binding.pbLoadingImage.visibility = View.VISIBLE
        binding.vShutterImage.visibility = View.VISIBLE

        try {
            val options = ArrayList<String>().apply {
                // 1. Kunci koneksi lancar
                add("--rtsp-tcp")
                add("--network-caching=250") // Buffer 250ms (rendah delay)
                add("--drop-late-frames")

                // 2. Kunci Performa Mi Stick (Hardware Decoder)
                add("--avcodec-hw=any")

                // 3. Kunci ANTI-BLANK SCREEN (Wajib untuk TextureView di Mi Stick)
                add("--no-mediacodec-dr") // Matikan Direct Rendering
                add("--no-omxil-dr")
            }

            libVlc = LibVLC(requireContext(), options)
            mediaPlayer = MediaPlayer(libVlc)

            // Setup Output ke TextureView
            val vout = mediaPlayer!!.vlcVout
            vout.setVideoView(textureView)

            // Callback untuk Listener Layout (Agar video tidak gepeng)
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

            // Setup URL
            val rawUrl = liveViewModel.rtspRequest.value ?: ""
            val user = liveViewModel.rtspUsername.value ?: ""
            val pass = liveViewModel.rtspPassword.value ?: ""
            val finalUrl = if (user.isNotEmpty() && !rawUrl.contains("//$user")) {
                rawUrl.replace("rtsp://", "rtsp://$user:$pass@")
            } else {
                rawUrl
            }

            val media = Media(libVlc, Uri.parse(finalUrl))
            media.setHWDecoderEnabled(true, false) // Paksa HW decode di level media juga
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
        requireActivity().finish()
    }

    // ==========================================
    // RECORDING LOGIC (Frame Grabber)
    // ==========================================

    private fun startVideoRecording() {
        val dir = videosDir ?: sessionDir ?: return
        val out = File(dir, "vid_${StorageUtils.timestampWIB()}.mp4")
        videoOutputFile = out

        // Rekam di 720p agar ringan
        val recWidth = 1280
        val recHeight = 720

        try {
            recorder = RealtimeBitmapEncoder(requireContext(), recWidth, recHeight, out)
            recorder.start()
            record.set(true)

            recordStartElapsedMs = android.os.SystemClock.elapsedRealtime()
            binding.recordHud.visibility = View.VISIBLE
            binding.tvRecordTimer.text = "00:00:00"
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
        videoOutputFile = null
        binding.rvThumbs.postDelayed({ refreshThumbs() }, 300)
        Toast.makeText(requireContext(), "Video Tersimpan", Toast.LENGTH_SHORT).show()
    }

    private fun startFrameGrabber(width: Int, height: Int) {
        recordingJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            while (record.get() && isActive) {
                val start = System.currentTimeMillis()
                // Ambil bitmap dari TextureView (Ringan karena sudah di-decode HW)
                val bmp = withContext(Dispatchers.Main) { textureView?.getBitmap(width, height) }

                if (bmp != null) {
                    recorder.submitBitmap(processTextToBitmapSafe(bmp))
                }
                // Target ~20fps
                val elapsed = System.currentTimeMillis() - start
                delay((50 - elapsed).coerceAtLeast(0))
            }
        }
    }

    private fun takeSnapshot() {
        val dir = snapshotsDir ?: sessionDir ?: return
        val bmp = textureView?.bitmap // Ambil resolusi asli layar
        if (bmp != null) {
            runCatching {
                StorageUtils.saveJpegWithPrefix(dir, processTextToBitmapSafe(bmp), prefix = "ss")
            }.onSuccess {
                Toast.makeText(requireContext(), "Snapshot Tersimpan", Toast.LENGTH_SHORT).show()
                refreshThumbs()
            }
        }
    }

    // ... (Sisa fungsi Overlay, Dialog, HUD sama seperti sebelumnya) ...
    // Copy-paste sisa fungsi processTextToBitmapSafe, setKeepScreenOn, hudTick, dialogs, dll 
    // dari kode yang saya berikan sebelumnya.

    private fun processTextToBitmapSafe(src: Bitmap): Bitmap {
        val bitmap = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val formatted = if (android.os.Build.VERSION.SDK_INT >= 26)
            ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
        else SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())

        val canvas = Canvas(bitmap)
        val paintText = Paint().apply { color = Color.WHITE; textSize = 36f; isAntiAlias = true }
        val paintBox = Paint().apply { color = Color.argb(128, 0, 0, 0); style = Paint.Style.FILL }

        canvas.drawRect(
            bitmap.width.toFloat() - 360f,
            bitmap.height.toFloat() - 60f,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            Paint().apply { color = "#3F3F3F".toColorInt() })
        canvas.drawText(
            formatted,
            bitmap.width.toFloat() - 350f,
            bitmap.height.toFloat() - 20f,
            paintText
        )

        canvas.drawRect(0f, bitmap.height.toFloat() - 65f, 650f, bitmap.height.toFloat(), paintBox)
        val infoText = if (patientNrm.isEmpty()) "$patientRs" else "$patientRs/$patientNrm"
        canvas.drawText(infoText, 20f, bitmap.height.toFloat() - 20f, paintText)
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

    // ==== DIALOGS & SELECTION MODE ====
    // ... Copy fungsi showSaveConfirmDialog, showExitConfirmDialog, enterSelectionMode, dll dari kode sebelumnya ...

    private fun showSaveConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Konfirmasi")
            .setMessage("Simpan media dan tutup sesi?")
            .setPositiveButton("Simpan") { _, _ -> showSavingProgressAndExecute() }
            .setNegativeButton("Batal", null)
            .show()
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
        d.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom)
        d.show()
        v.findViewById<TextView>(R.id.tvAction)
            ?.setOnClickListener { d.dismiss(); stopStreamAndExit() }
    }

    // ... Sertakan fungsi enterSelectionMode, confirmDeleteSelected, deleteFiles, exitSelectionMode, showPatientInfoBottomSheet, refreshThumbs dari kode sebelumnya ...
    private fun enterSelectionMode() {
        selectionMode = true
        binding.topAppBar.menu.clear(); binding.topAppBar.inflateMenu(R.menu.menu_video_fragment_select)
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
                refreshThumbs(); exitSelectionMode()
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        binding.topAppBar.menu.clear(); binding.topAppBar.inflateMenu(R.menu.menu_video_fragment)
        binding.topAppBar.title = "Cervexa Colposcope"
        binding.topAppBar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_info_pasien) showPatientInfoBottomSheet()
            else if (it.itemId == R.id.action_pilih) enterSelectionMode()
            true
        }
        thumbsAdapter.setSelectionMode(false)
    }

    private fun showPatientInfoBottomSheet() {
        val dialog = BottomSheetDialog(requireContext()); dialog.setContentView(
            layoutInflater.inflate(
                R.layout.bs_patient_info,
                null
            )
        )
        dialog.show()
    }

    companion object {
        private val TAG: String = VideoFragment::class.java.simpleName
        private const val DEBUG = true
    }
}