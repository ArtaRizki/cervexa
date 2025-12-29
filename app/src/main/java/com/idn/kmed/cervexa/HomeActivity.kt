package com.idn.kmed.cervexa

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.idn.kmed.cervexa.media.MediaListFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.idn.kmed.cervexa.utils.WifiMonitor

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_system_info -> {
                    startActivity(Intent(this, SystemInfoActivity::class.java))
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }

        // Onboarding check (tetap di Activity)
        val prefs = getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
        if (!prefs.getBoolean("on_boarding", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish(); return
        }

        val bottom = findViewById<BottomNavigationView>(R.id.nav_view)

        val openTab = intent.getStringExtra("open_tab")
        if (openTab == "media") {
            showFragment(MediaListFragment())
            bottom.selectedItemId = R.id.navigation_media
        }else{
            // default tab: Beranda
            if (savedInstanceState == null) {
                showFragment(HomeDashboardFragment())
                bottom.selectedItemId = R.id.navigation_home
            }
        }

        bottom.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> {
                    showFragment(HomeDashboardFragment())
                    true
                }
                R.id.navigation_media -> {
                    // Pakai fragment media milikmu
                    showFragment(MediaListFragment())
                    true
                }
                else -> false
            }
        }


    }

    private fun showFragment(f: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.navHost, f)
            .commit()
    }

    // Tetap forward permission result ke WifiMonitor
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        WifiMonitor.handlePermissionResult(requestCode, grantResults, this)
    }
}