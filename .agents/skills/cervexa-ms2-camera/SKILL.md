---
name: cervexa-ms2-camera
description: >
  Domain knowledge lengkap untuk proyek cervexa_new (KmedHealthIndonesia),
  sebuah aplikasi Android kamera medis/laboratorium yang mengintegrasikan
  kamera mikroskop WiFi Elikliv MS2. Gunakan skill ini setiap kali mengerjakan
  fitur yang berkaitan dengan: integrasi kamera MS2, live stream RTSP,
  capture foto/video dari stream, pengurangan latensi IjkMediaPlayer, Jieli SDK,
  UVC USB camera, PatientActivity, atau penyimpanan media ke galeri HP.
---

# Cervexa / KmedHealthIndonesia — Domain Knowledge

## Identitas Project

| Atribut | Nilai |
|---|---|
| **Nama Aplikasi** | KmedHealthIndonesia |
| **Package Name** | `com.weioa.KmedHealthIndonesia` |
| **Repository** | `https://github.com/ArtaRizki/cervexa_new` |
| **Branch Utama** | `main` |
| **Application Class** | `com.gizthon.camera.application.CameraApplication` |
| **Min SDK** | 21 (Android 5.0) |
| **Target SDK** | 29 (Android 10) |
| **Orientasi** | Landscape (global di Manifest) |
| **Build Tool** | Gradle + Kotlin DSL |
| **Bahasa Utama** | Java + Kotlin (campur) |

---

## Konteks Bisnis

Aplikasi ini digunakan untuk **pemeriksaan medis/laboratorium** menggunakan kamera
mikroskop WiFi **Elikliv MS2**. Kamera MS2 menyalakan hotspot sendiri (SSID mengandung
`..ms2..`), lalu HP Android terhubung ke hotspot tersebut dan menampilkan live stream
dari kamera di layar.

Fitur utama:
- **Live stream** dari kamera MS2 via WiFi (RTSP over UDP)
- **Capture foto** dari frame live stream → tersimpan ke galeri
- **Rekam video** dari live stream → tersimpan ke galeri
- **UVC/USB** mode kamera cadangan (kabel USB OTG)
- **PatientActivity**: manajemen data pasien yang diperiksa
- **Gallery**: melihat riwayat foto/video hasil pemeriksaan

---

## Arsitektur Streaming Kamera MS2

### Stack Teknologi

```
Kamera Elikliv MS2
       │ (WiFi Hotspot)
       │ SSID: *ms2*
       │
       ▼
HP Android (terhubung ke hotspot MS2)
       │
       ▼
RTSP Stream (format H.264 / AVC)
       │
       ▼
IjkMediaPlayer (FFmpeg-based, dari library ijk)
       │
       ▼
Jieli SDK (com.jieli.stream.p016dv.running2...)
   └── IjkVideoView.java    ← FrameLayout wrapper
       └── TextureRenderView.java  ← TextureView aktual
           └── getBitmap() ← cara ambil frame nyata
```

### Protocol Detail

| Komponen | Nilai |
|---|---|
| **Protokol Streaming** | RTSP (Real Time Streaming Protocol) |
| **Transport Layer** | **UDP** (setelah optimasi) — sebelumnya TCP |
| **Format Video** | H.264 / AVC |
| **Player Engine** | IjkMediaPlayer (fork FFmpeg) |
| **SDK Wrapper** | Jieli SDK (`com.jieli.stream...`) |

---

## File-File Kunci

### Kamera MS2

| File | Path | Keterangan |
|---|---|---|
| `Ms2CameraActivity.java` | `app/src/main/java/com/gizthon/camera/activity/` | Activity utama MS2, orchestrator semua fitur |
| `ViewRecorder.java` | `app/src/main/java/com/gizthon/camera/utils/` | Perekam video mandiri via MediaCodec + MediaMuxer |
| `IjkVideoView.java` | `app/src/main/java/com/jieli/stream/p016dv/running2/p017ui/widget/media/` | Wrapper player Jieli SDK |
| `TextureRenderView.java` | (folder yang sama) | TextureView aktual, extends TextureView |

### Activity Lainnya

| File | Keterangan |
|---|---|
| `PatientActivity.kt` | Manajemen data pasien (Kotlin) |
| `PatientHistoryActivity.kt` | Riwayat pasien (Kotlin) |
| `UVCUSBCameraActivity.java` | Mode kamera USB/UVC (alternatif MS2) |
| `GalleryListActivity.java` | Galeri foto & video hasil pemeriksaan |
| `PreviewPhotoActivity.java` | Preview sebelum/sesudah simpan foto |
| `PreviewVideoActivity.java` | Preview video hasil rekaman |
| `SplashActivity.java` | Entry point aplikasi |

---

## Pelajaran Penting: Optimasi Latensi MS2

