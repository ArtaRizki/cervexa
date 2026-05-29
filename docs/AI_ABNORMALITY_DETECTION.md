# AI Abnormality Detection — Dokumentasi Developer

## Ringkasan Proyek

**Cervexa** adalah aplikasi Android untuk skrining kanker serviks menggunakan metode VIA (Visual Inspection with Acetic Acid). Aplikasi ini terhubung ke kamera kolposkopi via RTSP untuk live streaming, dan menyimpan hasil pemeriksaan (foto/video) per sesi pasien.

**Fitur AI Abnormality Detection** menambahkan kemampuan AI untuk mendeteksi area abnormal pada gambar serviks secara otomatis — baik saat live stream maupun saat review gambar di galeri.

---

## Tech Stack

| Layer | Teknologi |
|-------|-----------|
| Language | Kotlin |
| Min SDK | 21 (Android 5.0) |
| Target SDK | 34 |
| Build | Gradle (Groovy DSL) |
| AI/ML | TensorFlow Lite 2.14.0 |
| Async | Kotlin Coroutines + StateFlow |
| UI | View Binding, ConstraintLayout, PhotoView |
| Video | RTSP Client (custom library), VLC |
| Testing | Kotest 5.8.0 (property-based), MockK 1.13.8 |
| Backend | Retrofit + OkHttp (API), Firebase Crashlytics |

---

## Arsitektur Fitur AI

```
┌─────────────────────────────────────────────────────────┐
│                      UI LAYER                           │
│  VideoFragmentMobile (Live)  │  MediaPageFragment (Gallery) │
│         ↕ toggle AI ON/OFF   │       ↕ tombol "Analisis AI" │
└──────────────┬───────────────┴──────────────┬───────────┘
               │ frame bitmap                  │ single image
               ▼                               ▼
┌─────────────────────────────────────────────────────────┐
│                   DETECTION LAYER                        │
│                                                         │
│  ┌─────────────┐    ┌──────────────┐    ┌───────────┐  │
│  │ FrameQueue  │───▶│  AiDetector  │───▶│ViaModel   │  │
│  │ (Channel 3) │    │  (pipeline)  │    │Helper     │  │
│  │ DROP_OLDEST │    │              │    │(TFLite)   │  │
│  └─────────────┘    │   fallback ──│───▶│Acetowhite │  │
│                     │              │    │Detector   │  │
│                     └──────┬───────┘    └───────────┘  │
│                            │                            │
│                            ▼                            │
│                   AbnormalityResult                      │
│                   (Detected/Error/Idle)                  │
└──────────────────────────┬──────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│                   RENDERING LAYER                        │
│                                                         │
│  ┌─────────────────────────────────────────────────┐    │
│  │              OverlayRenderer                     │    │
│  │  • Bounding box (merah/oranye) saat ABNORMAL    │    │
│  │  • Border hijau saat NORMAL                     │    │
│  │  • Label "AI: ABNORMAL (XX%)" / "AI: NORMAL"   │    │
│  │  • Scaling proporsional terhadap resolusi       │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

---

## Struktur File

```
app/src/main/java/com/idn/kmed/cervexa/
├── ml/                              ← KOMPONEN AI (BARU)
│   ├── AbnormalityResult.kt         ← Sealed class: Detected, Error, Idle
│   ├── AiDetector.kt               ← Pipeline inferensi (coroutine + Channel)
│   ├── AcetowhiteDetector.kt       ← Fallback deteksi warna
│   ├── AnalysisModeManager.kt      ← Toggle ON/OFF + SharedPreferences
│   ├── OverlayRenderer.kt          ← Render bounding box & label
│   └── ViaModelHelper.kt           ← TFLite wrapper (DIREFAKTOR)
├── live/
│   ├── VideoFragmentMobile.kt      ← Integrasi AI di live stream (DIMODIFIKASI)
│   └── VideoFragmentTv.kt          ← Fix compatibility (DIMODIFIKASI)
├── gallery/
│   └── MediaPageFragment.kt        ← Integrasi AI di galeri (DIMODIFIKASI)
├── utils/
│   └── PdfReportHelper.kt          ← Laporan PDF + AI metadata (DIMODIFIKASI)
└── ...

