package com.idn.kmed.cervexa

import android.content.*
import android.content.Context.MODE_PRIVATE
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
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

    private fun handleStartClickHome() {
        val isD3m0: Boolean = false
        val camNet = findCameraWifiNetwork()

        if (camNet != null) {
            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (isD3m0) {
                val valCntRecord = prefs.getInt("D3M0_K3Y_M4X_C0UN7", 0)
                if (valCntRecord == 5) {
                    Toast.makeText(
                        requireContext(),
                        "Anda sudah melakukan 5x Percobaan, silahkan menggunakan versi Release!",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                } else {
                    prefs.edit { putInt("D3M0_K3Y_M4X_C0UN7", valCntRecord + 1) }
                }
            }

            runCatching { cm.bindProcessToNetwork(camNet) }
            startActivity(Intent(requireContext(), ConfirmPatientActivity::class.java))
        } else {
            Toast.makeText(requireContext(), "Belum terhubung ke Wi-Fi kamera", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_home_dashboard, container, false)

        requireActivity().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
            ?.title = "Cervexa"

        wifiViewModel = ViewModelProvider(requireActivity())[WifiViewModel::class.java]
        tvStatus = v.findViewById(R.id.statusConnect)
        btnConnect = v.findViewById(R.id.btn_connect)
        imgIndicator = v.findViewById(R.id.imgIndicator)

        btnConnect.setOnClickListener { handleStartClickHome() }

        // [TV OPTIMIZATION] Pastikan tombol utama focusable
        btnConnect.isFocusable = true
        btnConnect.isFocusableInTouchMode = true

        WifiMonitor.init(requireContext()) { ssid ->
            wifiViewModel.updateSsid(ssid)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                wifiViewModel.ssidFlow.collect {
                    refreshUiWithCurrentStatus()
                }
            }
        }

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // [TV OPTIMIZATION] Paksa fokus ke tombol connect saat fragment muncul
        // agar user tidak perlu tekan tab/panah berkali-kali
//        btnConnect.postDelayed({
//            if (isAdded) btnConnect.requestFocus()
//        }, 300)
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
        WifiMonitor.stopMonitoring()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { requireContext().unregisterReceiver(postConnReceiver) }
        }
    }

    private fun refreshUiWithCurrentStatus() {
        if (isOnCameraWifi()) {
            imgIndicator.setImageResource(R.drawable.device_active)
            tvStatus.text = "Terhubung"
            btnConnect.text = "Mulai"
        } else {
            imgIndicator.setImageResource(R.drawable.device_inactive)
            val status = wifiViewModel.ssidFlow.value
            tvStatus.text =
                if (status.isNullOrBlank()) "Koneksi Terputus" else "Terhubung ke Wi-Fi lain"
            btnConnect.text = "Hubungkan Kembali"
        }
    }

    private fun getSsidFromCaps(caps: NetworkCapabilities): String? =
        if (Build.VERSION.SDK_INT >= 31) (caps.transportInfo as? WifiInfo)?.ssid?.removeSurrounding(
            "\""
        ) else null

    private fun findCameraWifiNetwork(): Network? {
        val prefs = requireContext().getSharedPreferences(
            getString(R.string.pref_application),
            AppCompatActivity.MODE_PRIVATE
        )
        val exact = prefs.getString("camera_ssid_exact", null)
        val prefix = prefs.getString("camera_ssid_prefix", "wifi_camera_MS2_")

        val cm =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val all = cm.allNetworks ?: return null

        if (Build.VERSION.SDK_INT >= 31 && !exact.isNullOrBlank()) {
            for (n in all) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                if (getSsidFromCaps(caps) == exact) return n
            }
        }
        if (Build.VERSION.SDK_INT >= 31 && !prefix.isNullOrBlank()) {
            for (n in all) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val ssid = getSsidFromCaps(caps) ?: continue
                if (ssid.startsWith(prefix, ignoreCase = false)) return n
            }
        }

        var fallbackWifi: Network? = null
        for (n in all) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (!validated || !hasInternet) return n
            if (fallbackWifi == null) fallbackWifi = n
        }
        return fallbackWifi
    }

    private fun isOnCameraWifi(): Boolean = findCameraWifiNetwork() != null

    private fun bindProcessToCameraIfMatch() {
        val cm =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val camNet = findCameraWifiNetwork() ?: return
        runCatching { cm.bindProcessToNetwork(camNet) }
    }
}