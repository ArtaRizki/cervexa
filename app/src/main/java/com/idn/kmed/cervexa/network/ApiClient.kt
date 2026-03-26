package com.idn.kmed.cervexa.network

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    /**
     * Ganti dengan base URL server Laravel kamu.
     * Contoh: "http://192.168.1.100:8000/api/"  (dev, same LAN)
     *         "https://cervexa.yourdomain.com/api/"  (production)
     *
     * WAJIB diakhiri dengan slash "/"
     */
//    const val BASE_URL = "http://192.168.1.100:8000/api/"
    const val BASE_URL = "http://10.0.2.2:8000/api/"

    @Volatile
    private var INSTANCE: ApiService? = null

    fun getInstance(context: Context): ApiService =
        INSTANCE ?: synchronized(this) {
            INSTANCE ?: buildService(context).also { INSTANCE = it }
        }

    /** Paksa rebuild service (misal: setelah login / logout agar token di-inject ulang) */
    fun reset() {
        INSTANCE = null
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun buildService(context: Context): ApiService {
        val tokenManager = TokenManager.getInstance(context)

        val logging = HttpLoggingInterceptor().apply {
            level = if (com.idn.kmed.cervexa.BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BODY
            else
                HttpLoggingInterceptor.Level.NONE
        }

        val okHttp = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)    // video upload bisa lama
            .writeTimeout(120, TimeUnit.SECONDS)
            // Sisipkan Bearer token di setiap request
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                    .header("Accept", "application/json")
                tokenManager.token?.let { builder.header("Authorization", "Bearer $it") }
                chain.proceed(builder.build())
            }
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}