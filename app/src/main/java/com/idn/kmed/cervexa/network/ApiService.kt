package com.idn.kmed.cervexa.network

import com.idn.kmed.cervexa.network.dto.CompleteSessionRequest
import com.idn.kmed.cervexa.network.dto.LoginRequest
import com.idn.kmed.cervexa.network.dto.LoginResponse
import com.idn.kmed.cervexa.network.dto.MediaResponse
import com.idn.kmed.cervexa.network.dto.MessageResponse
import com.idn.kmed.cervexa.network.dto.PatientLookupResponse
import com.idn.kmed.cervexa.network.dto.PatientResponse
import com.idn.kmed.cervexa.network.dto.SessionResponse
import com.idn.kmed.cervexa.network.dto.StorePatientRequest
import com.idn.kmed.cervexa.network.dto.StoreSessionRequest
import com.idn.kmed.cervexa.network.dto.UserDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // ── Auth ───────────────────────────────────────────────────────────────

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<MessageResponse>

    @GET("auth/me")
    suspend fun me(): Response<UserDto>

    // ── Patients ───────────────────────────────────────────────────────────

    /**
     * Cek apakah NIK sudah terdaftar sebelum registrasi.
     * GET /api/patients/lookup?nik=xxxx
     */
    @GET("patients/lookup")
    suspend fun lookupPatient(@Query("nik") nik: String): Response<PatientLookupResponse>

    /** POST /api/patients — daftarkan pasien baru */
    @POST("patients")
    suspend fun storePatient(@Body body: StorePatientRequest): Response<PatientResponse>

    /** GET /api/patients/{id} */
    @GET("patients/{id}")
    suspend fun getPatient(@Path("id") id: Int): Response<PatientResponse>

    // ── Sessions ───────────────────────────────────────────────────────────

    /** POST /api/sessions — buat sesi baru saat VideoActivity dimulai */
    @POST("sessions")
    suspend fun storeSession(@Body body: StoreSessionRequest): Response<SessionResponse>

    /** GET /api/sessions/{id} */
    @GET("sessions/{id}")
    suspend fun getSession(@Path("id") id: Int): Response<SessionResponse>

    /** PATCH /api/sessions/{id}/complete — selesaikan sesi */
    @PATCH("sessions/{id}/complete")
    suspend fun completeSession(
        @Path("id") id: Int,
        @Body body: CompleteSessionRequest = CompleteSessionRequest()
    ): Response<SessionResponse>

    // ── Media ──────────────────────────────────────────────────────────────

    /**
     * POST /api/sessions/{sessionId}/media
     * Upload snapshot (JPG) atau video (MP4) via multipart.
     *
     * @param file     Part "file" — binary content
     * @param type     Part "type" — "snapshot" atau "video"
     * @param takenAt  Part "taken_at" — epoch-ms saat capture (opsional)
     */
    @Multipart
    @POST("sessions/{sessionId}/media")
    suspend fun uploadMedia(
        @Path("sessionId") sessionId: Int,
        @Part file: MultipartBody.Part,
        @Part("type") type: RequestBody,
        @Part("taken_at") takenAt: RequestBody? = null
    ): Response<MediaResponse>

    /** DELETE /api/media/{mediaId} */
    @DELETE("media/{mediaId}")
    suspend fun deleteMedia(@Path("mediaId") mediaId: Int): Response<MessageResponse>
}