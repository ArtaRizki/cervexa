package com.idn.kmed.cervexa

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.idn.kmed.cervexa.live.VideoFragment
import com.idn.kmed.cervexa.utils.PatientUtils
import com.idn.kmed.cervexa.utils.StorageUtils

class VideoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)

        // ==== Ambil data pasien dari RegistrationActivity ====
        val nama = intent.getStringExtra("patient_nama").orEmpty()
        val nik  = intent.getStringExtra("patient_nik").orEmpty()
        val rs  = intent.getStringExtra("patient_rs").orEmpty()
        val nrm  = intent.getStringExtra("patient_nrm").orEmpty()
        val dobUtc = intent.getLongExtra("patient_dob_utc", -1L)

        // ==== Siapkan folder sesi: <Pictures>/Scans/yyyy-MM-dd/NIK_NAMA_USIA ====
        val dateFolder = StorageUtils.todayDateFolderWIB()
        val patientFolder = PatientUtils.buildFolderName(nik, nama, dobUtc)
        val sessionDir = StorageUtils.ensureSessionDir(this, dateFolder, patientFolder)

        // (opsional) tulis metadata
        val meta = """
            {
              "nama": "${nama.replace("\"","\\\"")}",
              "nik": "$nik",
              "nrm": "${nrm.replace("\"","\\\"")}",
              "rs": "${rs.replace("\"","\\\"")}",
              "dob_utc": $dobUtc,
              "age": ${PatientUtils.calculateAge(dobUtc)},
              "date_folder": "$dateFolder",
              "patient_folder": "$patientFolder",
              "created_at": "${StorageUtils.timestampWIB()}"
            }
        """.trimIndent()
        runCatching { StorageUtils.writeSessionMetadata(sessionDir, meta) }

//        Toast.makeText(this, "Folder sesi: ${sessionDir.name}", Toast.LENGTH_SHORT).show()

        if (savedInstanceState == null) {
            val fragment = VideoFragment().apply {
                arguments = Bundle().apply {
                    putString("sessionDirPath", sessionDir.absolutePath)
                    putString("patient_nama", nama)
                    putString("patient_nik", nik)
                    putString("patient_rs", rs)
                    putString("patient_nrm", nrm)
                    putLong("patient_dob_utc", dobUtc)
                }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
        }
    }
}
