package com.idn.kmed.cervexa.utils

import android.content.Context
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
    val rawMessage: String
)

object PrintBridgeClient {

    const val DEFAULT_PORT = 9123
    const val PREF_KEY_BRIDGE_ENABLED = "print_bridge_enabled"
    const val PREF_KEY_BRIDGE_HOST = "print_bridge_host"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

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
        // Jika belum ada port, tambahkan default port 9123
        val uriPart = h.substringAfter("://")
        if (!uriPart.contains(":")) {
            h = "$h:$DEFAULT_PORT"
        }
        return h
    }

    /**
     * Memeriksa kesiapan Print Bridge Server dan mendeteksi printer yang tersedia.
     */
    suspend fun checkStatus(host: String): Result<PrintBridgeStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val formatted = cleanHost(host)
            if (formatted.isEmpty()) throw IllegalArgumentException("Alamat IP Print Bridge belum diisi")

            val url = "$formatted/status"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

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
                    rawMessage = bodyStr
                )
            }
        }
    }

    /**
     * Mengirim berkas PDF langsung ke Print Bridge untuk dicetak ke printer default/terpilih.
     */
    suspend fun sendPrintJob(
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
                .addHeader("X-Job-Title", jobTitle)

            if (!printerName.isNullOrBlank()) {
                reqBuilder.addHeader("X-Printer-Name", printerName)
            }

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
