package com.idn.kmed.cervexa

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
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

        // ✅ START WifiMonitor
        WifiMonitor.init(this) { /* callback SSID lama tidak dipakai */ }
        WifiMonitor.setOnStatusChanged { status ->
            wifiViewModel.updateStatus(status)
        }

        // 1. SETUP TOOLBAR & MENU
        val toolbar =
            findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)

        // Listener Menu (sesuai toolbar.xml yang Anda kirim)
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

        // [TV OPTIMIZATION] Setup Fokus Toolbar (Titik Tiga)
        if (isTvDevice) {
            setupToolbarForTv(toolbar)
        }

        // 2. CEK ONBOARDING
        val prefs = getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
        if (!prefs.getBoolean("on_boarding", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish(); return
        }

        // 3. SETUP BOTTOM NAVIGATION
        val bottom = findViewById<BottomNavigationView>(R.id.nav_view)

        // [TV OPTIMIZATION] Setup Navigasi Bawah Remote
        if (isTvDevice) {
            setupBottomNavForRemote(bottom)
        }

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

        // 4. HANDLE INTENT / DEFAULT FRAGMENT
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
        WifiMonitor.stopMonitoring()
    }

    // ==========================================
    // LOGIKA KHUSUS TV (REMOTE CONTROL)
    // ==========================================

    /**
     * Mencari tombol 'More Options' (Overflow) di Toolbar dan mengaktifkan fokus + background.
     */
    private fun setupToolbarForTv(toolbar: Toolbar) {
        toolbar.isFocusable = false

        toolbar.post {
            var overflowButtonFound: View? = null

            // 1. Cari tombol Overflow
            for (i in 0 until toolbar.childCount) {
                val child = toolbar.getChildAt(i)
                if (child is androidx.appcompat.widget.ActionMenuView) {
                    for (j in 0 until child.childCount) {
                        val innerChild = child.getChildAt(j)
                        if (isOverflowButton(innerChild)) {
                            overflowButtonFound = innerChild
                            break
                        }
                    }
                } else if (isOverflowButton(child)) {
                    overflowButtonFound = child
                }
                if (overflowButtonFound != null) break
            }

            // 2. Setup Tombol jika ketemu
            overflowButtonFound?.let { btn ->
                btn.isFocusable = true
                btn.isFocusableInTouchMode = true
                btn.setBackgroundResource(R.drawable.bg_btn_remote_selector)
                val p = 12
                btn.setPadding(p, p, p, p)

                // --- [FIX NAVIGASI TURUN] ---
                // Saat tekan BAWAH dari titik tiga, paksa pindah ke elemen Fragment
                btn.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        return@setOnKeyListener moveFocusToFragmentContent()
                    }
                    false
                }
            }
        }
    }

    private fun moveFocusToFragmentContent(): Boolean {
        // A. Cek jika sedang di Home Dashboard (Cari tombol Connect)
        val btnConnect = findViewById<View>(R.id.btn_connect)
        if (btnConnect != null && btnConnect.isShown) {
            btnConnect.requestFocus()
            return true
        }

        // B. Cek jika sedang di Media List (Cari Search Bar atau List)
        val searchView = findViewById<View>(R.id.searchView)
        if (searchView != null && searchView.isShown) {
            searchView.requestFocus()
            return true
        }

        val rvMedia = findViewById<RecyclerView>(R.id.rv)
        if (rvMedia != null && rvMedia.isShown) {
            rvMedia.requestFocus()
            return true
        }

        return false
    }

    /** Helper untuk mendeteksi apakah view adalah tombol overflow (titik tiga) */
    private fun isOverflowButton(view: View): Boolean {
        // Cek deskripsi konten standar Android ("More options") atau nama class
        return view.contentDescription == "More options" ||
                view.javaClass.simpleName.contains("OverflowMenuButton")
    }

    private fun setupBottomNavForRemote(bottomNav: BottomNavigationView) {
        val menuView = bottomNav.getChildAt(0) as? ViewGroup
        val navHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var navRunnable: Runnable? = null

        menuView?.children?.forEachIndexed { index, child ->
            child.isFocusable = true
            child.isFocusableInTouchMode = true
            // Padding agar fokus ring tidak terlalu mepet
            child.setPadding(0, 16, 0, 16)

            // 1. Sync Tabs saat Fokus
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

            // 2. Navigasi Tombol ATAS (DPAD_UP)
            child.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    // Prioritas: Cek elemen konten dulu (Search / Tombol Connect / List)

                    // Cek elemen di HomeDashboardFragment
                    val btnConnect = findViewById<View>(R.id.btn_connect)
                    if (btnConnect != null && btnConnect.isShown) {
                        btnConnect.requestFocus()
                        return@setOnKeyListener true
                    }

                    // Cek elemen di MediaListFragment (RecyclerView)
                    val rvMedia = findViewById<RecyclerView>(R.id.rv)
                    if (rvMedia != null && rvMedia.isShown && rvMedia.adapter != null && rvMedia.adapter!!.itemCount > 0) {
                        rvMedia.requestFocus()
                        return@setOnKeyListener true
                    }

                    // Cek elemen di MediaListFragment (Search Bar)
                    val searchView = findViewById<View>(R.id.searchView)
                    if (searchView != null && searchView.isShown) {
                        searchView.requestFocus()
                        return@setOnKeyListener true
                    }

                    // Kalau tidak ada konten yg bisa fokus, coba lari ke Toolbar (Titik Tiga)
                    val toolbar = findViewById<Toolbar>(R.id.topAppBar)
                    toolbar?.requestLayout() // pancing layout refresh
                    // Kita tidak bisa requestFocus ke toolbar langsung karena isFocusable=false,
                    // tapi sistem akan mencari anak toolbar yang focusable (tombol overflow yang sudah kita setup)
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