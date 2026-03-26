package com.idn.kmed.cervexa.home

import android.Manifest
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.auth.LoginActivity
import com.idn.kmed.cervexa.settings.SettingsActivity
import com.idn.kmed.cervexa.settings.SystemInfoActivity
import com.idn.kmed.cervexa.auth.OnboardingActivity
import com.idn.kmed.cervexa.media.MediaListFragment
import com.idn.kmed.cervexa.model.WifiViewModel
import com.idn.kmed.cervexa.network.ApiResult
import com.idn.kmed.cervexa.network.CervexaRepository
import com.idn.kmed.cervexa.network.TokenManager
import com.idn.kmed.cervexa.utils.WifiMonitor
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var wifiViewModel: WifiViewModel

    // Kode unik untuk request permission
    private val REQ_PERMISSION_CONN = 2201

    // Helper untuk mendeteksi apakah ini TV
    private val isTvDevice: Boolean
        get() {
            val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
            return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // 1. REQUEST PERMISSIONS DI AWAL (CRITICAL)
        // Agar WifiMonitor bisa langsung membaca SSID tanpa menunggu user klik tombol connect
        checkAndRequestPermissions()

        wifiViewModel = ViewModelProvider(this)[WifiViewModel::class.java]

        // 2. START WifiMonitor
        WifiMonitor.init(this) { /* callback SSID sederhana (tidak dipakai krn ada ViewModel) */ }
        WifiMonitor.setOnStatusChanged { status ->
            wifiViewModel.updateStatus(status)
        }

        // 3. SETUP TOOLBAR & MENU
        val toolbar =
            findViewById<MaterialToolbar>(R.id.topAppBar)

        // Listener Menu
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
//        if (isTvDevice) {
//            setupToolbarForTv(toolbar)
//        }

        // 4. CEK ONBOARDING
        val prefs = getSharedPreferences(getString(R.string.pref_application), MODE_PRIVATE)
        if (!prefs.getBoolean("on_boarding", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish(); return
        }

        // 5. SETUP BOTTOM NAVIGATION
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

        // 6. HANDLE INTENT / DEFAULT FRAGMENT
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
    // PERMISSIONS LOGIC (SESUAI MANIFEST)
    // ==========================================
    private fun checkAndRequestPermissions() {
        // Cek versi Android untuk menentukan permission mana yang diminta
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13 (Tiramisu) ke atas: Butuh NEARBY_WIFI_DEVICES
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES),
                    REQ_PERMISSION_CONN
                )
            }
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    REQ_PERMISSION_CONN
                )
            }
        } else {
            // Android 12 (S) ke bawah: Butuh FINE_LOCATION untuk baca SSID
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    REQ_PERMISSION_CONN
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // Teruskan hasil ke WifiMonitor
        WifiMonitor.handlePermissionResult(requestCode, grantResults, this)

        // Jika permission diberikan, paksa refresh status Wi-Fi segera
        // Ini memastikan status "Unknown SSID" langsung berubah jadi nama SSID
        if (requestCode == REQ_PERMISSION_CONN && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            WifiMonitor.init(this) { }
        }
    }

    // ==========================================
    // LOGIKA KHUSUS TV (REMOTE CONTROL)
    // ==========================================

    private fun setupToolbarForTv(toolbar: Toolbar) {
//        toolbar.isFocusable = false

        toolbar.post {
            var overflowButtonFound: View? = null

            // 1. Cari tombol Overflow
            for (i in 0 until toolbar.childCount) {
                val child = toolbar.getChildAt(i)
                if (child is ActionMenuView) {
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
//                btn.isFocusable = true
//                btn.isFocusableInTouchMode = true
                btn.setBackgroundResource(R.drawable.bg_btn_remote_selector)
                val p = 12
                btn.setPadding(p, p, p, p)

                // --- [FIX NAVIGASI TURUN] ---
//                btn.setOnKeyListener { _, keyCode, event ->
//                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
//                        return@setOnKeyListener moveFocusToFragmentContent()
//                    }
//                    false
//                }
            }
        }
    }

//    private fun moveFocusToFragmentContent(): Boolean {
//        // A. Cek Home Dashboard
//        val btnConnect = findViewById<View>(R.id.btn_connect)
//        if (btnConnect != null && btnConnect.isShown) {
//            btnConnect.requestFocus()
//            return true
//        }
//
//        // B. Cek Media List
//        val searchView = findViewById<View>(R.id.searchView)
//        if (searchView != null && searchView.isShown) {
//            searchView.requestFocus()
//            return true
//        }
//
//        val rvMedia = findViewById<RecyclerView>(R.id.rv)
//        if (rvMedia != null && rvMedia.isShown) {
//            rvMedia.requestFocus()
//            return true
//        }
//
//        return false
//    }

    private fun isOverflowButton(view: View): Boolean {
        return view.contentDescription == "More options" ||
                view.javaClass.simpleName.contains("OverflowMenuButton")
    }

    private fun setupBottomNavForRemote(bottomNav: BottomNavigationView) {
        val menuView = bottomNav.getChildAt(0) as? ViewGroup
        val navHandler = Handler(Looper.getMainLooper())
        var navRunnable: Runnable? = null

        menuView?.children?.forEachIndexed { index, child ->
//            child.isFocusable = true
//            child.isFocusableInTouchMode = true
            child.setPadding(0, 16, 0, 16)

            // 1. Sync Tabs saat Fokus
//            child.setOnFocusChangeListener { view, hasFocus ->
//                if (hasFocus) {
//                    val destinationId = bottomNav.menu.getItem(index).itemId
//
//                    if (bottomNav.selectedItemId != destinationId) {
//                        navRunnable?.let { navHandler.removeCallbacks(it) }
//
//                        navRunnable = Runnable {
//                            bottomNav.selectedItemId = destinationId
//                            view.requestFocus()
//                        }
//                        navHandler.postDelayed(navRunnable!!, 150)
//                    }
//                }
//            }

            // 2. Navigasi Tombol ATAS (DPAD_UP)
//            child.setOnKeyListener { _, keyCode, event ->
//                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
//                    val btnConnect = findViewById<View>(R.id.btn_connect)
//                    if (btnConnect != null && btnConnect.isShown) {
//                        btnConnect.requestFocus()
//                        return@setOnKeyListener true
//                    }
//
//                    val rvMedia = findViewById<RecyclerView>(R.id.rv)
//                    if (rvMedia != null && rvMedia.isShown && rvMedia.adapter != null && rvMedia.adapter!!.itemCount > 0) {
//                        rvMedia.requestFocus()
//                        return@setOnKeyListener true
//                    }
//
//                    val searchView = findViewById<View>(R.id.searchView)
//                    if (searchView != null && searchView.isShown) {
//                        searchView.requestFocus()
//                        return@setOnKeyListener true
//                    }
//
//                    val toolbar = findViewById<Toolbar>(R.id.topAppBar)
//                    toolbar?.requestLayout()
//                }
//                false
//            }
        }
    }

    private fun showFragment(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.navHost, f)
            .commit()
    }
}