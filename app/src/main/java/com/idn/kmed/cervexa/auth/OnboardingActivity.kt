package com.idn.kmed.cervexa.auth

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.idn.kmed.cervexa.utils.WifiCamConnection
import androidx.core.content.edit
import com.google.android.material.appbar.MaterialToolbar
import com.idn.kmed.cervexa.home.HomeActivity
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.network.TokenManager

class OnboardingActivity : AppCompatActivity() {

    private lateinit var screen1: ViewGroup
    private lateinit var screen2: ViewGroup

    private val handler = Handler(Looper.getMainLooper())
    private var fallbackRunnable: Runnable? = null
    private val backStack = ArrayDeque<Int>() // 1/2

    // ---- NEW: state untuk re-check setelah balik dari Settings
    private var openedWifiSettings = false
    private var recheckAttempts = 0
    private var recheckRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        screen1 = findViewById(R.id.screen1)
        screen2 = findViewById(R.id.screen2)

        findViewById<Button>(R.id.btnConnect1).setOnClickListener {
            showScreen(2, pushToBackStack = true)
        }

        val toolbar2 =
            findViewById<MaterialToolbar>(R.id.toolbar2)
        toolbar2.navigationIcon =
            AppCompatResources.getDrawable(this, R.drawable.ic_arrow_back)
        toolbar2.setNavigationOnClickListener { handleBack() }

        findViewById<Button>(R.id.btnConnect2).setOnClickListener {
            val prefs = getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
            val prefix =
                prefs.getString("camera_ssid_prefix", "wifi_camera_MS2_") ?: "wifi_camera_MS2_"
            connectToCameraWifiWithFallback(
                ssidPrefix = prefix,
                password = null,     // isi jika perlu
                timeoutMs = 12_000L
            )
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        showScreen(1, pushToBackStack = false, animate = false)
    }

    override fun onResume() {
        super.onResume()
        // Kalau baru saja balik dari Settings, lakukan re-check berkala 6x @ 1s
        if (openedWifiSettings) {
            startRecheckAfterSettings()
        }
    }

    override fun onPause() {
        super.onPause()
        stopRecheckAfterSettings()
    }

    private fun handleBack() {
        if (backStack.isEmpty()) finish()
        else {
            backStack.removeLastOrNull()
            val prev = backStack.lastOrNull() ?: 1
            showScreen(prev, pushToBackStack = false, isBack = true)
        }
    }

    private fun showScreen(
        target: Int,
        pushToBackStack: Boolean,
        isBack: Boolean = false,
        animate: Boolean = true
    ) {
        val from = if (screen2.visibility == View.VISIBLE) 2 else 1
        if (from == target) return

        val show = if (target == 2) screen2 else screen1
        val hide = if (target == 2) screen1 else screen2

        if (animate) {
            val width = hide.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val inFromX = if (isBack) -width.toFloat() else width.toFloat()
            val outToX = if (isBack) width.toFloat() else -width.toFloat()

            show.translationX = inFromX
            show.visibility = View.VISIBLE
            show.animate().translationX(0f).setDuration(220L).start()
            hide.animate().translationX(outToX).setDuration(220L).withEndAction {
                hide.visibility = View.GONE
                hide.translationX = 0f
            }.start()
        } else {
            hide.visibility = View.GONE
            show.visibility = View.VISIBLE
        }

        if (pushToBackStack) {
            if (backStack.isEmpty()) backStack.addLast(from)
            backStack.addLast(target)
        }
    }

    // ===================== CONNECT VIA SPECIFIER + SUGGESTION & FALLBACK =====================

