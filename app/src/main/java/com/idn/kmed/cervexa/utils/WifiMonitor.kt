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

    data class WifiStatus(
        val ssid: String?,
        val isCamera: Boolean,
        val isWifiConnected: Boolean = false
    )

    private const val REQ_PERM_NEARBY_OR_LOCATION = 2201
    const val DEFAULT_PREFIX = "wifi_camera_MS2_"
    private const val CAMERA_HOST = "192.168.1.1"
    private const val CAMERA_RTSP_PORT = 554

    private val cachedSsidByHandle = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private val verifiedCameraHandles = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
    private val probeInFlightHandles = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private var wifiEventCallback: ConnectivityManager.NetworkCallback? = null

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

        appCtx?.let { ctx ->
            val prefName = ctx.getString(R.string.pref_application)
            val pref = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
            if (pref.contains("camera_ssid_exact")) {
                pref.edit().remove("camera_ssid_exact").apply()
            }
            val savedPrefix = pref.getString("camera_ssid_prefix", null)?.trim()
            if (savedPrefix.isNullOrBlank()) {
                pref.edit().putString("camera_ssid_prefix", DEFAULT_PREFIX).apply()
            }
            WifiCamConnection.clearWifiSuggestions(ctx)
        }

        wifiEventCallback?.let { oldCb ->
            runCatching { cm?.unregisterNetworkCallback(oldCb) }
            wifiEventCallback = null
        }
        runCatching {
            val req = android.net.NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val transportCb = buildWifiCallback()
            cm?.registerNetworkCallback(req, transportCb)
            wifiEventCallback = transportCb
        }

        if (Build.VERSION.SDK_INT >= 31) {
            val callback = buildWifiCallback()
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

    fun refreshNow() {
        publishCurrent()
    }

    fun requiredWifiPermissionsArray(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                android.Manifest.permission.NEARBY_WIFI_DEVICES,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
            Build.VERSION.SDK_INT >= 29 -> arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
            else -> arrayOf(
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    fun hasRequiredWifiPermissions(ctx: Context): Boolean {
        return requiredWifiPermissionsArray().all { perm ->
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx,
                perm
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    fun isCameraSsid(rawSsid: String?, customPrefix: String? = null): Boolean {
        val ssid = sanitizeSsid(rawSsid) ?: return false
        val candidates = listOfNotNull(
            customPrefix?.trim()?.takeIf { it.isNotBlank() },
            DEFAULT_PREFIX,
            "wifi_camera",
            "MS2_"
        ).distinct()
        if (candidates.any { ssid.startsWith(it, ignoreCase = true) }) return true
        return ssid.contains("MS2", ignoreCase = true) && ssid.contains("cam", ignoreCase = true)
    }

    fun findCameraWifiNetwork(ctx: Context): Network? {
        val appContext = ctx.applicationContext
        val cmLocal = cm ?: (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?: return null
        val prefName = appContext.getString(R.string.pref_application)
        val pref = appContext.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val prefix = pref.getString("camera_ssid_prefix", DEFAULT_PREFIX)?.trim().orEmpty()
            .ifBlank { DEFAULT_PREFIX }
        return inspectWifiState(appContext, cmLocal, prefix, triggerPassiveProbe = false).cameraNetwork
    }

    suspend fun verifyOrProbeCameraNetwork(ctx: Context): Network? {
        val appContext = ctx.applicationContext
        findCameraWifiNetwork(appContext)?.let { existing ->
            publishCurrent()
            return existing
        }

        val cmLocal = cm ?: (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?: return null

        val wifiNets = cmLocal.allNetworks.filter { net ->
            val caps = cmLocal.getNetworkCapabilities(net) ?: return@filter false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) || hasSubnet1921681(cmLocal, net))
        }

        if (wifiNets.isEmpty()) return null

        val probedNet = kotlinx.coroutines.withContext(Dispatchers.IO) {
            for (net in wifiNets) {
                if (probeCameraRtspSync(net)) {
                    verifiedCameraHandles[net.networkHandle] = true
                    return@withContext net
                }
            }
            null
        }

        if (probedNet != null) {
            publishCurrent()
        }
        return probedNet
    }

    fun ensureBoundToCameraNetwork(ctx: Context, preferredNetwork: Network? = null): Network? {
        val appContext = ctx.applicationContext
        val cmLocal = cm ?: (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?: return null
        val target = preferredNetwork ?: findCameraWifiNetwork(appContext) ?: return null
        runCatching { cmLocal.bindProcessToNetwork(target) }
        return target
    }

    private fun buildWifiCallback(): ConnectivityManager.NetworkCallback {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = publishCurrent()
                override fun onLost(network: Network) = handleNetworkLost(network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    cacheSsidFromCaps(network, caps)
                    publishCurrent()
                }
                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: android.net.LinkProperties
                ) = handleLinkPropertiesChanged(network, linkProperties)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = publishCurrent()
                override fun onLost(network: Network) = handleNetworkLost(network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    cacheSsidFromCaps(network, caps)
                    publishCurrent()
                }
                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: android.net.LinkProperties
                ) = handleLinkPropertiesChanged(network, linkProperties)
            }
        }
    }

    private fun handleNetworkLost(network: Network) {
        val handle = network.networkHandle
        cachedSsidByHandle.remove(handle)
        verifiedCameraHandles.remove(handle)
        probeInFlightHandles.remove(handle)
        publishCurrent()
    }

    private fun handleLinkPropertiesChanged(
        network: Network,
        linkProperties: android.net.LinkProperties
    ) {
        val hasCamSubnet = linkProperties.linkAddresses.any {
            val addr = it.address
            addr is java.net.Inet4Address && addr.hostAddress?.startsWith("192.168.1.") == true
        }
        if (hasCamSubnet && verifiedCameraHandles[network.networkHandle] == false) {
            verifiedCameraHandles.remove(network.networkHandle)
        }
        publishCurrent()
    }

    private fun cacheSsidFromCaps(network: Network, caps: NetworkCapabilities) {
        if (Build.VERSION.SDK_INT >= 29 && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val info = caps.transportInfo as? WifiInfo
            val clean = sanitizeSsid(info?.ssid)
            if (!clean.isNullOrBlank()) {
                cachedSsidByHandle[network.networkHandle] = clean
            }
        }
    }

    private fun sanitizeSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().removeSurrounding("\"")
        if (cleaned.isBlank() ||
            cleaned.equals("<unknown ssid>", ignoreCase = true) ||
            cleaned.equals("0x", ignoreCase = true) ||
            cleaned.equals("null", ignoreCase = true)
        ) {
            return null
        }
        return cleaned
    }

    @SuppressLint("MissingPermission")
    private fun publishCurrent() {
        val ctx = appCtx ?: return
        val cmLocal = cm ?: (ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?: return
        val prefName = ctx.getString(R.string.pref_application)
        val pref = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val prefix = pref.getString("camera_ssid_prefix", DEFAULT_PREFIX)?.trim().orEmpty()
            .ifBlank { DEFAULT_PREFIX }

        val inspection = inspectWifiState(ctx, cmLocal, prefix, triggerPassiveProbe = true)
        val ssid = normalizeSsid(inspection.displaySsid) ?: inspection.displaySsid

        val status = WifiStatus(
            ssid = ssid,
            isCamera = inspection.cameraNetwork != null || inspection.matchedBySsidWithoutNet,
            isWifiConnected = inspection.hasAnyWifi
        )
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

    private data class WifiInspection(
        val cameraNetwork: Network?,
        val displaySsid: String?,
        val hasAnyWifi: Boolean,
        val matchedBySsidWithoutNet: Boolean = false
    )

    @SuppressLint("MissingPermission")
    private fun inspectWifiState(
        ctx: Context,
        cmLocal: ConnectivityManager,
        prefix: String,
        triggerPassiveProbe: Boolean
    ): WifiInspection {
        var hasAnyWifi = false
        var fallbackWifiSsid: String? = null
        var firstWifiNet: Network? = null

        val allNets = cmLocal.allNetworks
        for (net in allNets) {
            val caps = cmLocal.getNetworkCapabilities(net) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue

            hasAnyWifi = true
            if (firstWifiNet == null) firstWifiNet = net

            cacheSsidFromCaps(net, caps)
            val netSsid = extractSsidForNetwork(net, caps)
            if (fallbackWifiSsid == null && !netSsid.isNullOrBlank()) {
                fallbackWifiSsid = netSsid
            }

            if (isCameraSsid(netSsid, prefix)) {
                return WifiInspection(net, netSsid, true)
            }

            val handle = net.networkHandle
            val cachedVerify = verifiedCameraHandles[handle]
            if (WifiCamConnection.network == net && cachedVerify != false) {
                return WifiInspection(net, netSsid ?: "Kamera MS2", true)
            }
            if (cachedVerify == true) {
                return WifiInspection(net, netSsid ?: "Kamera MS2", true)
            }

            if (triggerPassiveProbe && cachedVerify == null &&
                (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) || hasSubnet1921681(cmLocal, net))
            ) {
                scheduleProbeIfNeeded(cmLocal, net)
            }
        }

        val wmLocal = wm ?: (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        val wmSsid = if (wmLocal?.isWifiEnabled == true) {
            @Suppress("DEPRECATION")
            val info = wmLocal.connectionInfo
            if (info != null && info.networkId != -1) sanitizeSsid(info.ssid) else null
        } else {
            null
        }

        if (!wmSsid.isNullOrBlank()) {
            hasAnyWifi = true
            if (isCameraSsid(wmSsid, prefix)) {
                return WifiInspection(firstWifiNet, wmSsid, true, matchedBySsidWithoutNet = firstWifiNet == null)
            }
            if (fallbackWifiSsid == null) fallbackWifiSsid = wmSsid
        }

        // Handle held WifiCamConnection network even if OS has not listed it yet
        val held = WifiCamConnection.network
        if (held != null) {
            val heldCaps = cmLocal.getNetworkCapabilities(held)
            if (heldCaps != null && heldCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val heldSsid = extractSsidForNetwork(held, heldCaps) ?: wmSsid ?: "Kamera MS2"
                return WifiInspection(held, heldSsid, true)
            }
        }

        return WifiInspection(null, fallbackWifiSsid, hasAnyWifi)
    }

    private fun extractSsidForNetwork(network: Network, caps: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT >= 29) {
            val direct = sanitizeSsid((caps.transportInfo as? WifiInfo)?.ssid)
            if (!direct.isNullOrBlank()) return direct
        }
        return cachedSsidByHandle[network.networkHandle]
    }

    private fun hasSubnet1921681(cmLocal: ConnectivityManager, network: Network): Boolean {
        val lp = cmLocal.getLinkProperties(network) ?: return false
        val hasAddr = lp.linkAddresses.any {
            val addr = it.address
            addr is java.net.Inet4Address && addr.hostAddress?.startsWith("192.168.1.") == true
        }
        if (hasAddr) return true
        return lp.dhcpServerAddress?.hostAddress == CAMERA_HOST
    }

    private fun scheduleProbeIfNeeded(cmLocal: ConnectivityManager, network: Network) {
        val handle = network.networkHandle
        if (!probeInFlightHandles.add(handle)) return
        val hasExpectedSubnet = hasSubnet1921681(cmLocal, network)
        val ioScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ioScope.launch(Dispatchers.IO) {
            try {
                val ok = probeCameraRtspSync(network)
                if (ok) {
                    verifiedCameraHandles[handle] = true
                    publishCurrent()
                } else if (hasExpectedSubnet) {
                    verifiedCameraHandles[handle] = false
                }
            } finally {
                probeInFlightHandles.remove(handle)
            }
        }
    }

    private fun probeCameraRtspSync(network: Network): Boolean {
        return runCatching {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(java.net.InetSocketAddress(CAMERA_HOST, CAMERA_RTSP_PORT), 400)
                true
            }
        }.getOrDefault(false)
    }

    private fun currentSsidAndIsWifi(ctx: Context): Pair<String?, Boolean> {
        val cmLocal = cm ?: return null to false
        val prefName = ctx.getString(R.string.pref_application)
        val pref = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val prefix = pref.getString("camera_ssid_prefix", DEFAULT_PREFIX)?.trim().orEmpty()
            .ifBlank { DEFAULT_PREFIX }
        val inspection = inspectWifiState(ctx, cmLocal, prefix, triggerPassiveProbe = false)
        return inspection.displaySsid to inspection.hasAnyWifi
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