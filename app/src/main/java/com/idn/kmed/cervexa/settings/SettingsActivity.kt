package com.idn.kmed.cervexa.settings

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.idn.kmed.cervexa.R

import android.graphics.Color
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.idn.kmed.cervexa.utils.PrintBridgeClient
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var swHw: MaterialSwitch
    private lateinit var tvRotVal: TextView
    private lateinit var rowRot: LinearLayout
    private var rot: Int = 0

    // Print Bridge UI
    private lateinit var swBridge: MaterialSwitch
    private lateinit var layoutBridgeConfig: LinearLayout
    private lateinit var etBridgeHost: TextInputEditText
    private lateinit var btnTestBridge: MaterialButton
    private lateinit var pbTestingBridge: ProgressBar
    private lateinit var tvBridgeStatus: TextView

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

        // Load nilai awal Hardware & Rotasi
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

        // Setup Print Bridge UI
        setupPrintBridgeSection()
    }

    private fun setupPrintBridgeSection() {
        swBridge = findViewById(R.id.switchPrintBridge)
        layoutBridgeConfig = findViewById(R.id.layoutBridgeConfig)
        etBridgeHost = findViewById(R.id.etBridgeHost)
        btnTestBridge = findViewById(R.id.btnTestBridge)
        pbTestingBridge = findViewById(R.id.pbTestingBridge)
        tvBridgeStatus = findViewById(R.id.tvBridgeStatus)

        val isBridgeEnabled = PrintBridgeClient.isBridgeEnabled(this)
        val savedHost = PrintBridgeClient.getBridgeHost(this)

        swBridge.isChecked = isBridgeEnabled
        layoutBridgeConfig.visibility = if (isBridgeEnabled) View.VISIBLE else View.GONE
        etBridgeHost.setText(savedHost)

        swBridge.setOnCheckedChangeListener { _, checked ->
            PrintBridgeClient.setBridgeEnabled(this, checked)
            layoutBridgeConfig.visibility = if (checked) View.VISIBLE else View.GONE
        }

        etBridgeHost.doAfterTextChanged { s ->
            val hostStr = s?.toString()?.trim().orEmpty()
            PrintBridgeClient.setBridgeHost(this, hostStr)
            tvBridgeStatus.text = ""
        }

        btnTestBridge.setOnClickListener {
            val inputHost = etBridgeHost.text?.toString()?.trim().orEmpty()
            if (inputHost.isEmpty()) {
                Toast.makeText(this, "Masukkan alamat IP Print Bridge terlebih dahulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            PrintBridgeClient.setBridgeHost(this, inputHost)
            btnTestBridge.isEnabled = false
            pbTestingBridge.visibility = View.VISIBLE
            tvBridgeStatus.text = "Menghubungi Print Bridge..."
            tvBridgeStatus.setTextColor(Color.parseColor("#6B7280"))

            lifecycleScope.launch {
                val result = PrintBridgeClient.checkStatus(this@SettingsActivity, inputHost)
                val transport = PrintBridgeClient.getActiveTransportName(this@SettingsActivity)
                pbTestingBridge.visibility = View.GONE
                btnTestBridge.isEnabled = true

                result.onSuccess { status ->
                    if (status.isReady) {
                        tvBridgeStatus.text = "✓ Terhubung [$transport]: ${status.defaultPrinter}"
                        tvBridgeStatus.setTextColor(Color.parseColor("#10B981"))
                    } else {
                        tvBridgeStatus.text = "⚠️ Terhubung [$transport], status printer belum siap"
                        tvBridgeStatus.setTextColor(Color.parseColor("#F59E0B"))
                    }
                }.onFailure { err ->
                    val extra = if (err.message?.contains("ENONET", ignoreCase = true) == true) " (Cek kabel LAN)" else ""
                    tvBridgeStatus.text = "✕ Gagal [$transport]: ${err.localizedMessage ?: err.message}$extra"
                    tvBridgeStatus.setTextColor(Color.parseColor("#EF4444"))
                }
            }
        }
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