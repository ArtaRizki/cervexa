package com.idn.kmed.cervexa.live

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.utils.DeviceTypeDetector

class VideoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)

        // Log device information untuk debugging
        DeviceTypeDetector.logDeviceInfo(this)

        // Deteksi device type
        val isTvDevice = DeviceTypeDetector.isTvDevice(this)

        Log.d(TAG, "Device Type: ${if (isTvDevice) "TV/STB" else "Smartphone/Tablet"}")

        // Ambil data dari intent
        val sessionDirPath = intent.getStringExtra("sessionDirPath")
        val patientNama = intent.getStringExtra("patient_nama")
        val patientNik = intent.getStringExtra("patient_nik")
        val patientRs = intent.getStringExtra("patient_rs")
        val patientNrm = intent.getStringExtra("patient_nrm")
        val patientDobUtc = intent.getLongExtra("patient_dob_utc", -1L)

        // Buat Bundle untuk fragment
        val bundle = Bundle().apply {
            putString("sessionDirPath", sessionDirPath)
            putString("patient_nama", patientNama)
            putString("patient_nik", patientNik)
            putString("patient_rs", patientRs)
            putString("patient_nrm", patientNrm)
            putLong("patient_dob_utc", patientDobUtc)
        }

        // Pilih fragment berdasarkan device type
        val fragment: Fragment = if (isTvDevice) {
            Log.i(TAG, "Loading VideoFragmentTv (VLC with auto-crop)")
            VideoFragmentTv().apply { arguments = bundle }
        } else {
            Log.i(TAG, "Loading VideoFragmentMobile (RTSP)")
//            VideoFragmentTv().apply { arguments = bundle }
            VideoFragmentMobile().apply { arguments = bundle }
        }

        // Load fragment jika belum ada
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
        }
    }

    companion object {
        private const val TAG = "VideoActivity"
    }
}