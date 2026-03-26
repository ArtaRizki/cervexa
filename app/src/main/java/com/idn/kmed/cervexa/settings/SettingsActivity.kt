package com.idn.kmed.cervexa.settings

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.idn.kmed.cervexa.R

class SettingsActivity : AppCompatActivity() {

    private lateinit var swHw: MaterialSwitch
    private lateinit var tvRotVal: TextView
    private lateinit var rowRot: LinearLayout
    private var rot: Int = 0

    private val prefs by lazy {
        getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Toolbar
        val top = findViewById<MaterialToolbar>(R.id.topAppBar)
        top.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        swHw = findViewById(R.id.switchHwDecoder)
        tvRotVal = findViewById(R.id.tvRotationValue)
        rowRot = findViewById(R.id.rowRotation)

        // Load nilai awal
        val useHw = prefs.getBoolean(KEY_USE_HW_DECODER, true)
        rot = prefs.getInt(KEY_CAMERA_ROTATION_DEG, 0)
        swHw.isChecked = useHw
        tvRotVal.text = "${rot}°"

        // Simpan saat di-toggle
        swHw.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_USE_HW_DECODER, checked).apply()
        }

        // Pilih rotasi (0/90/180/270)
        rowRot.setOnClickListener { showRotationPicker(rot) }
    }

    private fun showRotationPicker(current: Int) {
        val options = arrayOf("0°", "90°", "180°", "270°")
        val values = intArrayOf(0, 90, 180, 270)
        val checked = values.indexOf(current).coerceAtLeast(0)

        val rotateDialog = MaterialAlertDialogBuilder(this, R.style.MyAlertDialogTheme)
            .setTitle("Pilih Rotasi Kamera")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val v = values[which]
                prefs.edit().putInt(KEY_CAMERA_ROTATION_DEG, v).apply()
                rot = v
                tvRotVal.text = "${v}°"
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .create()
        rotateDialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_custom)
        rotateDialog.show()
    }

    companion object {
        const val KEY_USE_HW_DECODER = "use_hw_decoder"
        const val KEY_CAMERA_ROTATION_DEG = "camera_rotation_deg"
    }
}