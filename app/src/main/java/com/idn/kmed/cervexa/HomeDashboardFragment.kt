package com.idn.kmed.cervexa

import android.annotation.SuppressLint
import android.content.*
import android.content.Context.MODE_PRIVATE
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.bottomnavigation.BottomNavigationView
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

    private val postConnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                android.net.wifi.WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION == intent.action
            ) {
                Toast.makeText(context, "Tersambung ke Wi-Fi kamera", Toast.LENGTH_SHORT).show()
                bindProcessToCameraIfMatch()
                refreshUiWithCurrentStatus()
            }
        }
    }

    // =========================
    // Lifecycle
    // =========================
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_home_dashboard, container, false)

        requireActivity()
            .findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
            ?.title = "Cervexa"

        wifiViewModel = ViewModelProvider(requireActivity())[WifiViewModel::class.java]
        tvStatus = v.findViewById(R.id.statusConnect)
        btnConnect = v.findViewById(R.id.btn_connect)
        imgIndicator = v.findViewById(R.id.imgIndicator)

        btnConnect.setOnClickListener { handleStartClickHome() }

        // [TV OPTIMIZATION] Pastikan tombol utama focusable
        btnConnect.isFocusable = true
        btnConnect.isFocusableInTouchMode = true

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                wifiViewModel.statusFlow.collect {
                    refreshUiWithCurrentStatus()
                }
            }
        }

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // [TV OPTIMIZATION - FIX NAVIGATION]
        btnConnect.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                val bottomNav = requireActivity().findViewById<BottomNavigationView>(R.id.nav_view)
                val menuView = bottomNav.getChildAt(0) as? ViewGroup
                val homeIcon = menuView?.getChildAt(0)

                if (homeIcon != null) {
                    homeIcon.requestFocus()
                    return@setOnKeyListener true
                }
            }
            false
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requireContext().registerReceiver(
                postConnReceiver,
                IntentFilter(android.net.wifi.WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION)
            )
        }
        refreshUiWithCurrentStatus()
        bindProcessToCameraIfMatch()
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { requireContext().unregisterReceiver(postConnReceiver) }
        }
    }

    // =========================
    // UI
    // =========================
    private fun refreshUiWithCurrentStatus() {
        val status = wifiViewModel.statusFlow.value
        if (status.isCamera) {
            imgIndicator.setImageResource(R.drawable.device_active)
            tvStatus.text = "Terhubung"
            btnConnect.text = "Mulai"
        } else {
            imgIndicator.setImageResource(R.drawable.device_inactive)
            tvStatus.text =
                if (status.ssid.isNullOrBlank()) "Koneksi Terputus" else "Terhubung ke Wi-Fi lain"
            btnConnect.text = "Hubungkan Kembali"
        }
    }

    // =========================
    // Actions
    // =========================
    private fun handleStartClickHome() {
//        val isCamera = (WifiMonitor.statusFlow.value?.isCamera == true) // atau simpan dari callback
//        if (!isCamera) {
//            Toast.makeText(requireContext(), "Belum terhubung ke Wi-Fi kamera", Toast.LENGTH_SHORT).show()
//            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
//            return
//        }
//
//        val camNet = findCameraWifiNetworkStrict() ?: run {
//            Toast.makeText(requireContext(), "Wi-Fi kamera terdeteksi, tapi network belum terbaca", Toast.LENGTH_SHORT).show()
//            return
//        }
//
//        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
//        runCatching { cm.bindProcessToNetwork(camNet) }
        startActivity(Intent(requireContext(), ConfirmPatientActivity::class.java))
    }

    // =========================
    // Network Helpers (Strict + Cross-version)
    // =========================
    private fun getSsidFromCaps(caps: NetworkCapabilities): String? {
        return if (Build.VERSION.SDK_INT >= 31) {
            (caps.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"")
        } else null
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun getCurrentSsidLegacy(ctx: Context): String? {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = wm.connectionInfo?.ssid ?: return null
        return ssid.replace("\"", "").takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    /**
     * Strict = hanya dianggap kamera kalau SSID match exact/prefix.
     * Return Network Wi-Fi kamera untuk dipakai bindProcessToNetwork.
     */
    private fun findCameraWifiNetworkStrict(): Network? {
        val ctx = requireContext()
        val exact = prefs.getString("camera_ssid_exact", null)
        val prefix = prefs.getString("camera_ssid_prefix", "wifi_camera_MS2_")

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val all = cm.allNetworks ?: return null

        // ========= API 31+ =========
        if (Build.VERSION.SDK_INT >= 31) {
            for (n in all) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue

                val ssid = getSsidFromCaps(caps) ?: continue
                if (!exact.isNullOrBlank() && ssid == exact) return n
                if (!prefix.isNullOrBlank() && ssid.startsWith(prefix)) return n
            }
            return null
        }

        // ========= API < 31 =========
        val currentSsid = getCurrentSsidLegacy(ctx) ?: return null

        val match =
            (!exact.isNullOrBlank() && currentSsid == exact) ||
                    (!prefix.isNullOrBlank() && currentSsid.startsWith(prefix))

        if (!match) return null

        // Prefer active wifi network
        val active = cm.activeNetwork
        if (active != null) {
            val caps = cm.getNetworkCapabilities(active)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return active
            }
        }

        // Fallback: first WIFI network in allNetworks
        for (n in all) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n
        }

        return null
    }

    private fun bindProcessToCameraIfMatch() {
        val cm =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val camNet = findCameraWifiNetworkStrict() ?: return
        runCatching { cm.bindProcessToNetwork(camNet) }
    }
}
