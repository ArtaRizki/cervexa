package com.idn.kmed.cervexa.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object WifiCamConnection {
    @Volatile var network: Network? = null
        private set
    @Volatile var callback: ConnectivityManager.NetworkCallback? = null
        private set

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingTimeoutRunnable: Runnable? = null

    fun hold(network: Network, callback: ConnectivityManager.NetworkCallback) {
        synchronized(lock) {
            this.network = network
            this.callback = callback
        }
    }

    fun release(context: Context) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val oldCallback: ConnectivityManager.NetworkCallback?
        synchronized(lock) {
            pendingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingTimeoutRunnable = null
            oldCallback = callback
            callback = null
            network = null
        }
        oldCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
    }

    /** Hapus suggestion SSID spesifik dari versi lama agar HP bisa pindah ke unit kamera lain tanpa Clear Data. */
    fun clearWifiSuggestions(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        runCatching { wm.removeNetworkSuggestions(emptyList()) }
    }

    fun openWifiSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            val fallback = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
        }
    }

    /** Menghubungkan ke Wi-Fi kamera berdasarkan prefix default tanpa mengunci SSID satu perangkat tertentu. */
    fun connectToCameraWifi(
        context: Context,
        ssidPrefix: String = WifiMonitor.DEFAULT_PREFIX,
        timeoutMs: Long = 15_000L,
        onConnected: (Network) -> Unit,
        onUnavailableOrTimeout: () -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            mainHandler.post { onUnavailableOrTimeout() }
            return
        }

        val appCtx = context.applicationContext
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cleanPrefix = ssidPrefix.trim().ifBlank { WifiMonitor.DEFAULT_PREFIX }

        release(appCtx)
        clearWifiSuggestions(appCtx)

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(cleanPrefix, PatternMatcher.PATTERN_PREFIX))
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val finished = AtomicBoolean(false)

        lateinit var networkCallback: ConnectivityManager.NetworkCallback
        val timeoutRunnable = Runnable {
            if (finished.compareAndSet(false, true)) {
                synchronized(lock) {
                    if (callback === networkCallback) {
                        callback = null
                        network = null
                    }
                    pendingTimeoutRunnable = null
                }
                runCatching { cm.unregisterNetworkCallback(networkCallback) }
                onUnavailableOrTimeout()
            }
        }

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(net: Network) {
                if (finished.compareAndSet(false, true)) {
                    synchronized(lock) {
                        pendingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                        pendingTimeoutRunnable = null
                        network = net
                        callback = this
                    }
                    WifiMonitor.refreshNow()
                    mainHandler.post { onConnected(net) }
                }
            }

            override fun onUnavailable() {
                if (finished.compareAndSet(false, true)) {
                    synchronized(lock) {
                        pendingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                        pendingTimeoutRunnable = null
                        if (callback === this) {
                            callback = null
                            network = null
                        }
                    }
                    runCatching { cm.unregisterNetworkCallback(this) }
                    mainHandler.post { onUnavailableOrTimeout() }
                }
            }

            override fun onLost(net: Network) {
                val cleared = synchronized(lock) {
                    if (callback === this && network == net) {
                        callback = null
                        network = null
                        true
                    } else {
                        false
                    }
                }
                if (cleared) {
                    WifiMonitor.refreshNow()
                }
            }
        }

        synchronized(lock) {
            callback = networkCallback
            pendingTimeoutRunnable = timeoutRunnable
        }
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)

        try {
            cm.requestNetwork(request, networkCallback)
        } catch (_: Throwable) {
            if (finished.compareAndSet(false, true)) {
                synchronized(lock) {
                    pendingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                    pendingTimeoutRunnable = null
                    if (callback === networkCallback) {
                        callback = null
                        network = null
                    }
                }
                mainHandler.post { onUnavailableOrTimeout() }
            }
        }
    }

    /** Dialog pilihan koneksi kamera agar user tidak dilempar ke App Info saat belum terhubung Wi-Fi. */
    fun showConnectCameraDialog(
        activity: Activity,
        status: WifiMonitor.WifiStatus,
        onAutoSearch: () -> Unit,
        onOpenWifiSettings: () -> Unit = { openWifiSettings(activity) },
        onOpenPermissionSettings: (() -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val title = if (status.isWifiConnected && status.ssid.isNullOrBlank()) {
            "Kamera Belum Terdeteksi Otomatis"
        } else {
            "Hubungkan ke Wi-Fi Kamera"
        }

        val message = buildString {
            append("Pastikan perangkat kamera mikroskop sudah menyala.\n\n")
            append("Pilih metode koneksi:\n")
            append("• Cari Kamera Otomatis (prefix default ${WifiMonitor.DEFAULT_PREFIX})\n")
            append("• Buka Pengaturan Wi-Fi HP untuk memilih Wi-Fi kamera secara manual.")
            if (onOpenPermissionSettings != null) {
                append("\n\nCatatan: Izin Lokasi/Perangkat Sekitar dibutuhkan Android hanya untuk membaca nama Wi-Fi secara otomatis.")
            }
        }

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Cari Kamera Otomatis") { _, _ -> onAutoSearch() }
            .setNegativeButton("Pengaturan Wi-Fi") { _, _ -> onOpenWifiSettings() }

        if (onOpenPermissionSettings != null) {
            builder.setNeutralButton("Izin Aplikasi") { _, _ -> onOpenPermissionSettings() }
        } else {
            builder.setNeutralButton("Batal", null)
        }
        builder.show()
    }

    /** Alur koneksi terpusat untuk HomeDashboard & MediaList: deteksi/probe kamera dahulu, lalu dialog opsi bila belum terhubung. */
    fun ensureCameraReadyOrShowConnectFlow(
        fragment: androidx.fragment.app.Fragment,
        scope: CoroutineScope,
        proceedImmediatelyAfterAutoConnect: Boolean,
        onReady: (Network) -> Unit
    ) {
        val activity = fragment.activity ?: return
        val appCtx = fragment.context?.applicationContext ?: return

        scope.launch(Dispatchers.Main.immediate) {
            val camNet = WifiMonitor.verifyOrProbeCameraNetwork(appCtx)
            if (!fragment.isAdded || activity.isFinishing || activity.isDestroyed) return@launch

            if (camNet != null) {
                WifiMonitor.ensureBoundToCameraNetwork(appCtx, camNet)
                onReady(camNet)
                return@launch
            }

            val status = WifiMonitor.statusFlow.value
            val hasPerms = WifiMonitor.hasRequiredWifiPermissions(appCtx)
            showConnectCameraDialog(
                activity = activity,
                status = status,
                onAutoSearch = {
                    android.widget.Toast.makeText(
                        activity,
                        "Mencari kamera (${WifiMonitor.DEFAULT_PREFIX}...)...",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    connectToCameraWifi(
                        context = appCtx,
                        ssidPrefix = WifiMonitor.DEFAULT_PREFIX,
                        timeoutMs = 15_000L,
                        onConnected = { net ->
                            WifiMonitor.ensureBoundToCameraNetwork(appCtx, net)
                            if (!fragment.isAdded || activity.isFinishing || activity.isDestroyed) return@connectToCameraWifi
                            android.widget.Toast.makeText(
                                activity,
                                "Berhasil terhubung ke Wi-Fi Kamera.",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            if (proceedImmediatelyAfterAutoConnect) {
                                onReady(net)
                            }
                        },
                        onUnavailableOrTimeout = {
                            if (!fragment.isAdded || activity.isFinishing || activity.isDestroyed) return@connectToCameraWifi
                            android.widget.Toast.makeText(
                                activity,
                                "Kamera belum ditemukan otomatis. Pilih Wi-Fi kamera secara manual.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            openWifiSettings(activity)
                        }
                    )
                },
                onOpenWifiSettings = { openWifiSettings(activity) },
                onOpenPermissionSettings = if (!hasPerms) {
                    {
                        @Suppress("DEPRECATION")
                        fragment.requestPermissions(WifiMonitor.requiredWifiPermissionsArray(), 2201)
                    }
                } else {
                    null
                }
            )
        }
    }
}
