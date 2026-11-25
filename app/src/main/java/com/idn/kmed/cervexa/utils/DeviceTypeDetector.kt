package com.idn.kmed.cervexa.utils

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import com.idn.kmed.cervexa.R

/**
 * Utility class untuk mendeteksi tipe perangkat Android
 * Membedakan antara:
 * - Smartphone/Tablet (gunakan RTSP library lama)
 * - STB/Android TV (gunakan VLC library baru)
 */
object DeviceTypeDetector {

    private const val TAG = "DeviceTypeDetector"

    const val KEY_STREAM_ENGINE = "stream_engine_mode"
    const val ENGINE_AUTO = "auto"
    const val ENGINE_LIBVLC = "libvlc"
    const val ENGINE_IJK = "ijk"

    enum class DeviceType {
        SMARTPHONE_TABLET,  // Perangkat mobile biasa
        TV_STB             // Android TV atau STB
    }

    /**
     * Deteksi tipe perangkat
     * @return DeviceType.TV_STB jika Android TV/STB, DeviceType.SMARTPHONE_TABLET jika mobile
     */
    fun detectDeviceType(context: Context): DeviceType {
        val isTv = isAndroidTv(context)
        val deviceType = if (isTv) DeviceType.TV_STB else DeviceType.SMARTPHONE_TABLET

        Log.d(TAG, "Device Type Detected: $deviceType")
        Log.d(TAG, "- Is Android TV: $isTv")
        Log.d(TAG, "- UI Mode: ${getUiModeName(context)}")
        Log.d(TAG, "- Has Touchscreen: ${hasTouchscreen(context)}")
        Log.d(TAG, "- Device Model: ${Build.MODEL}")
        Log.d(TAG, "- Device Manufacturer: ${Build.MANUFACTURER}")

        return deviceType
    }

    /**
     * Check apakah perangkat adalah Android TV atau STB
     */
    private fun isAndroidTv(context: Context): Boolean {
        // Method 1: Check UI Mode
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val currentMode = uiModeManager?.currentModeType ?: Configuration.UI_MODE_TYPE_UNDEFINED
        val isTvMode = currentMode == Configuration.UI_MODE_TYPE_TELEVISION

        // Method 2: Check Leanback feature (Android TV feature)
        val hasLeanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        // Method 3: Check jika tidak ada touchscreen (karakteristik TV/STB)
        val noTouchscreen = !hasTouchscreen(context)

        // Method 4: Check TV input framework (hanya ada di Android TV)
        val hasTvInput = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV)

        // Method 5: Check manufacturer/model patterns untuk STB terkenal
        val isKnownStb = isKnownStbDevice()

        // Perangkat dianggap TV/STB jika:
        // - UI Mode adalah TV, ATAU
        // - Memiliki Leanback feature, ATAU
        // - Tidak punya touchscreen DAN memiliki live TV feature, ATAU
        // - Adalah STB yang dikenal
        val isTv = isTvMode || hasLeanback || (noTouchscreen && hasTvInput) || isKnownStb

        Log.d(TAG, "TV Detection Details:")
        Log.d(TAG, "  - TV Mode: $isTvMode")
        Log.d(TAG, "  - Has Leanback: $hasLeanback")
        Log.d(TAG, "  - No Touchscreen: $noTouchscreen")
        Log.d(TAG, "  - Has TV Input: $hasTvInput")
        Log.d(TAG, "  - Known STB: $isKnownStb")
        Log.d(TAG, "  - Final Decision: TV = $isTv")

