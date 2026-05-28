package com.idn.kmed.cervexa.ml

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the AI analysis mode activation state.
 *
 * Provides a reactive [StateFlow] of the current active/inactive status,
 * along with methods to toggle, activate, deactivate, persist, and restore
 * the state via [SharedPreferences].
 *
 * SharedPreferences key: [PREF_KEY_ACTIVE] (Boolean, default: false)
 */
class AnalysisModeManager(private val prefs: SharedPreferences) {

    private val _isActive = MutableStateFlow(false)

    /** Observable state of whether AI analysis mode is currently active. */
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    /** Toggles the analysis mode between active and inactive. */
    fun toggle() {
        _isActive.value = !_isActive.value
    }

    /** Activates the analysis mode. */
    fun activate() {
        _isActive.value = true
    }

    /** Deactivates the analysis mode. */
    fun deactivate() {
        _isActive.value = false
    }

    /**
     * Persists the current analysis mode state to SharedPreferences.
     * Should be called in onPause() or when the user leaves the screen.
     */
    fun persist() {
        prefs.edit().putBoolean(PREF_KEY_ACTIVE, _isActive.value).apply()
    }

    /**
     * Restores the analysis mode state from SharedPreferences.
     * Should be called in onResume() or when the screen is re-entered.
     */
    fun restore() {
        _isActive.value = prefs.getBoolean(PREF_KEY_ACTIVE, DEFAULT_VALUE)
    }

    companion object {
        const val PREF_KEY_ACTIVE = "ai_analysis_mode_active"
        private const val DEFAULT_VALUE = false
    }
}
