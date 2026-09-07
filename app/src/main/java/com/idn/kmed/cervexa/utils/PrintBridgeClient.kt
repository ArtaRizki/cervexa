package com.idn.kmed.cervexa.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.idn.kmed.cervexa.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class PrintBridgeStatus(
    val isReady: Boolean,
    val defaultPrinter: String,
    val availablePrinters: List<String>,
    val rawMessage: String,
    val transportName: String = "Jaringan"
)

object PrintBridgeClient {

    private const val TAG = "PrintBridgeClient"
    const val DEFAULT_PORT = 9123
    const val PREF_KEY_BRIDGE_ENABLED = "print_bridge_enabled"
    const val PREF_KEY_BRIDGE_HOST = "print_bridge_host"

    data class NetworkRoute(
        val network: Network?,
        val transportName: String
    )

    /**
     * Mencari interface jaringan terbaik untuk berkomunikasi dengan Print Bridge:
     * 1. Kabel LAN (TRANSPORT_ETHERNET) -> Jika Smart TV dicolok kabel LAN (Mode A Dual-Network)
     * 2. Wi-Fi (TRANSPORT_WIFI) -> Jika Smart TV terhubung ke Wi-Fi klinik/router (Mode B Single Wi-Fi)
     * 3. Jaringan Aktif Sistem -> Fallback ke default active network
     */
    fun findTargetNetwork(context: Context): NetworkRoute {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkRoute(null, "Tidak ada Network Service")

        return runCatching {
            val networks = cm.allNetworks
            var ethNet: Network? = null
            var wifiNet: Network? = null

            for (net in networks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    if (ethNet == null) ethNet = net
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    if (wifiNet == null) wifiNet = net
                }
            }

            // Prioritas 1: Kabel LAN / Ethernet (Mode A)
            if (ethNet != null) {
                return@runCatching NetworkRoute(ethNet, "LAN/Ethernet")
            }

            // Prioritas 2: Network aktif saat ini jika bertransport WiFi (Mode B)
            val active = cm.activeNetwork
            if (active != null) {
                val caps = cm.getNetworkCapabilities(active)
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return@runCatching NetworkRoute(active, "WiFi")
                }
            }

            // Prioritas 3: Wi-Fi manapun yang terdeteksi (termasuk jaringan lokal tanpa internet)
            if (wifiNet != null) {
                return@runCatching NetworkRoute(wifiNet, "WiFi")
            }

            // Prioritas 4: Active network apapun
            if (active != null) {
                return@runCatching NetworkRoute(active, "Jaringan Aktif")
            }