### Masalah Awal
- Delay **~1 detik** saat menggerakkan kamera MS2 ke kiri/kanan
- Live stream tidak real-time, ada buffer yang menumpuk

### Root Cause
Di dalam `IjkVideoView.java`, terdapat blok konfigurasi `if (this.isRealTime)`
yang mengaktifkan mode **low-latency** (fflags=nobuffer, infbuf=1, rtsp=UDP).
Namun flag `isRealTime` **tidak pernah diset ke `true`** dari `Ms2CameraActivity`.

### Solusi yang Diterapkan

**1. Di `Ms2CameraActivity.java` — aktifkan real-time mode:**
```java
// Tambahkan SEBELUM setRender()
mVideoView.setRealtime(true);
mVideoView.setRender(IjkVideoView.RENDER_TEXTURE_VIEW);
```

**2. Di `IjkVideoView.java` — tuning parameter buffer:**
```java
// Parameter low-latency (dalam createPlayer())
ijkMediaPlayer.setOption(4, "min-frames", 1L);
ijkMediaPlayer.setOption(4, "max-buffer-size", 0L);
ijkMediaPlayer.setOption(4, "packet-buffering", 0L);
ijkMediaPlayer.setOption(2, "skip_frame", 8L);
ijkMediaPlayer.setOption(4, "framedrop", 5L);
ijkMediaPlayer.setOption(1, "probesize", 32768L);       // 32KB (dari 1MB)
ijkMediaPlayer.setOption(1, "analyzeduration", 100L);   // 100ms (dari 5000ms)
```

Saat `isRealTime = true`, blok di `createPlayer()` otomatis menambahkan:
- `fflags = nobuffer`
- `infbuf = 1`
- `rtsp_transport = udp`

---

## Pelajaran Penting: Capture Foto & Video

### Masalah: Hasil Blank / Hitam

`getDrawingCache()` — fungsi tangkap layar standar Android — **tidak bisa
menangkap konten hardware-accelerated** seperti video yang dirender oleh
`TextureView`. Hasilnya selalu kotak hitam kosong.

### Solusi: Pakai `TextureRenderView.getBitmap()`

`TextureRenderView` adalah turunan langsung `TextureView` bawaan Android,
sehingga ia **memiliki `getBitmap()` secara native**. Kita ekspos via `IjkVideoView`:

```java
// Di IjkVideoView.java — fungsi yang ditambahkan:
public Bitmap getBitmap() {
    if (this.mCurrentRender == RENDER_TEXTURE_VIEW && this.mRenderView != null) {
        return ((TextureRenderView) this.mRenderView).getBitmap();
    }
    return null;
}
```

**Untuk Foto (di `Ms2CameraActivity.java`):**
```java
// BENAR ✅ — ambil dari TextureView langsung
final Bitmap bmp = mVideoView.getBitmap();

// SALAH ❌ — menghasilkan hitam
mVideoView.setDrawingCacheEnabled(true);
Bitmap cache = mVideoView.getDrawingCache(); // null/hitam untuk video
```

**Untuk Video (di `ViewRecorder.java` — `recordLoop()`):**
```java
if (mView instanceof IjkVideoView) {
    bmp = ((IjkVideoView) mView).getBitmap();
} else {
    // fallback ke drawing cache untuk View biasa
}
```

---

## Pelajaran Penting: Penyimpanan ke Galeri

### Masalah Awal
- Foto/video tidak muncul di Galeri HP setelah disimpan
- Video disimpan ke folder internal aplikasi (`getExternalFilesDir`)
  yang tidak bisa diakses oleh aplikasi Galeri

### Solusi: Public Storage + MediaScannerConnection

**1. Simpan ke folder PUBLIK:**
```java
// Foto → Pictures/Cervexa/
File dir = new File(
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
    "Cervexa"
);
if (!dir.exists()) dir.mkdirs();
File imageFile = new File(dir, "MS2_" + System.currentTimeMillis() + ".jpg");

// Video → Movies/Cervexa/
File dir = new File(
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
    "Cervexa"
);
```

**2. Panggil MediaScanner setelah simpan:**
```java
// Untuk foto
MediaScannerConnection.scanFile(this,
    new String[]{imageFile.getAbsolutePath()},
    new String[]{"image/jpeg"}, null);

// Untuk video (setelah mRecorder.stop())
String path = mRecorder.getOutputPath();
MediaScannerConnection.scanFile(this,
    new String[]{path},
    new String[]{"video/mp4"}, null);
```

> ⚠️ `MediaScannerConnection.scanFile()` adalah "alarm" ke sistem agar
> Galeri langsung refresh. Tanpa ini, foto/video baru terdeteksi hanya
> setelah HP di-restart.

---

## Struktur ViewRecorder (Perekam Video Mandiri)

