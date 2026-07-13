---
name: elikliv-ms2-microscope
description: >
  Knowledge base dan panduan untuk perangkat Elikliv MS2 WiFi Digital Microscope.
  Gunakan skill ini ketika user bertanya tentang cara penggunaan, koneksi WiFi, mode UVC/PC,
  spesifikasi teknis, penyelesaian masalah, atau integrasi kamera MS2 ke dalam aplikasi Android
  (seperti proyek Cervexa / KmedHealthIndonesia). Skill ini juga memberikan panduan
  implementasi kode Android untuk mendeteksi dan menampilkan stream dari kamera MS2.
---

# Elikliv MS2 WiFi Digital Microscope — Skill Guide

Skill ini berisi pengetahuan lengkap tentang perangkat **Elikliv MS2 WiFi Digital Microscope**
beserta cara integrasinya ke dalam aplikasi Android.

---

## 1. Spesifikasi Teknis (Product Specifications)

| Parameter | Nilai |
|---|---|
| Sensor Optik | 1/4" CMOS |
| Resolusi Video | 1920×1080 (1080p FHD) |
| Resolusi Foto | 3M (2048×1536), 2M (1920×1080), 1M (1280×720) |
| LED Pencahayaan | 8 buah LED ring (adjustable brightness) |
| Konektivitas | WiFi (Hotspot) + Micro USB (UVC/PC mode) |
| Format Kartu Memori | MicroSD/TF Card, 8GB–64GB, Class 10 |
| Baterai | 18650 Li-ion (removable/replaceable) |
| Pengisian Daya | Micro USB, 5V |
| Bahasa | 9 bahasa |
| Rentang Fokus | 0–60 mm (putar roller fokus) |
| Nama SSID WiFi | `wifi_camera_MS2_XXXX` |
| App Download | `http://qr09.cn/DuKSYX` |
| Nama Aplikasi | "Max-see" atau "DM WiFi" (Google Play / App Store) |

---

## 2. Antarmuka Perangkat (Device Interface) — Tombol & Fungsi

### Panel Utama (1–15):
1. **Up Button** — Zoom-in / navigasi menu ke atas
2. **Down Button** — Zoom-out / navigasi menu ke bawah
3. **Power Button** — Long press: nyala/mati | Short press: toggle WiFi
4. **Menu Button** — Short press: masuk/keluar menu | Long press: ganti mode (Photo → Video → File)
5. **OK/Capture Button**:
   - Mode Foto: ambil gambar
   - Mode Video: mulai/stop perekaman
   - Mode Playback: play/pause
   - Menu: konfirmasi pilihan
6. **Indicator Light**:
   - 🔴 Merah = sedang charging
   - 🔵 Biru berkedip = booting | Biru solid = aktif
7. **Reset Button** — Tekan saat freeze/crash untuk reset ke kondisi default
8. **Cable Socket** — Soket untuk wire control eksternal
9. **TF Card Slot** — Slot MicroSD (8–64 GB, Class 10+)
10. **Micro USB Port** — Pengisian daya & koneksi PC
11. **Brightness Knob** — Putar untuk atur intensitas LED
12. **Focus Roller** — Putar untuk sesuaikan fokus / magnifikasi (0–60mm)
13. **Auxiliary LED Ring** — 8 LED ring di sekitar lensa
14. **Battery Cover** — Kompartemen baterai 18650
15. **Movable Bracket** — Engsel penghubung layar ke lengan penyangga

### Wire Control Eksternal:
- Up/Down: navigasi file/menu
- Menu: masuk menu / ganti mode
- Photo/OK: switch ke foto, tekan lagi untuk capture
- File: switch ke mode playback
- Video: switch ke mode video

---

## 3. Mode Operasi

### A. Mode Foto (Photography Mode)
1. Long press Menu untuk masuk mode Photo
2. Sesuaikan fokus via Focus Roller
3. Tekan OK untuk ambil gambar
4. File tersimpan di TF Card

### B. Mode Video (Video Recording Mode)
1. Long press Menu, pilih Video
2. Tekan OK untuk mulai merekam
3. Tekan OK kembali untuk stop
4. File tersimpan di TF Card dalam format AVI/MP4

### C. Mode Playback (File Mode)
1. Long press Menu, pilih File
2. Navigasi dengan Up/Down
3. Tekan OK untuk play/pause video atau lihat foto
4. Dari menu: opsi delete tersedia

---

## 4. Mode Koneksi PC via USB

Saat USB dicolok ke PC, layar menampilkan 3 pilihan:

| Mode | Fungsi |
|---|---|
| **Memory Mode (Mass Storage)** | PC bisa akses TF card langsung, seperti flashdisk. Tombol device nonaktif kecuali Power & Brightness |
| **PC Camera / UVC Mode** | Berfungsi sebagai webcam UVC. Gunakan software seperti Windows Camera, Amcap, dll. Pilih "USB Video Device" |
| **Video Recording / Charge Mode** | Mode pengisian daya normal; perangkat tetap merekam ke TF card |

---

## 5. Mode WiFi — Setup & Koneksi ke Aplikasi Android/iOS

### Langkah Koneksi:
1. **Nyalakan mikroskop** → tekan Power button pendek → ikon WiFi kuning berkedip di layar
2. **Buka pengaturan WiFi di ponsel** → cari SSID: `wifi_camera_MS2_XXXX`
3. **Sambungkan** → abaikan notifikasi "No Internet" (itu normal karena hotspot langsung)
4. **Buka aplikasi** (Max-see / DM WiFi) → tap **"Camera"** → lihat live feed
5. **Matikan Mobile Data** jika gambar tidak muncul (Mobile Data bisa override koneksi WiFi lokal)

