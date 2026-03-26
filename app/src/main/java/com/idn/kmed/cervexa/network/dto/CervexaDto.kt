package com.idn.kmed.cervexa.network.dto

import com.google.gson.annotations.SerializedName

// ─── Auth ────────────────────────────────────────────────────────────────────

data class LoginRequest(
    val email: String,
    val password: String
)

data class LoginResponse(
    val token: String,
    val user: UserDto
)

data class UserDto(
    val id: Int,
    val name: String,
    val email: String,
    val role: String? = null,
    @SerializedName("hospital_name") val hospitalName: String? = null
)

// ─── Patient ─────────────────────────────────────────────────────────────────

data class StorePatientRequest(
    val nama: String,
    val nik: String,
    @SerializedName("hospital_name") val hospitalName: String,
    val nrm: String?,
    @SerializedName("dob_utc") val dobUtc: Long?         // epoch-ms
)

data class PatientDto(
    val id: Int,
    val nama: String,
    val nik: String,
    @SerializedName("hospital_name") val hospitalName: String?,
    val nrm: String?,
    val age: Int?,
    val dob: String?,
    @SerializedName("sessions_count") val sessionsCount: Int?,
    @SerializedName("created_at") val createdAt: String?
)

data class PatientResponse(
    val message: String,
    val data: PatientDto
)

data class PatientLookupResponse(
    val found: Boolean,
    val data: PatientDto?
)

// ─── Session ─────────────────────────────────────────────────────────────────

data class StoreSessionRequest(
    @SerializedName("patient_id") val patientId: Int,
    @SerializedName("hospital_name") val hospitalName: String?,
    val notes: String? = null
)

data class SessionDto(
    val id: Int,
    @SerializedName("session_code") val sessionCode: String,
    @SerializedName("patient_id") val patientId: Int,
    val status: String,              // active | completed | archived
    @SerializedName("hospital_name") val hospitalName: String?,
    val notes: String?,
    @SerializedName("started_at") val startedAt: String?,
    @SerializedName("completed_at") val completedAt: String?,
    @SerializedName("media_count") val mediaCount: Int?,
    @SerializedName("created_at") val createdAt: String?
)

data class SessionResponse(
    val message: String,
    val data: SessionDto
)

data class CompleteSessionRequest(
    val notes: String? = null,
    val metadata: Map<String, Any>? = null
)

// ─── Media ───────────────────────────────────────────────────────────────────

data class MediaDto(
    val id: Int,
    val type: String,                // snapshot | video
    @SerializedName("original_name") val originalName: String,
    @SerializedName("public_url") val publicUrl: String,
    @SerializedName("file_size") val fileSize: Long,
    @SerializedName("mime_type") val mimeType: String,
    @SerializedName("created_at") val createdAt: String?
)

data class MediaResponse(
    val message: String,
    val data: MediaDto
)

// ─── Generic wrapper ─────────────────────────────────────────────────────────

data class MessageResponse(val message: String)

data class ApiError(
    val message: String,
    val errors: Map<String, List<String>>? = null
)