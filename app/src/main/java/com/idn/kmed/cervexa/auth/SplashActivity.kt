package com.idn.kmed.cervexa.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.idn.kmed.cervexa.home.HomeActivity
import com.idn.kmed.cervexa.R

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefApps =
            getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
        val onBoardingDone = prefApps.getBoolean("on_boarding", false)

        Handler(Looper.getMainLooper()).postDelayed({
            val next: Intent = if (onBoardingDone) {
                Intent(this, HomeActivity::class.java)
            } else {
                Intent(this, OnboardingActivity::class.java)
            }
            startActivity(next)
            finish()
        }, 2_000L)
    }
}