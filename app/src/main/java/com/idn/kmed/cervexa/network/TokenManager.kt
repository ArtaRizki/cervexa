package com.idn.kmed.cervexa.network

import android.content.Context
import android.content.SharedPreferences

/**
 * Menyimpan & membaca Bearer token Sanctum di SharedPreferences.
 * Gunakan singleton via [TokenManager.getInstance].
 */
class TokenManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var userId: Int
        get() = prefs.getInt(KEY_USER_ID, -1)
        set(value) = prefs.edit().putInt(KEY_USER_ID, value).apply()

    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    var hospitalName: String?
        get() = prefs.getString(KEY_HOSPITAL_NAME, null)
        set(value) = prefs.edit().putString(KEY_HOSPITAL_NAME, value).apply()

    var userRole: String?
        get() = prefs.getString(KEY_USER_ROLE, null)
        set(value) = prefs.edit().putString(KEY_USER_ROLE, value).apply()

    val isLoggedIn: Boolean get() = token != null

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val PREF_NAME = "cervexa_auth"
        private const val KEY_TOKEN = "bearer_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_HOSPITAL_NAME = "hospital_name"
        private const val KEY_USER_ROLE = "user_role"

        @Volatile
        private var INSTANCE: TokenManager? = null

        fun getInstance(context: Context): TokenManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TokenManager(context).also { INSTANCE = it }
            }
    }
}