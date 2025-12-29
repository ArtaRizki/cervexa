package com.idn.kmed.cervexa

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ❌ Jangan pakai setContentView() di sini
        // setContentView(R.layout.activity_splash)

        val prefApps = getSharedPreferences(getString(R.string.pref_application), Context.MODE_PRIVATE)
        val onBoarding = prefApps.getBoolean("on_boarding", false)

        Handler(Looper.getMainLooper()).postDelayed({
            if (onBoarding) {
                startActivity(Intent(this, HomeActivity::class.java))
            } else {
                startActivity(Intent(this, OnboardingActivity::class.java))
            }
            finish()
        }, 3000)
    }
}
