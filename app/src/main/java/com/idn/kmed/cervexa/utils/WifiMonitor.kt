package com.idn.kmed.cervexa.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.idn.kmed.cervexa.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@SuppressLint("StaticFieldLeak")
object WifiMonitor {

    data class WifiStatus(
        val ssid: String?,    // SSID saat ini (tanpa tanda kutip), null jika tidak di Wi-Fi
        val isCamera: Boolean // true jika ssid == prefs.camera_ssid_exact
    )

    // === Public API ===
    private var simpleCallback: ((String?) -> Unit)? = null
    private var statusCallback: ((WifiStatus) -> Unit)? = null

    fun init(context: Context, onSsidChanged: (String?) -> Unit) {
        simpleCallback = onSsidChanged
        start(context)
    }

    fun setOnStatusChanged(callback: (WifiStatus) -> Unit) {
        statusCallback = callback
        lastStatus?.let { callback(it) }
    }

    fun stopMonitoring() {
        stopInternal()
    }

    fun handlePermissionResult(requestCode: Int, grantResults: IntArray, context: Context) {
        if (requestCode == REQ_PERM_NEARBY_OR_LOCATION) {
            val granted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) start(context)
        }
    }

    // === Internal state ===
    private const val REQ_PERM_NEARBY_OR_LOCATION = 2201

    private var appCtx: Context? = null
    private var cm: ConnectivityManager? = null
    private var wm: WifiManager? = null

    private var registered = false
    private var connCallback: ConnectivityManager.NetworkCallback? = null
    private var legacyReceiver: BroadcastReceiver? = null

    private var scope: CoroutineScope? = null
    private var statusFlowInternal = MutableStateFlow(WifiStatus(null, false))
    val statusFlow: StateFlow<WifiStatus> get() = statusFlowInternal

    private var lastStatus: WifiStatus? = null

    // === Start/Stop ===
    private fun start(context: Context) {
        appCtx = context.applicationContext
        cm = appCtx!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wm = appCtx!!.getSystemService(Context.WIFI_SERVICE) as WifiManager

        if (!hasWifiPermission(appCtx!!)) {
            requestWifiPermission(context)
            return
        }
        if (registered) return

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        if (Build.VERSION.SDK_INT >= 31) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = publishCurrent()
                override fun onLost(network: Network) = publishCurrent()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = publishCurrent()
            }
            connCallback = callback
            cm?.registerDefaultNetworkCallback(callback)
            registered = true
            publishCurrent()
        } else {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = publishCurrent()
            }
            legacyReceiver = receiver
            val filter = IntentFilter().apply {
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            }
            appCtx!!.registerReceiver(receiver, filter)
            registered = true
            publishCurrent()
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

    // === Permission ===
    private fun hasWifiPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestWifiPermission(context: Context) {
        if (context is android.app.Activity) {
            if (Build.VERSION.SDK_INT >= 33) {
                context.requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), REQ_PERM_NEARBY_OR_LOCATION)
            } else {
                context.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_PERM_NEARBY_OR_LOCATION)
            }
        }
    }

    // === Publish SSID + camera-flag ===
    @SuppressLint("MissingPermission")
    private fun publishCurrent() {
        val ctx = appCtx ?: return

        val prefName = ctx.getString(R.string.pref_application)
        val exactCameraSsid = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
            .getString("camera_ssid_exact", null)

        val (ssid, isWifi) = currentSsidAndIsWifi(ctx)

        val status = WifiStatus(
            ssid = ssid,
            isCamera = isWifi && !ssid.isNullOrBlank() && ssid == exactCameraSsid
        )
        lastStatus = status
        statusFlowInternal.value = status

        simpleCallback?.invoke(ssid)
        statusCallback?.invoke(status)
    }

    /** @return Pair<ssid, isWifiTransport> */
    private fun currentSsidAndIsWifi(ctx: Context): Pair<String?, Boolean> {
        val cmLocal = cm ?: return null to false
        val active = cmLocal.activeNetwork ?: return null to false
        val caps = cmLocal.getNetworkCapabilities(active) ?: return null to false
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        if (!isWifi) return null to false

        return if (Build.VERSION.SDK_INT >= 31) {
            val info = caps.transportInfo as? WifiInfo
            val ssid = info?.ssid?.removeSurrounding("\"")
            ssid to true
        } else {
            val wmLocal = wm ?: (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            @Suppress("DEPRECATION")
            val ssid = wmLocal.connectionInfo?.ssid?.replace("\"", "")
            ssid to true
        }
    }
}