app/src/main/res/
├── drawable/
│   ├── bg_ai_toggle_off.xml        ← Background tombol AI (nonaktif)
│   ├── bg_ai_toggle_on.xml         ← Background tombol AI (aktif)
│   └── ic_ai_toggle.xml            ← Ikon AI
├── layout/
│   ├── fragment_video_mobile.xml   ← Tombol AI toggle (portrait)
│   └── page_media.xml             ← Tombol Analisis AI (portrait)
└── layout-land/
    ├── fragment_video_mobile.xml   ← Tombol AI toggle (landscape)
    └── page_media.xml             ← Tombol Analisis AI (landscape)
```

---

## Komponen Utama

### 1. `AbnormalityResult` (Sealed Class)

```kotlin
sealed class AbnormalityResult {
    data class Detected(
        val label: Classification,      // NORMAL atau ABNORMAL
        val confidenceScore: Float,     // 0.0 – 1.0
        val boundingBox: RectF?,        // null jika NORMAL
        val isFallback: Boolean = false // true jika dari AcetowhiteDetector
    ) : AbnormalityResult()

    data class Error(val message: String, val errorCode: Int = -1) : AbnormalityResult()
    object Idle : AbnormalityResult()
}

enum class Classification { NORMAL, ABNORMAL }
```

### 2. `AiDetector` (Inference Pipeline)

- **Frame Queue**: `Channel<Bitmap>(capacity = 3, DROP_OLDEST)` — frame lama dibuang jika antrian penuh
- **Consumer**: Coroutine di `Dispatchers.Default` yang memproses frame satu per satu
- **Fallback Chain**:
  1. TFLite via `ViaModelHelper` → jika exception →
  2. `AcetowhiteDetector` → jika exception →
  3. Return `Error("AI: ERROR")` + disable AnalysisMode

```kotlin
// Penggunaan di live stream:
aiDetector.startAnalysis(lifecycleScope)
aiDetector.submitFrame(bitmap)  // non-blocking
aiDetector.result.collect { result -> /* update overlay */ }

// Penggunaan di galeri:
val result = aiDetector.analyzeImage(bitmap)  // suspend, single shot
```

### 3. `AcetowhiteDetector` (Fallback)

Deteksi berbasis warna piksel untuk area acetowhite (bercak putih khas lesi pra-kanker):
- **Sampling area**: tengah frame (25%–75% width & height), step 5 piksel
- **Kriteria piksel acetowhite**: R > 150, G > 150, B > 130, |R-G| < 45, |G-B| < 45
- **Confidence**: `min(acetowhiteRatio / 0.15, 1.0)`
- **Klasifikasi**: score > 0.5 → ABNORMAL, else → NORMAL

### 4. `OverlayRenderer`

Menggambar overlay di atas bitmap:
- **ABNORMAL**: Bounding box merah (score > 0.75) atau oranye (0.5–0.75)
- **NORMAL**: Border hijau di sekeliling frame
- **Label**: "AI: ABNORMAL (XX%)" atau "AI: NORMAL (XX%)"
- **Fallback suffix**: "(Acetowhite)" jika `isFallback == true`
- **Scaling**: Text size = 4% frame height, stroke = 0.5% frame width

### 5. `AnalysisModeManager`

Toggle AI ON/OFF dengan persistence:
- `toggle()`, `activate()`, `deactivate()`
- `persist()` → simpan ke SharedPreferences
- `restore()` → baca dari SharedPreferences
- Key: `ai_analysis_mode_active` (Boolean, default: false)

---

## Alur Kerja

### Live Stream (VideoFragmentMobile)

```
1. User tekan toggle "AI ON"
2. AnalysisModeManager.activate()
3. AiDetector.startAnalysis(lifecycleScope)
4. Setiap frame RTSP masuk → submitFrame(bitmap)
5. AiDetector memproses → emit result ke StateFlow
6. Fragment observe result → OverlayRenderer.renderOverlay()
7. Bitmap dengan overlay ditampilkan + disimpan ke recorder/snapshot
8. User tekan "AI OFF" → stopAnalysis(), overlay hilang
```

### Galeri (MediaPageFragment)

```
1. User buka gambar → tombol "Analisis AI" muncul
2. User tekan "Analisis AI"
3. Validasi: decode check + minimum 64x64
4. AiDetector.analyzeImage(bitmap) dengan timeout 3 detik
5. Hasil overlay ditampilkan di atas PhotoView
6. User tekan "Hapus Overlay" → gambar asli kembali
```

---

## Git & Branch

| Branch | Deskripsi |
|--------|-----------|
| `main` | Production |
| `dev` | Development |
| `feature/frontend-only` | Branch utama frontend saat ini |
| `feature/ai-abnormality-detection` | **Branch fitur AI ini** (cabang dari `feature/frontend-only`) |

### Cara mulai:

```bash
git clone https://github.com/ArtaRizki/cervexa.git
cd cervexa
git checkout feature/ai-abnormality-detection
```

---

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest

# Install ke device
./gradlew installDebug
```

