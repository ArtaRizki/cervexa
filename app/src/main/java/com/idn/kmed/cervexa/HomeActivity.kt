package com.idn.kmed.cervexa

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import com.idn.kmed.cervexa.media.MediaListFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.idn.kmed.cervexa.utils.WifiMonitor

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // ... (Kode toolbar tetap sama) ...
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_system_info -> { startActivity(Intent(this, SystemInfoActivity::class.java)); true }
                R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                else -> false
            }
        }

        // ... (Kode onboarding tetap sama) ...
        val prefs = getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
        if (!prefs.getBoolean("on_boarding", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish(); return
        }

        val bottom = findViewById<BottomNavigationView>(R.id.nav_view)

        // --- [TV OPTIMIZATION START] ---
        // 1. Setup agar setiap ICON bisa dipilih pakai remote
        setupBottomNavForRemote(bottom)

        // 2. Setup listener navigasi
        bottom.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> {
                    showFragment(HomeDashboardFragment())
                    true
                }
                R.id.navigation_media -> {
                    showFragment(MediaListFragment())
                    true
                }
                else -> false
            }
        }
        // --- [TV OPTIMIZATION END] ---

        val openTab = intent.getStringExtra("open_tab")
        if (openTab == "media") {
            showFragment(MediaListFragment())
            bottom.selectedItemId = R.id.navigation_media
        } else {
            if (savedInstanceState == null) {
                showFragment(HomeDashboardFragment())
                bottom.selectedItemId = R.id.navigation_home
            }
        }
    }

    // FUNGSI KHUSUS REMOTE TV
    private fun setupBottomNavForRemote(bottomNav: BottomNavigationView) {
        // Ambil container icon di dalam BottomNav
        val menuView = bottomNav.getChildAt(0) as? android.view.ViewGroup

        // Loop setiap item (Beranda, Media) dan aktifkan fokusnya
        menuView?.children?.forEach { child ->
            child.isFocusable = true
            child.isFocusableInTouchMode = true
            // Opsional: Tambahkan padding agar background selector tidak terlalu mepet
            child.setPadding(0, 16, 0, 16)
        }
    }

    private fun showFragment(f: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.navHost, f)
            .commit()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        WifiMonitor.handlePermissionResult(requestCode, grantResults, this)
    }
}