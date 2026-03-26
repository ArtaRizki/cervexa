package com.idn.kmed.cervexa.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.idn.kmed.cervexa.network.dto.ApiError
import com.idn.kmed.cervexa.network.dto.CompleteSessionRequest
import com.idn.kmed.cervexa.network.dto.LoginRequest
import com.idn.kmed.cervexa.network.dto.LoginResponse
import com.idn.kmed.cervexa.network.dto.MediaDto
import com.idn.kmed.cervexa.network.dto.MessageResponse
import com.idn.kmed.cervexa.network.dto.PatientDto
import com.idn.kmed.cervexa.network.dto.PatientLookupResponse
import com.idn.kmed.cervexa.network.dto.SessionDto
import com.idn.kmed.cervexa.network.dto.StorePatientRequest
import com.idn.kmed.cervexa.network.dto.StoreSessionRequest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File

/**
 * Satu-satunya entry point untuk semua panggilan API Cervexa.
 *
 * Setiap fungsi mengembalikan [ApiResult]:
 *  - [ApiResult.Success] berisi data yang sudah di-unwrap
 *  - [ApiResult.Error]   berisi pesan error yang siap ditampilkan ke user
 */
class CervexaRepository private constructor(
    private val api: ApiService,
    private val tokenManager: TokenManager
) {

    // ── Auth ───────────────────────────────────────────────────────────────

    suspend fun login(email: String, password: String): ApiResult<LoginResponse> =
        safeCall { api.login(LoginRequest(email, password)) }
            .onSuccessData { resp ->
                tokenManager.token = resp.token
                tokenManager.userName = resp.user.name
                tokenManager.userId = resp.user.id
                tokenManager.hospitalName = resp.user.hospitalName
                tokenManager.userRole = resp.user.role
            }

    suspend fun logout(): ApiResult<MessageResponse> =
        safeCall { api.logout() }.also { tokenManager.clear() }

    // ── Patients ───────────────────────────────────────────────────────────

    /** Cek NIK sebelum registrasi — tidak wajib, tapi bisa dipakai untuk UX. */
    suspend fun lookupPatient(nik: String): ApiResult<PatientLookupResponse> =
        safeCall { api.lookupPatient(nik) }

    /**
     * Daftarkan pasien baru; kembalikan PatientDto yang sudah punya [id].
     * Dipanggil dari RegistrationPatientActivity sebelum membuka VideoActivity.
     */
    suspend fun storePatient(
        nama: String,
        nik: String,
        hospitalName: String,
        nrm: String?,
        dobUtcMs: Long?
    ): ApiResult<PatientDto> =
        safeCall {
            api.storePatient(
                StorePatientRequest(
                    nama,
                    nik,
                    hospitalName,
                    nrm.takeIf { !it.isNullOrBlank() },
                    dobUtcMs
                )
            )
        }.mapData { it.data }

    // ── Sessions ───────────────────────────────────────────────────────────

    /**
     * Buat sesi pemeriksaan baru.
     * Dipanggil di awal VideoActivity / VideoFragment.
     */
    suspend fun storeSession(patientId: Int, hospitalName: String?): ApiResult<SessionDto> =
        safeCall {
            api.storeSession(StoreSessionRequest(patientId, hospitalName))
        }.mapData { it.data }

    /**
     * Tandai sesi sebagai selesai.
     * Dipanggil saat user menekan tombol "Simpan" di VideoFragment.
     */
    suspend fun completeSession(sessionId: Int, notes: String? = null): ApiResult<SessionDto> =
        safeCall {
            api.completeSession(sessionId, CompleteSessionRequest(notes))
        }.mapData { it.data }

    // ── Media ──────────────────────────────────────────────────────────────

    /**
     * Upload satu file snapshot (JPG) atau video (MP4).
     *
     * @param sessionId  ID sesi dari server (bukan folder lokal)
     * @param file       File lokal yang akan diupload
     * @param type       "snapshot" atau "video"
     * @param takenAtMs  Timestamp capture (epoch-ms); opsional
     */
    suspend fun uploadMedia(
        sessionId: Int,
        file: File,
        type: String,            // "snapshot" | "video"
        takenAtMs: Long? = null
    ): ApiResult<MediaDto> {
        val mimeType = if (type == "snapshot") "image/jpeg" else "video/mp4"

        val filePart = MultipartBody.Part.createFormData(
            "file",
            file.name,
            file.asRequestBody(mimeType.toMediaTypeOrNull())
        )
        val typePart = type.toRequestBody("text/plain".toMediaTypeOrNull())
        val tsPart = takenAtMs?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())

        return safeCall { api.uploadMedia(sessionId, filePart, typePart, tsPart) }
            .mapData { it.data }
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private suspend fun <T> safeCall(block: suspend () -> Response<T>): ApiResult<T> {
        return try {
            val response = block()
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) ApiResult.Success(body)
                else ApiResult.Error("Respons kosong dari server")
            } else {
                val errBody = response.errorBody()?.string()
                val msg = runCatching {
                    Gson().fromJson(errBody, ApiError::class.java).message
                }.getOrElse { "Error ${response.code()}" }
                Log.e(TAG, "API error ${response.code()}: $errBody")
                ApiResult.Error(msg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network exception", e)
            ApiResult.Error("Tidak dapat terhubung ke server: ${e.localizedMessage}")
        }
    }

    companion object {
        private const val TAG = "CervexaRepository"

        @Volatile
        private var INSTANCE: CervexaRepository? = null

        fun getInstance(context: Context): CervexaRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: CervexaRepository(
                    ApiClient.getInstance(context),
                    TokenManager.getInstance(context)
                ).also { INSTANCE = it }
            }
    }
}

// ─── Result wrapper ──────────────────────────────────────────────────────────

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String) : ApiResult<Nothing>()
}

/** Jalankan [block] hanya jika sukses; kembalikan ApiResult yang sama. */
inline fun <T> ApiResult<T>.onSuccessData(block: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Success) block(data)
    return this
}

/** Transformasi data jika sukses (seperti map pada functional style). */
inline fun <T, R> ApiResult<T>.mapData(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Error -> this
}