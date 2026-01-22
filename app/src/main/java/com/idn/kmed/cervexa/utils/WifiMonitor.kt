package com.idn.kmed.cervexa.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.idn.kmed.cervexa.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@SuppressLint("StaticFieldLeak")
object WifiMonitor {

    data class WifiStatus(val ssid: String?, val isCamera: Boolean)

    private const val REQ_PERM_NEARBY_OR_LOCATION = 2201
    private const val DEFAULT_PREFIX = "wifi_camera_MS2_"

    private var simpleCallback: ((String?) -> Unit)? = null
    private var statusCallback: ((WifiStatus) -> Unit)? = null
    private var appCtx: Context? = null
    private var cm: ConnectivityManager? = null
    private var wm: WifiManager? = null
    private var registered = false
    private var connCallback: ConnectivityManager.NetworkCallback? = null
    private var legacyReceiver: BroadcastReceiver? = null
    private var scope: CoroutineScope? = null

    private val statusFlowInternal = MutableStateFlow(WifiStatus(null, false))
    val statusFlow: StateFlow<WifiStatus> get() = statusFlowInternal
    private var lastStatus: WifiStatus? = null

    fun init(context: Context, onSsidChanged: (String?) -> Unit) {
        simpleCallback = onSsidChanged
        start(context)
    }

    fun setOnStatusChanged(callback: (WifiStatus) -> Unit) {
        statusCallback = callback
        lastStatus?.let { callback(it) }
    }

    fun stopMonitoring() {
        simpleCallback = null
        statusCallback = null
        stopInternal()
    }

    fun handlePermissionResult(requestCode: Int, grantResults: IntArray, context: Context) {
        if (requestCode == REQ_PERM_NEARBY_OR_LOCATION) {
            val granted =
                grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) start(context)
        }
    }

    private fun start(context: Context) {
        appCtx = context.applicationContext
        val ctx = appCtx ?: return
        cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager

        if (!hasWifiPermission(ctx)) {
            requestWifiPermission(context)
            return
        }

        // Always publish current state immediately when starting/re-starting
        publishCurrent()

        if (registered) return

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        if (Build.VERSION.SDK_INT >= 31) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = publishCurrent()
                override fun onLost(network: Network) = publishCurrent()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    publishCurrent()

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: android.net.LinkProperties
                ) = publishCurrent()
            }
            connCallback = callback

            // Request SPECIFICALLY for WIFI transport (even without internet)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            try {
                cm?.registerNetworkCallback(request, callback)
                registered = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    publishCurrent()
                }
            }
            legacyReceiver = receiver
            val filter = IntentFilter().apply {
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                @Suppress("DEPRECATION")
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            }
            ctx.registerReceiver(receiver, filter)
            registered = true
        }
    }

    private fun stopInternal() {
        if (!registered) return
        runCatching { connCallback?.let { cm?.unregisterNetworkCallback(it) } }
        connCallback = null
        runCatching { legacyReceiver?.let { appCtx?.unregisterReceiver(it) } }
        legacyReceiver = null
        registered = false
        scope?.cancel()
        scope = null
    }

    @SuppressLint("MissingPermission")
    private fun publishCurrent() {
        val ctx = appCtx ?: return
        val prefName = ctx.getString(R.string.pref_application)
        val pref = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val exactCameraSsid = pref.getString("camera_ssid_exact", null)
        val prefix = pref.getString("camera_ssid_prefix", DEFAULT_PREFIX)?.trim().orEmpty()

        val (ssidRaw, isWifi) = currentSsidAndIsWifi(ctx)
        val ssid = normalizeSsid(ssidRaw)

        val isCameraNow = isWifi && !ssid.isNullOrBlank() && (
                (!exactCameraSsid.isNullOrBlank() && ssid == exactCameraSsid) ||
                        (prefix.isNotBlank() && ssid.startsWith(prefix))
                )

        val status = WifiStatus(ssid = ssid, isCamera = isCameraNow)
        lastStatus = status

        scope?.launch {
            statusFlowInternal.value = status
            simpleCallback?.invoke(ssid)
            statusCallback?.invoke(status)
        } ?: run {
            statusFlowInternal.value = status
            simpleCallback?.invoke(ssid)
            statusCallback?.invoke(status)
        }
    }

    private fun currentSsidAndIsWifi(ctx: Context): Pair<String?, Boolean> {
        val cmLocal = cm ?: return null to false

        // 1. Try API 31+ NetworkCapabilities
        if (Build.VERSION.SDK_INT >= 31) {
            val allNetworks = cmLocal.allNetworks
            for (network in allNetworks) {
                val caps = cmLocal.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val info = caps.transportInfo as? WifiInfo
                    if (info != null && !info.ssid.isNullOrBlank() && info.ssid != "<unknown ssid>") {
                        return info.ssid to true
                    }
                }
            }
        }

        // 2. Fallback to WifiManager (Crucial for "No Internet" scenarios on some devices)
        val wmLocal =
            wm ?: (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        if (wmLocal.isWifiEnabled) {
            @Suppress("DEPRECATION")
            val info = wmLocal.connectionInfo
            if (info != null && !info.ssid.isNullOrBlank() && info.ssid != "<unknown ssid>" && info.networkId != -1) {
                return info.ssid to true
            }
        }

        return null to false
    }

    private fun normalizeSsid(raw: String?): String? {
        val s = raw?.removeSurrounding("\"")?.trim()
        if (s.isNullOrEmpty()) return null
        if (s.equals("<unknown ssid>", true)) return null
        if (s.equals("unknown ssid", true)) return null
        if (s.contains("0x")) return null
        return s
    }

    private fun hasWifiPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestWifiPermission(context: Context) {
        if (context is android.app.Activity) {
            if (Build.VERSION.SDK_INT >= 33) {
                context.requestPermissions(
                    arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES),
                    REQ_PERM_NEARBY_OR_LOCATION
                )
            } else {
                context.requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    REQ_PERM_NEARBY_OR_LOCATION
                )
            }
        }
    }
}