### Catatan Penting WiFi:
- Mikroskop tidak bisa terhubung ke smartphone dan PC bersamaan
- Pertama kali buka app: izinkan semua permission (Storage, Location)
- Jika WiFi logo berhenti berkedip → koneksi berhasil

### Fitur di Aplikasi:
- **Camera**: live stream real-time dari mikroskop
- **My Photos**: galeri foto/video yang sudah disimpan ke perangkat
- Capture foto dan video dari dalam app

---

## 6. Koneksi UVC via USB OTG ke Android

Untuk koneksi **wired** ke perangkat Android (bukan PC):
1. Pastikan HP/Tablet mendukung **USB OTG**
2. Butuh adapter **USB-A → USB-C** (atau Micro-USB)
3. Set mikroskop ke **PC Camera / UVC Mode**
4. Colokkan → gunakan aplikasi UVC Camera pihak ketiga (misalnya: "UVC Camera", "USB Camera" dari Play Store)
5. Perangkat akan muncul sebagai **"USB Video Device"** standar

---

## 7. Tips & Troubleshooting

| Masalah | Solusi |
|---|---|
| Gambar buram | Putar Focus Roller, pastikan jarak 0–60mm |
| App tidak muncul live feed | Matikan Mobile Data, reconnect WiFi |
| Device freeze | Tekan Reset Button (lubang kecil) |
| TF Card tidak terdeteksi | Gunakan MicroSD Class 10, format FAT32, max 64GB |
| Charging lambat / tidak charging | Gunakan charger 5V original, jangan gunakan saat charging (lebih baik) |
| Baterai baru tidak menyala | Isi daya dulu via charger sebelum menyalakan pertama kali |
| WiFi tidak muncul di ponsel | Restart mikroskop, pastikan tidak sedang dipakai PC |
| Gambar gelap / terlalu terang | Putar Brightness Adjustment Knob |

---

## 8. Integrasi ke Aplikasi Android (Kode)

### A. Mendeteksi Kamera UVC via USB

Referensi implementasi untuk proyek **KmedHealthIndonesia / Cervexa**:

```java
// Di CameraDialog.java atau USBMonitor.java
// Deteksi perangkat UVC / MS2 saat dicolok USB OTG

public void onDeviceAttach(UsbDevice device) {
    String deviceName = device.getProductName();
    int vendorId = device.getVendorId();
    
    if (deviceName != null && deviceName.toLowerCase().contains("usb video")) {
        // Kemungkinan UVC device seperti MS2
        openCamera(device); // buka via libusbcamera
    } else {
        // Fallback ke kamera HP
        openPhoneCamera();
    }
}
```

### B. Menampilkan Stream WiFi dari MS2 (MJPEG / RTSP)

MS2 menggunakan protokol WiFi proprietary via app. Untuk stream langsung dari Android:

```java
// URL stream WiFi MS2 (tipikal untuk kamera jenis ini):
// http://192.168.1.1:8080/?action=stream  (MJPEG)
// atau via socket proprietary sesuai app Max-see

String wifiStreamUrl = "http://192.168.1.1:8080/?action=stream";
// Gunakan VLC LibVLC, ExoPlayer, atau WebView untuk render

// Dengan Glide (MJPEG tidak native, gunakan library):
// implementation 'com.github.bumptech.glide:glide:4.x'
// + MjpegInputStream library
```

### C. Deteksi Otomatis UVC atau Fallback ke Kamera HP

```java
// Pattern yang sudah diimplementasikan di proyek Cervexa:
// Jika tidak ada kamera UVC terdeteksi → gunakan kamera belakang HP

private void initCamera() {
    if (isUsbCameraConnected()) {
        openUvcCamera(); // MS2 via USB OTG
    } else {
        openPhoneCamera(); // Kamera HP sebagai fallback
    }
}

private boolean isUsbCameraConnected() {
    UsbManager usbManager = (UsbManager) getSystemService(USB_SERVICE);
    HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
    for (UsbDevice device : deviceList.values()) {
        // UVC class = 0x0E (Video)
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO
            || hasVideoInterface(device)) {
            return true;
        }
    }
    return false;
}
```

---

## 9. Referensi & Sumber

- **Manual Resmi**: https://id.manuals.plus/elikliv/ms2-wifi-digital-microscope-manual
- **App Download QR**: http://qr09.cn/DuKSYX
- **App Name**: Max-see / DM WiFi (Play Store & App Store)
- **Proyek Android terkait**: `d:\INFORMATICS\FREELANCE\cervexa_new` (KmedHealthIndonesia)
- **Implementasi UVC**: `com.jiangdg.libusbcamera`, `com.serenegiant.usb`
- **Implementasi Fallback Kamera**: `com.gizthon.camera.dialog.CameraDialog`

---

## 10. Cara Menggunakan Skill Ini

Ketika user bertanya tentang MS2 Microscope:
1. Cek mode yang ingin digunakan (WiFi / USB / standalone)
2. Rujuk ke tabel spesifikasi dan langkah koneksi sesuai kebutuhan
3. Jika terkait kode Android, berikan contoh implementasi dari Bagian 8
4. Jika ada masalah, cek Bagian 7 Troubleshooting
5. Untuk integrasi lebih dalam ke proyek Cervexa, baca source code di:
   - [`CameraDialog.java`](file:///d:/INFORMATICS/FREELANCE/cervexa_new/app/src/main/java/com/serenegiant/usb/CameraDialog.java)
   - [`AspectRatioTextureView.java`](file:///d:/INFORMATICS/FREELANCE/cervexa_new/app/src/main/java/com/serenegiant/usb/widget/AspectRatioTextureView.java)
