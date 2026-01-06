package com.idn.kmed.cervexa

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.idn.kmed.cervexa.media.MediaListFragment
import com.idn.kmed.cervexa.model.WifiViewModel
import com.idn.kmed.cervexa.utils.WifiMonitor

class HomeActivity : AppCompatActivity() {

    private lateinit var wifiViewModel: WifiViewModel

    // Helper untuk mendeteksi apakah ini TV
    private val isTvDevice: Boolean
        get() {
            val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        wifiViewModel = ViewModelProvider(this)[WifiViewModel::class.java]

        // ✅ START WifiMonitor sekali di Activity
        WifiMonitor.init(this) { /* callback SSID lama tidak dipakai */ }
        WifiMonitor.setOnStatusChanged { status ->
            wifiViewModel.updateStatus(status)
        }

        // ... (Kode toolbar tetap sama) ...
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_system_info -> {
                    startActivity(Intent(this, SystemInfoActivity::class.java)); true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java)); true
                }
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
        if (isTvDevice) {
            setupBottomNavForRemote(bottom)
        }
        // --- [TV OPTIMIZATION END] ---

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

    override fun onDestroy() {
        super.onDestroy()
        // ✅ STOP WifiMonitor sekali di Activity (bukan di fragment)
        WifiMonitor.stopMonitoring()
    }

    // FUNGSI KHUSUS REMOTE TV
    private fun setupBottomNavForRemote(bottomNav: BottomNavigationView) {
        val menuView = bottomNav.getChildAt(0) as? ViewGroup
        val navHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var navRunnable: Runnable? = null

        menuView?.children?.forEachIndexed { index, child ->
            child.isFocusable = true
            child.isFocusableInTouchMode = true
            child.setPadding(0, 16, 0, 16)

            // 1. LOGIKA PINDAH TAB (Tetap sama)
            child.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    val destinationId = bottomNav.menu.getItem(index).itemId

                    if (bottomNav.selectedItemId != destinationId) {
                        navRunnable?.let { navHandler.removeCallbacks(it) }

                        navRunnable = Runnable {
                            bottomNav.selectedItemId = destinationId
                            view.requestFocus()
                        }
                        navHandler.postDelayed(navRunnable!!, 150)
                    }
                }
            }

            // 2. LOGIKA BARU: TOMBOL ATAS (Manual Navigation - Global Search)
            child.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    val btnConnect = findViewById<View>(R.id.btn_connect)
                    val rvMedia = findViewById<RecyclerView>(R.id.rv)

                    if (btnConnect != null && btnConnect.isShown) {
                        if (btnConnect.requestFocus()) return@setOnKeyListener true
                    }

                    if (rvMedia != null && rvMedia.isShown) {
                        if (rvMedia.adapter != null && rvMedia.adapter!!.itemCount > 0) {
                            rvMedia.requestFocus()
                            return@setOnKeyListener true
                        } else {
                            val btnStart = findViewById<View>(R.id.btnStart)
                            if (btnStart != null && btnStart.isShown) {
                                btnStart.requestFocus()
                                return@setOnKeyListener true
                            }
                        }

                        val searchView = findViewById<View>(R.id.searchView)
                        if (searchView != null && searchView.isShown) {
                            searchView.requestFocus()
                            return@setOnKeyListener true
                        }
                    }
                }
                false
            }
        }
    }

    private fun showFragment(f: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.navHost, f)
            .commit()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        WifiMonitor.handlePermissionResult(requestCode, grantResults, this)
    }
}
