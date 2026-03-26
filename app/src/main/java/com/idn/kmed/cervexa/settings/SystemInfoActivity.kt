package com.idn.kmed.cervexa.settings

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import android.widget.TextView
import androidx.core.content.edit
import androidx.lifecycle.ViewModelProvider
import com.alexvas.rtsp.codec.VideoDecodeThread
import com.idn.kmed.cervexa.settings.SettingsActivity.Companion.KEY_CAMERA_ROTATION_DEG
import com.idn.kmed.cervexa.settings.SettingsActivity.Companion.KEY_USE_HW_DECODER
import com.idn.kmed.cervexa.live.LiveViewModel
import com.idn.kmed.cervexa.live.VideoFragmentTv
import com.idn.kmed.cervexa.utils.StorageUtils
import com.alexvas.rtsp.widget.RtspImageView
import com.alexvas.rtsp.widget.RtspProcessor.Statistics
import com.alexvas.rtsp.widget.RtspStatusListener
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.gallery.MediaPagerActivity
import com.idn.kmed.cervexa.gallery.MediaPagerActivityLand
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class SystemInfoActivity : AppCompatActivity() {
    private lateinit var imgRtspImageView: RtspImageView
    private lateinit var liveViewModel: LiveViewModel
    private var statisticsTimer: Timer? = null
    private lateinit var tvDecoder: TextView
    private lateinit var tvLatency: TextView
    private lateinit var tvResolution: TextView
    private lateinit var tvLastTest: TextView
    private lateinit var btnSeeResult: View
    private var ivVideoImageResolution = Pair(0, 0)
    private var ss: AtomicBoolean = AtomicBoolean(false)
    private var canSS: AtomicBoolean = AtomicBoolean(false)
    private val prefs by lazy {
        getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
    }
    private val main = Handler(Looper.getMainLooper())
    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_information_system)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        tvDecoder = findViewById<TextView>(R.id.tvDecoder)
        tvLatency = findViewById<TextView>(R.id.tvLatency)
        tvResolution = findViewById<TextView>(R.id.tvResolution)
        tvLastTest = findViewById<TextView>(R.id.tvLastTest)
        btnSeeResult = findViewById<TextView>(R.id.btnSeeResult)
        val btnRetest = findViewById<MaterialButton>(R.id.btnRetest)

        this.imgRtspImageView = findViewById<RtspImageView>(R.id.videoImage)
        this.imgRtspImageView.setStatusListener(rtspStatusImageListener)

        // Setting Rotation and Encoder from sharePref
        imgRtspImageView.videoRotation = prefs.getInt(KEY_CAMERA_ROTATION_DEG, 0)
        imgRtspImageView.videoDecoderType = if (prefs.getBoolean(KEY_USE_HW_DECODER, false)) VideoDecodeThread.DecoderType.HARDWARE else VideoDecodeThread.DecoderType.SOFTWARE

        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        liveViewModel = ViewModelProvider(this)[LiveViewModel::class.java]

        btnSeeResult.setOnClickListener {
            val base = this.getExternalFilesDir("System")
            val dir = File(base, "Testing/${getPrefLatestImnageName()}")
            openPreview(dir,false)
        }

        tvDecoder.text = getPrefLatestDecodeer()
        tvLatency.text = getPrefLatestLatency()
        tvResolution.text = getPrefLatestResolution()
        tvLastTest.text = getPrefLatestDate()


        btnRetest.setOnClickListener {
            val started: Boolean = imgRtspImageView.isStarted()
            if(!started){
                btnRetest.text = "Stop Tes"
                startRtspStream()
                ss.set(true)
                startStatistics()
            }else{
                saveLatestTest()
                btnRetest.text = "Tes Ulang"
                stopRtspStream()
                stopStatistics()
                tvLastTest.text = getPrefLatestDate()
            }

        }
    }

    private fun openPreview(file: File, isVideo: Boolean) {
        val paths = arrayListOf(file.absolutePath)
        val types = arrayListOf(if (isVideo) "VIDEO" else "IMAGE")

        val target = if (isLandscape())
            MediaPagerActivityLand::class.java
        else
            MediaPagerActivity::class.java

        startActivity(
            Intent(this, target).apply {
                putStringArrayListExtra("paths", paths)
                putStringArrayListExtra("types", types)
                putExtra("index", 0)
                putExtra("forceLandscape", isLandscape()) // <— penting
            }
        )
    }

    private fun getPrefLatestDate() : String{
        val lastes_test: String? = prefs.getString("date_test", "-")
        return lastes_test.toString()
    }

    private fun getPrefLatestDecodeer() : String {
        val lastes_test: String? = prefs.getString("decoder_test", "-")
        return lastes_test.toString()
    }

    private fun getPrefLatestLatency() : String {
        val lastes_test: String? = prefs.getString("latency_test", "-")
        return lastes_test.toString()
    }

    private fun getPrefLatestResolution() : String {
        val lastes_test: String? = prefs.getString("resolution_test", "-")
        return lastes_test.toString()
    }

    private fun getPrefLatestImnageName() : String {
        val lastes_test: String? = prefs.getString("result_image_name_test", "-")
        return lastes_test.toString()
    }

    private fun saveLatestTest(){
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd, HH:mm")
        val current = LocalDateTime.now().format(formatter)
        prefs.edit { putString("date_test", current) }
        prefs.edit { putString("decoder_test", tvDecoder.text.toString())}
        prefs.edit { putString("latency_test", tvLatency.text.toString())}
        prefs.edit { putString("resolution_test", tvResolution.text.toString())}
    }

    private fun stopRtspStream(){
        if (imgRtspImageView.isStarted()) {
            imgRtspImageView.stop()
        }
    }

    private fun stopStatistics() {
        statisticsTimer?.apply {
            cancel()
        }
        statisticsTimer = null
    }

    private val rtspStatusImageListener = object : RtspStatusListener {

        override fun onRtspFrameSizeChanged(width: Int, height: Int) {
            ivVideoImageResolution = Pair(width, height)
        }

        override fun onRtspFirstFrameRendered() {
            canSS.set(true)
        }
    }


    private fun startRtspStream() {
        val uri = Uri.parse(liveViewModel.rtspRequest.value)
        imgRtspImageView.apply {
            init(uri, liveViewModel.rtspUsername.value, liveViewModel.rtspPassword.value, "cervexa-client-android")

            onRtspImageBitmapListener = object : RtspImageView.RtspImageBitmapListener {
                override fun onRtspImageBitmapObtained(bitmap: Bitmap) {
                    if(canSS.get()){
                        if(ss.get()){
                            saveFrame(bitmap)
                            ss.set(false)
                        }
                    }
                }
            }
            start(
                requestVideo = true,
                requestAudio = false,
                requestApplication = false
            )
        }
    }

    /**
     * Simpan satu frame ke folder sesi (pakai StorageUtils).
     */
    private fun saveFrame(bitmap: Bitmap) {
        val base = this.getExternalFilesDir("System")
        val dir = File(base, "Testing").apply { if (!exists()) mkdirs() }

        runCatching {
            StorageUtils.saveJpeg(dir, bitmap)
        }.onSuccess { saved ->
            prefs.edit { putString("result_image_name_test", saved.name)}
//            Toast.makeText(this, "Tersimpan: ${saved.name}", Toast.LENGTH_SHORT).show()
        }.onFailure {
//            Toast.makeText(this, "Gagal simpan: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startStatistics() {
        if (statisticsTimer == null) {
            val task: TimerTask = object : TimerTask() {
                override fun run() {
                    main.post {
                        if (imgRtspImageView.isStarted()) {
                            val statistics: Statistics = imgRtspImageView.statistics
                            tvDecoder.text = "${statistics.videoDecoderType.toString().lowercase()} ${if (statistics.videoDecoderName.isNullOrEmpty()) "" else "(${statistics.videoDecoderName})"}"
                            tvLatency.text = "${statistics.videoDecoderLatencyMsec} ms"
                            tvResolution.text = "${ivVideoImageResolution.first}x${ivVideoImageResolution.second}"
//                        binding.tvStatistics2?.post { binding.tvStatistics2?.text = text }
                        }
                    }
                }
            }
            statisticsTimer = Timer("Statistics").apply { schedule(task, 0, 1000) }
        }
    }

    companion object {
        private val TAG: String = VideoFragmentTv::class.java.simpleName
        private const val DEBUG = true
    }
}