**Catatan**: Pastikan Android SDK terinstall dengan compileSdk 36.

---

## Testing

### Dependencies (sudah ditambahkan di `app/build.gradle`)

```gradle
testImplementation 'io.kotest:kotest-runner-junit5:5.8.0'
testImplementation 'io.kotest:kotest-assertions-core:5.8.0'
testImplementation 'io.kotest:kotest-property:5.8.0'
testImplementation 'io.mockk:mockk:1.13.8'
testImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3'
```

### Property-Based Tests (yang perlu ditulis)

| Property | Apa yang divalidasi |
|----------|---------------------|
| Classification threshold | score > 0.5 = ABNORMAL, ≤ 0.5 = NORMAL |
| Display formatting | Persentase dan warna sesuai score |
| Overlay visualization | Bounding box merah/oranye saat ABNORMAL, hijau saat NORMAL |
| Proportional scaling | Text/stroke proporsional terhadap resolusi |
| Image validation | Tolak gambar < 64x64 |
| AnalysisMode round-trip | persist() → restore() = same value |
| Frame queue bounds | Max 3 frame, drop oldest |
| Acetowhite algorithm | Sampling area, pixel criteria, confidence mapping |
| Fallback label | isFallback=true → label mengandung "(Acetowhite)" |

---

## Error Handling

| Kondisi | Penanganan | Feedback ke User |
|---------|------------|------------------|
| Model TFLite gagal dimuat | Fallback AcetowhiteDetector | Label "(Acetowhite)" |
| TFLite inference exception | Fallback AcetowhiteDetector | Label "(fallback)" |
| Acetowhite juga gagal | Return Error, disable AI | "AI: ERROR" |
| Gambar corrupt | Tampilkan pesan | "Gambar rusak atau format tidak didukung" |
| Gambar < 64x64 | Tampilkan pesan | "Gambar terlalu kecil untuk dianalisis" |
| Low memory | Auto-disable AI | Toast "AI dinonaktifkan: memori tidak cukup" |
| Interpreter.close() gagal | Log error, lanjut cleanup | Tidak ada (internal) |

---

## Yang Perlu Dikerjakan Selanjutnya

- [ ] Tulis property-based tests (9 properties di design doc)
- [ ] Tulis unit tests untuk error handling & edge cases
- [ ] Performance testing: < 200ms per frame, ≥ 5 FPS
- [ ] Integrasi dengan model TFLite yang lebih akurat (jika ada update model)
- [ ] UI polish: animasi toggle, transisi overlay

---

## Kontak

Jika ada pertanyaan tentang arsitektur atau flow, lihat file spec lengkap di:
- `.kiro/specs/ai-abnormality-detection/requirements.md`
- `.kiro/specs/ai-abnormality-detection/design.md`
- `.kiro/specs/ai-abnormality-detection/tasks.md`
