package com.idn.kmed.cervexa

import com.idn.kmed.cervexa.utils.StorageUtils
import com.idn.kmed.cervexa.utils.WifiMonitor
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch

class OverlayAndWifiLogicTest : StringSpec({
    "formatOverlayTimestamp uses yyyy/MM/dd HH:mm:ss format" {
        val formatted = StorageUtils.formatOverlayTimestamp(1_762_860_000_000L)
        formatted shouldMatch Regex("""\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}""")
    }

    "isCameraSsid matches default prefix and strips quotes" {
        WifiMonitor.isCameraSsid("\"wifi_camera_MS2_123456\"") shouldBe true
        WifiMonitor.isCameraSsid("wifi_camera_MS2_ABCDEF") shouldBe true
        WifiMonitor.isCameraSsid("KlinikWiFi_5G") shouldBe false
        WifiMonitor.isCameraSsid("<unknown ssid>") shouldBe false
        WifiMonitor.isCameraSsid(null) shouldBe false
    }
})
