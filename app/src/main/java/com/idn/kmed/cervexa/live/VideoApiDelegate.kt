package com.idn.kmed.cervexa.live

import android.content.Context
import android.util.Log
import com.idn.kmed.cervexa.network.ApiResult
import com.idn.kmed.cervexa.network.CervexaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Delegate yang mengelola semua panggilan API untuk satu sesi pemeriksaan.
 *
 * Cara pakai di VideoFragmentTv / VideoFragmentMobile:
 *
 *   private val apiDelegate by lazy { VideoApiDelegate(requireContext(), lifecycleScope) }
 *
 *   // Di onCreate / onStart:
 *   apiDelegate.createSession(patientId, hospitalName)
 *
 *   // Setelah snapshot tersimpan lokal:
 *   apiDelegate.uploadSnapshot(file)
 *
 *   // Setelah video recording selesai:
 *   apiDelegate.uploadVideo(file)
 *
 *   // Di showSaveConfirmDialog → onConfirm:
 *   apiDelegate.completeSession(notes) { finish() }
 */
class VideoApiDelegate(
    context: Context,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit = {}   // callback untuk tampil Toast/Snackbar
) {
    private val repo = CervexaRepository.getInstance(context)

    /** ID sesi dari server. -1 = belum dibuat / offline. */
    var serverSessionId: Int = -1
        private set

    // ─── Session lifecycle ──────────────────────────────────────────────────

    /**
     * Panggil sekali di awal VideoFragment setelah patient_id tersedia dari Intent.
     * [patientId] = -1 → mode offline, tidak ada panggilan API.
     */
    fun createSession(patientId: Int, hospitalName: String?) {
        if (patientId <= 0) {
            Log.w(TAG, "patientId tidak valid ($patientId) — skip createSession")
            return
        }
        scope.launch {
            when (val r = repo.storeSession(patientId, hospitalName)) {
                is ApiResult.Success -> {
                    serverSessionId = r.data.id
                    Log.i(TAG, "Sesi dibuat: sessionId=$serverSessionId code=${r.data.sessionCode}")
                }

                is ApiResult.Error -> {
                    Log.e(TAG, "Gagal buat sesi: ${r.message}")
                    // Tidak blokir alur — mode offline tetap berjalan
                    onError("Sesi tidak tersinkron ke server: ${r.message}")
                }
            }
        }
    }

    /**
     * Tandai sesi selesai. Panggil saat user konfirmasi "Simpan".
     * [onDone] dipanggil setelah request selesai (sukses maupun gagal) agar
     * Activity/Fragment bisa lanjut menutup layar.
     */
    fun completeSession(notes: String? = null, onDone: () -> Unit = {}) {
        if (serverSessionId <= 0) {
            onDone(); return
        }
        scope.launch {
            when (val r = repo.completeSession(serverSessionId, notes)) {
                is ApiResult.Success -> Log.i(TAG, "Sesi selesai: ${r.data.sessionCode}")
                is ApiResult.Error -> Log.e(TAG, "completeSession error: ${r.message}")
            }
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    // ─── Media upload ───────────────────────────────────────────────────────

    /**
     * Upload snapshot JPG ke server secara background.
     * Kegagalan hanya di-log (tidak blokir UI).
     */
    fun uploadSnapshot(file: File, takenAtMs: Long = System.currentTimeMillis()) {
        uploadMedia(file, "snapshot", takenAtMs)
    }

    /**
     * Upload video MP4 ke server secara background.
     * Biasanya dipanggil setelah [stopVideoRecording] selesai.
     */
    fun uploadVideo(file: File, takenAtMs: Long = System.currentTimeMillis()) {
        uploadMedia(file, "video", takenAtMs)
    }

    // ─── Private ────────────────────────────────────────────────────────────

    private fun uploadMedia(file: File, type: String, takenAtMs: Long) {
        if (serverSessionId <= 0) {
            Log.w(TAG, "Upload $type dilewati — serverSessionId belum ada")
            return
        }
        scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Mulai upload $type: ${file.name} (${file.length() / 1024} KB)")
            when (val r = repo.uploadMedia(serverSessionId, file, type, takenAtMs)) {
                is ApiResult.Success ->
                    Log.i(TAG, "Upload berhasil: mediaId=${r.data.id} url=${r.data.publicUrl}")

                is ApiResult.Error ->
                    Log.e(TAG, "Upload $type gagal: ${r.message}")
            }
        }
    }

    companion object {
        private const val TAG = "VideoApiDelegate"
    }
}