        return isTv
    }

    /**
     * Check apakah perangkat memiliki touchscreen
     */
    private fun hasTouchscreen(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /**
     * Check apakah perangkat adalah STB yang dikenal berdasarkan manufacturer/model
     */
    private fun isKnownStbDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()
        val brand = Build.BRAND.lowercase()

        // Daftar manufacturer STB terkenal
        val stbManufacturers = listOf(
            "rockchip",     // Banyak STB China
            "amlogic",      // STB chip populer
            "allwinner",    // STB chip
            "hisilicon",    // Huawei STB
            "broadcom",     // Set-top box chips
            "mediatek"      // Beberapa STB menggunakan MTK
        )

        // Daftar model/brand STB terkenal
        val stbModels = listOf(
            "stb",
            "settopbox",
            "set-top-box",
            "androidtv",
            "tvbox",
            "tv box",
            "x96",          // STB populer
            "t95",          // STB populer
            "h96",          // STB populer
            "mecool",       // STB brand
            "minix",        // STB brand
            "xiaomi mi box", // Xiaomi TV box
            "nvidia shield", // NVIDIA Shield TV
            "chromecast"    // Google Chromecast with Google TV
        )

        val matchManufacturer = stbManufacturers.any { manufacturer.contains(it) }
        val matchModel = stbModels.any { model.contains(it) || brand.contains(it) }

        if (matchManufacturer || matchModel) {
            Log.d(TAG, "Detected known STB device: $manufacturer $model")
        }

        return matchManufacturer || matchModel
    }

    /**
     * Get UI mode name untuk logging
     */
    private fun getUiModeName(context: Context): String {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return when (uiModeManager?.currentModeType) {
            Configuration.UI_MODE_TYPE_TELEVISION -> "TELEVISION"
            Configuration.UI_MODE_TYPE_CAR -> "CAR"
            Configuration.UI_MODE_TYPE_DESK -> "DESK"
            Configuration.UI_MODE_TYPE_APPLIANCE -> "APPLIANCE"
            Configuration.UI_MODE_TYPE_WATCH -> "WATCH"
            Configuration.UI_MODE_TYPE_VR_HEADSET -> "VR_HEADSET"
            Configuration.UI_MODE_TYPE_NORMAL -> "NORMAL"
            else -> "UNDEFINED"
        }
    }

    /**
     * Helper function untuk log device info
     */
    fun logDeviceInfo(context: Context) {
        Log.d(TAG, "=== Device Information ===")
        Log.d(TAG, "Manufacturer: ${Build.MANUFACTURER}")
        Log.d(TAG, "Brand: ${Build.BRAND}")
        Log.d(TAG, "Model: ${Build.MODEL}")
        Log.d(TAG, "Device: ${Build.DEVICE}")
        Log.d(TAG, "Product: ${Build.PRODUCT}")
        Log.d(TAG, "Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        Log.d(TAG, "UI Mode: ${getUiModeName(context)}")
        Log.d(TAG, "Has Touchscreen: ${hasTouchscreen(context)}")
        Log.d(TAG, "Has Leanback: ${context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)}")
        Log.d(TAG, "Screen Size: ${getScreenSizeCategory(context)}")
        Log.d(TAG, "=========================")
    }

    /**
     * Get screen size category
     */
    private fun getScreenSizeCategory(context: Context): String {
        val screenLayout = context.resources.configuration.screenLayout
        return when (screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) {
            Configuration.SCREENLAYOUT_SIZE_SMALL -> "SMALL"
            Configuration.SCREENLAYOUT_SIZE_NORMAL -> "NORMAL"
            Configuration.SCREENLAYOUT_SIZE_LARGE -> "LARGE"
            Configuration.SCREENLAYOUT_SIZE_XLARGE -> "XLARGE"
            else -> "UNDEFINED"
        }
    }

    /**
     * Quick check function - return true jika TV/STB
     */
    fun isTvDevice(context: Context): Boolean {
        return detectDeviceType(context) == DeviceType.TV_STB
    }

    /**
     * Quick check function - return true jika Smartphone/Tablet
     */
    fun isMobileDevice(context: Context): Boolean {
        return detectDeviceType(context) == DeviceType.SMARTPHONE_TABLET
    }

    /**
     * Deteksi apakah perangkat merupakan Standing TV / Smart Screen / Tablet Layar Besar
     * (misal Amdox MV4 27" Android 14 touchscreen) yang memiliki layanan PrintManager normal
     * namun jauh lebih stabil menggunakan engine streaming LibVLC seperti Smart TV.
     */
    fun isStandingTabletOrLargeScreen(context: Context): Boolean {
        if (isAndroidTv(context)) return false

        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()
        val brand = Build.BRAND.lowercase()
        val product = Build.PRODUCT.lowercase()

        val standingKeywords = listOf(
            "amdox",
            "mv4",
            "mv 4",
            "prospera",
            "smart screen",
            "stanbyme",
            "standbyme",
            "kiosk"
        )
        val matchStandingModel = standingKeywords.any {
            manufacturer.contains(it) || model.contains(it) || brand.contains(it) || product.contains(it)
        }

        val cfg = context.resources.configuration
        val swDp = cfg.smallestScreenWidthDp
        val screenSizeMask = cfg.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
        val isLargeLayout = swDp >= 600 ||
            screenSizeMask == Configuration.SCREENLAYOUT_SIZE_LARGE ||
            screenSizeMask == Configuration.SCREENLAYOUT_SIZE_XLARGE

        // Perangkat Wi-Fi tanpa modul telepon seluler (seperti Standing Smart Screen / Wi-Fi Tablet)
        val hasTelephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

        val result = matchStandingModel || isLargeLayout || !hasTelephony
        Log.d(
            TAG,
            "Standing/Tablet check: result=$result (matchModel=$matchStandingModel, swDp=$swDp, largeLayout=$isLargeLayout, hasTelephony=$hasTelephony)"
        )
        return result
    }

    /**
     * Menentukan apakah live stream harus memakai engine LibVLC (VideoFragmentTv)
     * berdasarkan pilihan di Pengaturan (atau deteksi otomatis jika mode = ENGINE_AUTO).
     */
    fun shouldUseLibVlcEngine(context: Context): Boolean {
        val prefs = context.getSharedPreferences(
            context.getString(R.string.pref_application),
            Context.MODE_PRIVATE
        )
        return when (prefs.getString(KEY_STREAM_ENGINE, ENGINE_AUTO)) {
            ENGINE_LIBVLC -> true
            ENGINE_IJK -> false
            else -> isTvDevice(context) || isStandingTabletOrLargeScreen(context)
        }
    }

    fun getStreamEngineSummary(context: Context): String {
        val prefs = context.getSharedPreferences(
            context.getString(R.string.pref_application),
            Context.MODE_PRIVATE
        )
        return when (prefs.getString(KEY_STREAM_ENGINE, ENGINE_AUTO)) {
            ENGINE_LIBVLC -> "LibVLC (Smart TV & Stand TV)"
            ENGINE_IJK -> "IjkPlayer (Smartphone)"
            else -> {
                val autoLibVlc = isTvDevice(context) || isStandingTabletOrLargeScreen(context)
                if (autoLibVlc) "Otomatis (Aktif: LibVLC — TV / Stand TV)"
                else "Otomatis (Aktif: IjkPlayer — Smartphone)"
            }
        }
    }
}