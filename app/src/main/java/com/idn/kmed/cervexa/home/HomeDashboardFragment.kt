package com.idn.kmed.cervexa.home

import android.Manifest
import android.content.*
import android.content.Context.MODE_PRIVATE
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.appbar.MaterialToolbar
import com.idn.kmed.cervexa.patient.ConfirmPatientActivity
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.model.WifiViewModel
import com.idn.kmed.cervexa.utils.WifiCamConnection
import com.idn.kmed.cervexa.utils.WifiMonitor
import kotlinx.coroutines.launch

class HomeDashboardFragment : Fragment() {

    private lateinit var wifiViewModel: WifiViewModel

    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var imgIndicator: ImageView

    private val prefs by lazy {
        requireContext().getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
    }

    // Receiver opsional untuk mendeteksi event koneksi dari API Suggestion (Android 10+)
    private val postConnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION == intent.action
            ) {
                // Paksa refresh saat ada notifikasi koneksi sukses dari sistem
                forceRefreshWifi()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_home_dashboard, container, false)

        requireActivity()
            .findViewById<MaterialToolbar>(R.id.topAppBar)
            ?.title = "Cervexa"

        wifiViewModel = ViewModelProvider(requireActivity())[WifiViewModel::class.java]

        tvStatus = v.findViewById(R.id.statusConnect)
        btnConnect = v.findViewById(R.id.btn_connect)
        imgIndicator = v.findViewById(R.id.imgIndicator)

        btnConnect.setOnClickListener { handleStartClickHome() }

        // [TV OPTIMIZATION] Pastikan tombol bisa difokus remote/dpad
//        btnConnect.isFocusable = true
//        btnConnect.isFocusableInTouchMode = true

        // Observe ViewModel untuk update UI otomatis
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                wifiViewModel.statusFlow.collect { status ->
                    refreshUi(status)
                }
            }
        }

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Hubungkan WifiMonitor ke ViewModel
        WifiMonitor.setOnStatusChanged { status ->
            wifiViewModel.updateStatus(status)
        }

        // 2. [TV OPTIMIZATION] Navigasi D-Pad ke BottomNav
//        btnConnect.setOnKeyListener { _, keyCode, event ->
//            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
//                val bottomNav = requireActivity().findViewById<BottomNavigationView>(R.id.nav_view)
//                val menuView = bottomNav.getChildAt(0) as? ViewGroup
//                val homeIcon = menuView?.getChildAt(0)
//                if (homeIcon != null) {
//                    homeIcon.requestFocus()
//                    return@setOnKeyListener true
//                }
//            }
//            false
//        }
    }

    override fun onResume() {
        super.onResume()
        // [PENTING] Refresh status saat user kembali dari Settings Wi-Fi/Permission
        forceRefreshWifi()
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                requireContext().registerReceiver(
                    postConnReceiver,
                    IntentFilter(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION)
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { requireContext().unregisterReceiver(postConnReceiver) }
        }
    }

    // Menangani hasil request permission dari WifiMonitor (jika ada)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        WifiMonitor.handlePermissionResult(requestCode, grantResults, requireContext())
        WifiMonitor.refreshNow()
    }

    private fun forceRefreshWifi() {
        // Init ulang akan memicu pembacaan status terbaru secara instan
        WifiMonitor.init(requireContext()) { /* callback opsional */ }
    }

    private fun refreshUi(status: WifiMonitor.WifiStatus) {
        if (status.isCamera) {
            imgIndicator.setImageResource(R.drawable.img_device_active)
            tvStatus.text = if (!status.ssid.isNullOrBlank()) {
                "Terhubung (${status.ssid})"
            } else {
                "Terhubung"
            }
            btnConnect.text = "Mulai"
        } else {
            imgIndicator.setImageResource(R.drawable.img_device_inactive)
            tvStatus.text = when {
                !status.isWifiConnected -> "Koneksi Terputus"
                !status.ssid.isNullOrBlank() && status.ssid != "<unknown ssid>" ->
                    "Wi-Fi: ${status.ssid} (Bukan Kamera)"
                else -> "Wi-Fi Aktif (Belum Terhubung ke Kamera)"
            }
            btnConnect.text = "Hubungkan Kembali"
        }
    }

    private fun handleStartClickHome() {
        WifiCamConnection.ensureCameraReadyOrShowConnectFlow(
            fragment = this,
            scope = viewLifecycleOwner.lifecycleScope,
            proceedImmediatelyAfterAutoConnect = false,
            onReady = {
                try {
                    startActivity(Intent(requireContext(), ConfirmPatientActivity::class.java))
                } catch (e: Exception) {
                    Toast.makeText(
                        requireContext(),
                        "Gagal membuka halaman selanjutnya.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun bindProcessToCameraIfMatch() {
        if (WifiMonitor.statusFlow.value.isCamera) {
            WifiMonitor.ensureBoundToCameraNetwork(requireContext())
        }
    }
}