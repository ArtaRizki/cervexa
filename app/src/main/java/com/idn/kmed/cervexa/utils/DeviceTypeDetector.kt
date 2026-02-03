package com.idn.kmed.cervexa.utils

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log

/**
 * Utility class untuk mendeteksi tipe perangkat Android
 * Membedakan antara:
 * - Smartphone/Tablet (gunakan RTSP library lama)
 * - STB/Android TV (gunakan VLC library baru)
 */
object DeviceTypeDetector {

    private const val TAG = "DeviceTypeDetector"

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
}