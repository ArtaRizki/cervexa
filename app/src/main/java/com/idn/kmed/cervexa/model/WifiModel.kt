package com.idn.kmed.cervexa.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.idn.kmed.cervexa.utils.WifiMonitor.WifiStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel untuk memaparkan status Wi-Fi ke UI.
 *
 * Sumber data utama: WifiMonitor.setOnStatusChanged { status -> updateStatus(status) }
 *
 * - statusFlow: WifiStatus(ssid, isCamera) → dipakai UI untuk render lengkap.
 * - isCameraFlow: Boolean → true bila saat ini di Wi-Fi kamera (sesuai prefs).
 * - ssidFlow: String? → kompat untuk kode lama yang hanya butuh SSID.
 */
class WifiViewModel : ViewModel() {

    // State utama yang ditulis dari WifiMonitor
    private val _statusFlow = MutableStateFlow(WifiStatus(ssid = null, isCamera = false))
    val statusFlow: StateFlow<WifiStatus> = _statusFlow.asStateFlow()

    // Derived states (convenience)
    val isCameraFlow: StateFlow<Boolean> =
        statusFlow.map { it.isCamera }
            .stateIn(viewModelScope, started = kotlinx.coroutines.flow.SharingStarted.Eagerly, initialValue = false)

    val ssidFlow: StateFlow<String?> =
        statusFlow.map { it.ssid }
            .stateIn(viewModelScope, started = kotlinx.coroutines.flow.SharingStarted.Eagerly, initialValue = null)

    /** Dipanggil oleh WifiMonitor.setOnStatusChanged */
    fun updateStatus(status: WifiStatus) {
        _statusFlow.value = status
    }

    /** Kompat lama: jika ada kode lama yang masih kirim SSID saja. */
    @Deprecated("Gunakan updateStatus(WifiStatus) dari WifiMonitor")
    fun updateSsid(ssid: String?, isCamera: Boolean = false) {
        _statusFlow.value = WifiStatus(ssid = ssid, isCamera = isCamera)
    }

    /** Helper opsional untuk reset status (misal saat app logout / disconnect). */
    fun reset() {
        _statusFlow.value = WifiStatus(ssid = null, isCamera = false)
    }

    /** Helper opsional: set flag kamera saja tanpa mengubah SSID. */
    fun setIsCamera(value: Boolean) {
        val cur = _statusFlow.value
        _statusFlow.value = cur.copy(isCamera = value)
    }

    /** Helper opsional: set SSID saja tanpa mengubah flag kamera. */
    fun setSsid(ssid: String?) {
        val cur = _statusFlow.value
        _statusFlow.value = cur.copy(ssid = ssid)
    }

    /** Contoh cara push dari luar dengan aman via coroutine (tidak wajib). */
    fun post(status: WifiStatus) = viewModelScope.launch {
        _statusFlow.emit(status)
    }
}