            NetworkRoute(null, "Tidak Terhubung")
        }.getOrElse {
            NetworkRoute(null, "Tidak Terhubung")
        }
    }

    /**
     * Nama jalur aktif yang dipakai (untuk tampilan UI diagnosis pengguna).
     */
    fun getActiveTransportName(context: Context): String {
        return findTargetNetwork(context).transportName
    }

    /**
     * Membangun OkHttpClient yang terikat ke target network (Ethernet atau WiFi klinik).
     *
     * PENTING: Memanggil cm.bindProcessToNetwork(null) terlebih dahulu untuk membersihkan
     * binding lama ke kamera MS2. Jika proses masih terikat ke netId kamera yang sudah ditutup,
     * pembuatan socket baru akan dilempar error "ENONET (Machine is not on the network)".
     */
    private fun clientFor(context: Context): Pair<OkHttpClient, String> {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val route = findTargetNetwork(context)

        // 1. Bersihkan process binding lama dari kamera MS2 agar libc socket tidak ENONET
        runCatching {
            cm?.bindProcessToNetwork(null)
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))

        // 2. Jika ada network target (Ethernet atau WiFi klinik), bind process & socketFactory ke network tersebut
        if (route.network != null && cm != null) {
            Log.i(TAG, "Mengikat PrintBridgeClient ke jalur: ${route.transportName} (${route.network})")
            runCatching {
                cm.bindProcessToNetwork(route.network)
            }
            runCatching {
                builder.socketFactory(route.network.socketFactory)
            }
            runCatching {
                builder.dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<java.net.InetAddress> {
                        return route.network.getAllByName(hostname).toList()
                    }
                })
            }
        } else {
            Log.w(TAG, "Tidak ada network target spesifik, menggunakan routing default sistem")
        }

        return Pair(builder.build(), route.transportName)
    }

    /**
     * Header HTTP hanya boleh berisi karakter ASCII 0x20..0x7E.
     * Karakter seperti em dash "—" (0x2014) menyebabkan OkHttp melempar
     * IllegalArgumentException. Fungsi ini mengonversi karakter non-ASCII menjadi "-".
     */
    private fun sanitizeHeaderValue(value: String): String =
        value.map { c -> if (c.code in 0x20..0x7E) c else '-' }.joinToString("")

    fun isBridgeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(context.getString(R.string.pref_application), Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_KEY_BRIDGE_ENABLED, false)
    }

    fun setBridgeEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(context.getString(R.string.pref_application), Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_KEY_BRIDGE_ENABLED, enabled).apply()
    }

    fun getBridgeHost(context: Context): String {
        val prefs = context.getSharedPreferences(context.getString(R.string.pref_application), Context.MODE_PRIVATE)
        return prefs.getString(PREF_KEY_BRIDGE_HOST, "")?.trim().orEmpty()
    }

    fun setBridgeHost(context: Context, host: String) {
        val prefs = context.getSharedPreferences(context.getString(R.string.pref_application), Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_KEY_BRIDGE_HOST, cleanHost(host)).apply()
    }

    fun cleanHost(rawHost: String): String {
        var h = rawHost.trim()
        if (h.isEmpty()) return ""
        if (!h.startsWith("http://", ignoreCase = true) && !h.startsWith("https://", ignoreCase = true)) {
            h = "http://$h"
        }
        h = h.trimEnd('/')
        val uriPart = h.substringAfter("://")
        if (!uriPart.contains(":")) {
            h = "$h:$DEFAULT_PORT"
        }
        return h
    }

    /**
     * Memeriksa kesiapan Print Bridge Server dan mendeteksi printer yang tersedia.
     */
    suspend fun checkStatus(context: Context, host: String): Result<PrintBridgeStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val formatted = cleanHost(host)
            if (formatted.isEmpty()) throw IllegalArgumentException("Alamat IP Print Bridge belum diisi")

            val url = "$formatted/status"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val (client, transport) = clientFor(context)

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw Exception("Server merespons error HTTP ${response.code}: $bodyStr")
                }

                val json = JSONObject(bodyStr)
                val status = json.optString("status", "unknown")
                val isReady = status.equals("ready", ignoreCase = true)
                val defaultPrinter = json.optString("default_printer", "Default Printer")
                val printersArray = json.optJSONArray("available_printers")
                val printers = mutableListOf<String>()
                if (printersArray != null) {
                    for (i in 0 until printersArray.length()) {
                        printers.add(printersArray.getString(i))
                    }
                }

                PrintBridgeStatus(
                    isReady = isReady,
                    defaultPrinter = defaultPrinter,
                    availablePrinters = printers,
                    rawMessage = bodyStr,
                    transportName = transport
                )
            }
        }
    }

    /**
     * Mengirim berkas PDF langsung ke Print Bridge untuk dicetak ke printer default/terpilih.
     */
    suspend fun sendPrintJob(
        context: Context,
        host: String,
        pdfFile: File,
        jobTitle: String = "Cervexa Rekam Medis",
        printerName: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val formatted = cleanHost(host)
            if (formatted.isEmpty()) throw IllegalArgumentException("Alamat IP Print Bridge belum diisi")
            if (!pdfFile.exists() || pdfFile.length() == 0L) throw IllegalArgumentException("Berkas PDF tidak ditemukan atau kosong")

            val url = "$formatted/print"
            val mediaType = "application/pdf".toMediaTypeOrNull()
            val requestBody = pdfFile.asRequestBody(mediaType)

            val reqBuilder = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/pdf")
                .addHeader("X-Job-Title", sanitizeHeaderValue(jobTitle))

            if (!printerName.isNullOrBlank()) {
                reqBuilder.addHeader("X-Printer-Name", sanitizeHeaderValue(printerName))
            }

            val (client, _) = clientFor(context)

            client.newCall(reqBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errMsg = try {
                        JSONObject(bodyStr).optString("message", "HTTP ${response.code}")
                    } catch (_: Exception) {
                        "HTTP ${response.code} $bodyStr"
                    }
                    throw Exception(errMsg)
                }

                try {
                    val json = JSONObject(bodyStr)
                    json.optString("message", "Pekerjaan cetak berhasil dikirim")
                } catch (_: Exception) {
                    "Pekerjaan cetak berhasil dikirim"
                }
            }
        }
    }
}