`ViewRecorder.java` di `com.gizthon.camera.utils` adalah kelas mandiri untuk
merekam live stream MS2 ke file MP4, dibuat karena Jieli SDK tidak mengekspos
API rekam video secara langsung.

### Cara Kerja

```
Thread rekaman (15 fps)
  │
  ├── getBitmap() dari IjkVideoView
  │
  ├── Canvas.drawBitmap() ke mInputSurface (MediaCodec)
  │
  ├── drainEncoder() — ambil frame H.264 yang sudah diencode
  │
  └── MediaMuxer.writeSampleData() → file .mp4
```

### Parameter Rekaman
- **Format**: H.264 / AVC
- **Bitrate**: 3 Mbps
- **FPS**: 15 fps
- **Output**: `Movies/Cervexa/MS2_Video_{timestamp}.mp4`

### Fungsi Penting

```java
public ViewRecorder(View view, String outputPath) { ... }
public void start() throws IOException { ... }
public void stop() { ... }       // hentikan rekaman, join thread
public String getOutputPath() { ... }  // path file untuk MediaScanner
```

---

## Konfigurasi Build

```
// app/build.gradle
compileSdkVersion = 29
minSdkVersion = 21
targetSdkVersion = 29

// Bahasa
sourceCompatibility JavaVersion.VERSION_1_8
targetCompatibility JavaVersion.VERSION_1_8
```

> ⚠️ Jika build gagal, periksa `local.properties`:
> ```
> sdk.dir=C\:\\Users\\it-arta\\AppData\\Local\\Android\\Sdk
> ```
> Path SDK harus sesuai user yang sedang login.

---

## Permissions yang Dibutuhkan (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.CAMERA"/>
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE"/>
<uses-permission android:name="android.permission.INTERNET"/>
```

> `android:requestLegacyExternalStorage="true"` diperlukan untuk menulis
> ke folder publik di Android 10 (target SDK 29).

---

## Dua Mode Kamera

### Mode 1: WiFi MS2 (`Ms2CameraActivity`)
- Kamera terhubung via WiFi hotspot (SSID: `*ms2*`)
- Protocol: RTSP → IjkMediaPlayer → Jieli SDK
- Transport: UDP (real-time mode)
- Cocok untuk: pemeriksaan di tempat, mobile

### Mode 2: USB/UVC (`UVCUSBCameraActivity`)
- Kamera terhubung via kabel USB OTG
- Protocol: UVC (USB Video Class)
- Library: libUVC / SereneGiant UVC SDK
- Cocok untuk: pemeriksaan statis di meja

---

## Candidate URL RTSP MS2

Di `Ms2CameraActivity.java` terdapat array `CANDIDATE_URLS` yang berisi
beberapa URL RTSP yang dicoba satu per satu saat koneksi:

```java
// Contoh format URL RTSP MS2
rtsp://192.168.x.x:554/stream
```

Aplikasi mencoba URL satu per satu hingga berhasil terkoneksi.

---

## SDK Pihak Ketiga yang Digunakan

| SDK | Package | Fungsi |
|---|---|---|
| **Jieli SDK** | `com.jieli.stream.p016dv.running2.*` | WiFi camera streaming wrapper |
| **IjkMediaPlayer** | `tv.danmaku.ijk.media.player.*` | FFmpeg-based media player |
| **Baidu LBS** | `com.baidu.trace.*` | Location service |
| **UVC (SereneGiant)** | `com.serenegiant.*` | USB camera support |
| **GeneralPlus GoPlusDrone** | `com.generalplus.GoPlusDrone.*` | Drone camera (warisan dari base app) |

> ⚠️ Package `com.generalplus.GoPlusDrone.*` dan `com.jieli.stream.*` berasal
> dari **`base.apk`** (aplikasi kamera original dari produsen kamera) yang
> di-decompile dengan JADX dan diintegrasikan ke cervexa_new. Kode ini adalah
> hasil decompile sehingga ada nama class yang ter-obfuscate (`p016dv`, `p017ui`, dll).

---

## Alur Pengembangan Standar

1. Edit kode Java/Kotlin di folder `app/src/main/java/`
2. Build: `.\gradlew assembleDebug`
3. Install ke HP via Android Studio atau `adb install app/build/outputs/apk/debug/app-debug.apk`
4. Test koneksi MS2: nyalakan kamera → HP konek ke hotspot MS2 → buka app
5. Commit & push ke GitHub

---

## Hal yang Belum Dikerjakan (Backlog)

- [ ] **Bounding Box / AI Detection**: Deteksi objek dari output model TFLite
  berdasarkan nilai "Shape" dari hasil inferensi di live stream
- [ ] **Refactor CANDIDATE_URLS**: Tambahkan mekanisme auto-discovery IP kamera MS2
  agar tidak perlu hardcode URL
- [ ] **Audio Recording**: Saat ini `ViewRecorder` hanya merekam video tanpa audio
---