    private fun hasWifiPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestWifiPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 1001)
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1002)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToCameraWifiWithFallback(
        ssidPrefix: String,
        password: String?,
        timeoutMs: Long
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            openWifiSettings(); return
        }
        if (!hasWifiPermissions()) {
            requestWifiPermissions()
            openWifiSettings()
            return
        }

        val spec = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(ssidPrefix, PatternMatcher.PATTERN_PREFIX))
            .apply { if (!password.isNullOrEmpty()) setWpa2Passphrase(password) }
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(spec)
            .build()

        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)

                // Simpan sementara (opsional). HomeActivity nanti akan bind lagi via allNetworks.
                WifiCamConnection.hold(network, this)

                runOnUiThread {
                    cancelFallbackTimer()
                    onCameraWifiConnectedByAnyMeans(network, ssidPrefix, cm, password)
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                runOnUiThread {
                    Toast.makeText(
                        this@OnboardingActivity,
                        "Jaringan kamera tidak ditemukan",
                        Toast.LENGTH_SHORT
                    ).show()
                    openWifiSettings()
                }
            }
        }

        cm.requestNetwork(request, callback)
        startFallbackTimer(timeoutMs) { openWifiSettings() }
    }

    /** Dipanggil saat konek via specifier ATAU saat kita mendeteksi koneksi setelah balik dari Settings */
    private fun onCameraWifiConnectedByAnyMeans(
        network: Network?,
        ssidPrefix: String,
        cm: ConnectivityManager,
        password: String?
    ) {
        // Ambil SSID jika tersedia (API 31+ via transportInfo)
        val ssid = runCatching {
            val caps =
                if (network != null) cm.getNetworkCapabilities(network) else cm.getNetworkCapabilities(
                    cm.activeNetwork
                )
            if (Build.VERSION.SDK_INT >= 31) {
                (caps?.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"")
            } else null
        }.getOrNull()

        val prefs = getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
        prefs.edit { putBoolean("on_boarding", true) }
        prefs.edit { putString("camera_ssid_prefix", ssidPrefix) }
        if (!ssid.isNullOrBlank()) {
            prefs.edit { putString("camera_ssid_exact", ssid) }
        }

        addCameraWifiSuggestion(ssid = ssid ?: ssidPrefix, password = password)

        // Sesudah
        val tokenManager = TokenManager.getInstance(this@OnboardingActivity)
        // Beres → pindah ke Login , kalau sudah login maka ke home
        val nextClass =
            if (tokenManager.isLoggedIn) HomeActivity::class.java else LoginActivity::class.java

        startActivity(Intent(this@OnboardingActivity, nextClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("from_onboarding", true)
        })
        finish()
        finish()
    }

    private fun addCameraWifiSuggestion(ssid: String, password: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        val builder = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setIsAppInteractionRequired(false)

        if (!password.isNullOrEmpty()) builder.setWpa2Passphrase(password)

        wm.addNetworkSuggestions(listOf(builder.build()))
    }

    private fun startFallbackTimer(timeoutMs: Long, action: () -> Unit) {
        cancelFallbackTimer()
        fallbackRunnable = Runnable { action() }
        handler.postDelayed(fallbackRunnable!!, timeoutMs)
    }

    private fun cancelFallbackTimer() {
        fallbackRunnable?.let { handler.removeCallbacks(it) }
        fallbackRunnable = null
    }

    private fun openWifiSettings() {
        openedWifiSettings = true
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Tidak bisa membuka pengaturan Wi-Fi", Toast.LENGTH_SHORT).show()
        }
    }

    // ===================== RE-CHECK SETELAH BALIK DARI SETTINGS =====================

    private fun startRecheckAfterSettings() {
        stopRecheckAfterSettings()
        recheckAttempts = 0
        val prefs = getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
        val ssidPrefix =
            prefs.getString("camera_ssid_prefix", "wifi_camera_MS2_") ?: "wifi_camera_MS2_"

        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        recheckRunnable = object : Runnable {
            override fun run() {
                recheckAttempts++

                val camNet = findCameraWifiNetwork(ssidPrefix, cm)
                if (camNet != null) {
                    // Ketemu jaringan kamera → finalize dan pindah ke Home
                    onCameraWifiConnectedByAnyMeans(camNet, ssidPrefix, cm, password = null)
                    openedWifiSettings = false
                    stopRecheckAfterSettings()
                    return
                }

                if (recheckAttempts < 6) {
                    handler.postDelayed(this, 1000L) // coba lagi tiap 1 detik, maks 6x
                } else {
                    // gagal deteksi; biarkan user tekan tombol connect lagi
                    openedWifiSettings = false
                    stopRecheckAfterSettings()
                }
            }
        }
        handler.postDelayed(recheckRunnable!!, 500L)
    }

    private fun stopRecheckAfterSettings() {
        recheckRunnable?.let { handler.removeCallbacks(it) }
        recheckRunnable = null
    }

    /** Ambil SSID dari caps (API 31+). */
    private fun ssidFromCaps(caps: NetworkCapabilities): String? {
        return if (Build.VERSION.SDK_INT >= 31)
            (caps.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"")
        else null
    }

    /**
     * Cari jaringan kamera dari allNetworks:
     * - Coba exact (jika tersimpan saat connect sebelumnya).
     * - Coba prefix.
     * - Heuristik: Wi-Fi tanpa internet/validasi.
     */
    private fun findCameraWifiNetwork(ssidPrefix: String, cm: ConnectivityManager): Network? {
        val prefs = getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
        val exact = prefs.getString("camera_ssid_exact", null)

        val all = cm.allNetworks ?: return null

        if (Build.VERSION.SDK_INT >= 31 && !exact.isNullOrBlank()) {
            for (n in all) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                if (ssidFromCaps(caps) == exact) return n
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            for (n in all) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val ssid = ssidFromCaps(caps) ?: continue
                if (ssid.startsWith(ssidPrefix, ignoreCase = false)) return n
            }
        }

        // Heuristik: Wi-Fi tanpa internet/validasi (umum pada kamera)
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
}