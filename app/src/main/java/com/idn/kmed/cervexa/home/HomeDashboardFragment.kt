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
    }

    private fun forceRefreshWifi() {
        // Init ulang akan memicu pembacaan status terbaru secara instan
        WifiMonitor.init(requireContext()) { /* callback opsional */ }
    }

    private fun refreshUi(status: WifiMonitor.WifiStatus) {
        if (status.isCamera) {
            imgIndicator.setImageResource(R.drawable.img_device_active)
            tvStatus.text = "Terhubung"
            btnConnect.text = "Mulai"
        } else {
            imgIndicator.setImageResource(R.drawable.img_device_inactive)
            // Tampilkan info detail untuk memudahkan debug user
            if (status.ssid.isNullOrBlank() || status.ssid == "<unknown ssid>") {
                tvStatus.text = "Koneksi Terputus"
            } else {
                tvStatus.text = "Wi-Fi: ${status.ssid} (Bukan Kamera)"
            }
            btnConnect.text = "Hubungkan Kembali"
        }
    }

    private fun handleStartClickHome() {
        val ctx = requireContext()

        // ========================================================================
        // 1. DIAGNOSA PERMISSION (Penyebab utama "SSID tidak terbaca")
        // ========================================================================
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+: Wajib NEARBY_WIFI_DEVICES
            if (ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    ctx,
                    "Izin 'Nearby Devices' diperlukan untuk deteksi Wi-Fi.",
                    Toast.LENGTH_LONG
                ).show()
                WifiMonitor.handlePermissionResult(
                    2201,
                    intArrayOf(),
                    ctx
                ) // Trigger request permission logic di WifiMonitor
                // Atau bisa direct requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 2201)
                return
            }
            if (ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    ctx,
                    "Izin Lokasi (Fine Location) diperlukan untuk membaca nama Wi-Fi.",
                    Toast.LENGTH_LONG
                ).show()
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2201)
                return
            }
        } else {
            // Android < 13: Wajib FINE LOCATION
            if (ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    ctx,
                    "Izin Lokasi (Fine Location) diperlukan untuk membaca nama Wi-Fi.",
                    Toast.LENGTH_LONG
                ).show()
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2201)
                return
            }
        }

        // ========================================================================
        // 2. CEK STATUS KONEKSI & DIAGNOSA LANJUTAN
        // ========================================================================
        val status = WifiMonitor.statusFlow.value

        if (!status.isCamera) {
            val ssid = status.ssid ?: "Null"

            // Jika SSID tidak terbaca / unknown -> Masalah GPS atau Mode Permission
            if (ssid == "Null" || ssid.contains("unknown", ignoreCase = true)) {

                // Cek apakah GPS Hardware nyala
                val lm =
                    ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val isGpsOn = try {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                } catch (e: Exception) {
                    true
                } // Fallback true jika error cek provider

                if (!isGpsOn) {
                    Toast.makeText(
                        ctx,
                        "GPS Mati. Harap nyalakan GPS/Lokasi untuk deteksi Wi-Fi.",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } else {
                    // GPS Nyala tapi tetap Unknown -> Kemungkinan Izinnya "Approximate" (bukan Precise)
                    Toast.makeText(
                        ctx,
                        "SSID tidak terbaca. Pastikan izin lokasi diset ke 'PRECISE' (Akurat).",
                        Toast.LENGTH_LONG
                    ).show()

                    // Buka App Info agar user bisa ubah permission manual
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = Uri.fromParts("package", ctx.packageName, null)
                        intent.data = uri
                        startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } else {
                // SSID terbaca tapi bukan kamera
                Toast.makeText(
                    ctx,
                    "Terhubung ke: $ssid.\nSilakan pindah ke Wi-Fi Kamera.",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            return
        }

        // ========================================================================
        // 3. KONEKSI SUKSES -> BINDING NETWORK
        // ========================================================================

        // Cari object Network yang valid untuk kamera
        val camNet = findCameraWifiNetworkStrict() ?: run {
            // Fallback desperate: Jarang terjadi jika status.isCamera = true
            Toast.makeText(
                requireContext(),
                "Network error. Coba matikan/hidupkan Wi-Fi.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val cm =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // [KUNCI UTAMA] Bind process agar traffic HTTP/RTSP lewat Wi-Fi ini, bukan 4G
        runCatching { cm.bindProcessToNetwork(camNet) }

        // Pindah activitycls

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

    /**
     * Mencari Network Wi-Fi kamera dengan ketat.
     * Mampu mendeteksi Wi-Fi "No Internet" di Android 10+.
     */
    private fun findCameraWifiNetworkStrict(): Network? {
        val ctx = requireContext()
        val exact = prefs.getString("camera_ssid_exact", null)
        val prefix = prefs.getString("camera_ssid_prefix", "wifi_camera_MS2_")

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val all = cm.allNetworks

        // 1. Loop semua network (termasuk yang no internet)
        for (n in all) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue

            var ssid: String? = null
            // API 31+ bisa baca SSID dari caps
            if (Build.VERSION.SDK_INT >= 31) {
                ssid = (caps.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"")
            }

            // Jika SSID cocok, return network ini
            if (!exact.isNullOrBlank() && ssid == exact) return n
            if (!prefix.isNullOrBlank() && ssid?.startsWith(prefix) == true) return n
        }

        // 2. Fallback Logic:
        // Jika WifiMonitor sudah bilang "Connected" (artinya kita yakin connect ke kamera via WifiManager),
        // tapi loop di atas gagal dapat SSID (limitasi API 31+ terkadang null ssid di caps),
        // maka ambil network Wi-Fi apapun yang aktif.
        if (WifiMonitor.statusFlow.value.isCamera) {
            for (n in all) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return n
                }
            }
        }

        return null
    }

    // Helper (bisa dihapus jika tidak dipakai di tempat lain)
    private fun bindProcessToCameraIfMatch() {
        if (WifiMonitor.statusFlow.value.isCamera) {
            val cm =
                requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val camNet = findCameraWifiNetworkStrict()
            if (camNet != null) {
                runCatching { cm.bindProcessToNetwork(camNet) }
            }
        }
    }
}