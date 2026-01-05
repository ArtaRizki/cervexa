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
import com.idn.kmed.cervexa.media.MediaListFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.idn.kmed.cervexa.utils.WifiMonitor

class HomeActivity : AppCompatActivity() {

    // Helper untuk mendeteksi apakah ini TV
    private val isTvDevice: Boolean
        get() {
            val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }

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
        // HANYA jalankan setup remote jika device adalah TV
        if (isTvDevice) {
            setupBottomNavForRemote(bottom)
        }
        // --- [TV OPTIMIZATION END] ---

        // Setup listener navigasi
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
                            // Kunci fokus agar tetap di icon ini setelah fragment berubah
                            view.requestFocus()
                        }
                        navHandler.postDelayed(navRunnable!!, 150)
                    }
                }
            }

            // 2. LOGIKA BARU: TOMBOL ATAS (Manual Navigation)
            child.setOnKeyListener { v, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {

                    // Cek kita sedang ada di Fragment mana
                    val currentFrag = supportFragmentManager.findFragmentById(R.id.navHost)

                    when (currentFrag) {
                        is HomeDashboardFragment -> {
                            // Jika di Home -> Lempar fokus ke tombol "Mulai"
                            currentFrag.view?.findViewById<View>(R.id.btn_connect)?.requestFocus()
                            return@setOnKeyListener true // Event selesai, jangan diproses sistem lagi
                        }

                        is MediaListFragment -> {
                            // Jika di Media -> Coba lempar ke List (RecyclerView) dulu
                            val rv = currentFrag.view?.findViewById<View>(R.id.rv)
                            val btnStart = currentFrag.view?.findViewById<View>(R.id.btnStart)
                            val searchView = currentFrag.view?.findViewById<View>(R.id.searchView)

                            // Logika Prioritas:
                            // 1. Jika List ada isinya -> Fokus ke List
                            // 2. Jika List kosong (Empty State) -> Fokus ke tombol "Mulai" di empty state
                            // 3. Terakhir -> Fokus ke Search View

                            if (rv != null && rv.visibility == View.VISIBLE && (rv as? androidx.recyclerview.widget.RecyclerView)?.adapter?.itemCount ?: 0 > 0) {
                                rv.requestFocus()
                            } else if (btnStart != null && btnStart.visibility == View.VISIBLE) {
                                btnStart.requestFocus()
                            } else {
                                searchView?.requestFocus()
                            }
                            return@setOnKeyListener true
                        }
                    }
                }
                false // Tombol lain (Bawah/Kiri/Kanan) biarkan sistem yang atur
            }
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