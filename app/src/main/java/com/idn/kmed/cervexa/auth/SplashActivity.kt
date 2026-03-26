package com.idn.kmed.cervexa.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.idn.kmed.cervexa.home.HomeActivity
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.network.TokenManager

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefApps =
            getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
        val onBoardingDone = prefApps.getBoolean("on_boarding", false)
        val tokenManager = TokenManager.getInstance(this)

        Handler(Looper.getMainLooper()).postDelayed({
            val next: Intent = when {
                // Onboarding belum selesai → tampil onboarding dulu
                !onBoardingDone -> Intent(this, OnboardingActivity::class.java)

                // Onboarding sudah, tapi belum login → wajib login
                !tokenManager.isLoggedIn -> Intent(this, LoginActivity::class.java)

                // Sudah login → langsung ke Home
                else -> Intent(this, HomeActivity::class.java)
            }
            startActivity(next)
            finish()
        }, 2_000L)
    }
}