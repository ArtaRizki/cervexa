# Implementation Plan: AI Abnormality Detection

## Overview

Implementasi fitur AI Abnormality Detection untuk aplikasi Cervexa. Fitur ini menambahkan layer abstraksi di atas `ViaModelHelper` yang sudah ada, dengan komponen baru: `AbnormalityResult` (sealed class), `AiDetector` (inference pipeline dengan coroutines), `AcetowhiteDetector` (fallback terpisah), `OverlayRenderer` (rendering bounding box & label), dan `AnalysisModeManager` (toggle persistence). Integrasi dilakukan ke `VideoFragmentMobile` (live stream) dan `MediaPageFragment` (galeri).

## Tasks

- [x] 1. Set up data models dan core interfaces
  - [x] 1.1 Create `AbnormalityResult` sealed class dan `Classification` enum
    - Buat file `app/src/main/java/com/idn/kmed/cervexa/ml/AbnormalityResult.kt`
    - Implementasi sealed class dengan variants: `Detected`, `Error`, `Idle`
    - Implementasi enum `Classification` dengan values `NORMAL`, `ABNORMAL`
    - `Detected` berisi: `label: Classification`, `confidenceScore: Float`, `boundingBox: RectF?`, `isFallback: Boolean`
    - `Error` berisi: `message: String`, `errorCode: Int`
    - _Requirements: 1.2, 5.1_

  - [x] 1.2 Create `AnalysisModeManager` class
    - Buat file `app/src/main/java/com/idn/kmed/cervexa/ml/AnalysisModeManager.kt`
    - Implementasi `StateFlow<Boolean>` untuk status aktif/nonaktif
    - Implementasi `toggle()`, `activate()`, `deactivate()`, `persist()`, `restore()`
    - Gunakan SharedPreferences key `ai_analysis_mode_active` (default: false)
    - _Requirements: 4.4_

  - [x]* 1.3 Write property test for AnalysisMode persistence round-trip
    - **Property 6: AnalysisMode persistence round-trip**
    - **Validates: Requirements 4.4**

- [x] 2. Implement AcetowhiteDetector sebagai komponen terpisah
  - [x] 2.1 Extract dan refactor `AcetowhiteDetector` dari `ViaModelHelper`
    - Buat file `app/src/main/java/com/idn/kmed/cervexa/ml/AcetowhiteDetector.kt`
    - Pindahkan logika `detectByColor()` dari `ViaModelHelper` ke class baru
    - Return type berubah dari `Float` menjadi `AbnormalityResult.Detected`
    - Sampling area tengah (25%–75% width & height), step 5 piksel
    - Kriteria acetowhite: R > 150, G > 150, B > 130, |R-G| < 45, |G-B| < 45
    - Confidence mapping: `min(ratio / 0.15, 1.0)`
    - Klasifikasi: score > 0.5 → ABNORMAL, else → NORMAL
    - Set `isFallback = true` pada result
    - Handle classification error: return confidenceScore = -1
    - Handle detection exception: propagate untuk ditangani AiDetector
    - _Requirements: 7.1, 7.3, 7.4, 7.5_

  - [x]* 2.2 Write property test for Acetowhite detection algorithm
    - **Property 8: Acetowhite detection algorithm correctness**
    - **Validates: Requirements 7.3, 7.4**

  - [x]* 2.3 Write property test for classification threshold and display formatting
    - **Property 2: Classification threshold and display formatting**
    - **Validates: Requirements 1.3, 5.1, 5.2, 5.3**

- [x] 3. Implement OverlayRenderer
  - [x] 3.1 Create `OverlayRenderer` class
    - Buat file `app/src/main/java/com/idn/kmed/cervexa/ml/OverlayRenderer.kt`
    - Implementasi `renderOverlay(source: Bitmap, result: AbnormalityResult.Detected, includeTimestamp: Boolean): Bitmap`
    - Implementasi `calculateTextSize(frameHeight: Int): Float` — proporsional terhadap frame
    - Implementasi `calculateStrokeWidth(frameWidth: Int): Float` — proporsional terhadap frame
    - Implementasi `formatLabel(result: AbnormalityResult.Detected): String`:
      - ABNORMAL: "AI: ABNORMAL (XX%)" atau "AI: ABNORMAL (XX%) (Acetowhite)" jika fallback
      - NORMAL: "AI: NORMAL (XX%)" atau "AI: NORMAL (XX%) (Acetowhite)" jika fallback
    - Implementasi `getLabelColor(result: AbnormalityResult.Detected): Int`:
      - score > 0.75 → merah (#FF0000)
      - 0.5 < score ≤ 0.75 → oranye (#FF8C00)
      - score ≤ 0.5 → hijau (#00C853)
    - Gambar bounding box merah/oranye saat ABNORMAL (jika boundingBox != null)
    - Gambar border hijau saat NORMAL (tanpa bounding box spesifik)
    - Skalakan text, stroke, padding proporsional terhadap resolusi frame
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 5.2, 5.3, 5.4_

  - [x]* 3.2 Write property test for overlay visualization correctness
    - **Property 3: Overlay visualization correctness**
    - **Validates: Requirements 2.1, 2.2, 3.3**

  - [x]* 3.3 Write property test for proportional overlay scaling
    - **Property 4: Proportional overlay scaling**
    - **Validates: Requirements 2.3**

  - [x]* 3.4 Write property test for fallback label indicator
    - **Property 9: Fallback label indicator**
    - **Validates: Requirements 7.2**

- [x] 4. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Implement AiDetector inference pipeline
  - [x] 5.1 Create `AiDetector` class with coroutine-based frame queue
    - Buat file `app/src/main/java/com/idn/kmed/cervexa/ml/AiDetector.kt`
    - Implementasi `Channel<Bitmap>(capacity = 3, onBufferOverflow = DROP_OLDEST)` untuk frame queue
    - Implementasi `StateFlow<AbnormalityResult>` untuk result
    - Implementasi `startAnalysis(scope: CoroutineScope)` — launch consumer coroutine pada `Dispatchers.Default`
    - Implementasi `stopAnalysis()` — cancel job dan close channel
    - Implementasi `submitFrame(bitmap: Bitmap)` — trySend ke channel
    - Implementasi `analyzeImage(bitmap: Bitmap): AbnormalityResult` — single image analysis
    - Implementasi `validateImage(width: Int, height: Int): String?`:
      - width < 64 OR height < 64 → "Gambar terlalu kecil untuk dianalisis (minimum 64x64 piksel)"
      - else → null (valid)
    - Fallback chain: TFLite exception → AcetowhiteDetector
    - Acetowhite classification error → return score = -1
    - Acetowhite detection exception → return `AbnormalityResult.Error`, disable AnalysisMode
    - _Requirements: 1.1, 1.2, 1.4, 6.1, 6.3, 7.1, 7.5, 7.6_

  - [x]* 5.2 Write property test for inference output structure completeness
    - **Property 1: Inference output structure completeness**
    - **Validates: Requirements 1.2**

  - [x]* 5.3 Write property test for image size validation gate
    - **Property 5: Image size validation gate**
    - **Validates: Requirements 3.6**

  - [x]* 5.4 Write property test for frame queue bounded size
    - **Property 7: Frame queue bounded size**
    - **Validates: Requirements 6.3**

- [x] 6. Refactor ViaModelHelper untuk integrasi dengan AiDetector
  - [x] 6.1 Update `ViaModelHelper` return type dan error handling
    - Ubah `detectAbnormality()` agar return `AbnormalityResult.Detected` (bukan `Float`)
    - Hapus internal `detectByColor()` — fallback sekarang ditangani oleh `AiDetector`
    - Tambahkan proper exception propagation (jangan catch internal, biarkan AiDetector handle)
    - Tambahkan bounding box extraction dari output model (jika model mendukung)
    - Pastikan `close()` tetap aman dipanggil multiple times
    - _Requirements: 1.1, 1.2, 6.4_

- [x] 7. Integrate AI Detection ke VideoFragmentMobile (Live Stream)
  - [x] 7.1 Add AI toggle button dan AnalysisMode integration
    - Tambahkan tombol toggle "AI ON/OFF" di layout `fragment_video_mobile.xml`
    - Inisialisasi `AnalysisModeManager` di fragment
    - Observe `AnalysisModeManager.isActive` StateFlow untuk update UI toggle
    - Implementasi toggle click: `AnalysisModeManager.toggle()`
    - Tampilkan indikator visual status ON (hijau/ikon aktif) dan OFF
    - Persist status via `AnalysisModeManager.persist()` di `onPause()`
    - Restore status via `AnalysisModeManager.restore()` di `onResume()`
    - _Requirements: 4.1, 4.2, 4.3, 4.4_

  - [x] 7.2 Integrate AiDetector dengan RTSP frame pipeline
    - Inisialisasi `AiDetector` dengan `ViaModelHelper` dan `AcetowhiteDetector`
    - Pada `onRtspImageBitmapObtained`: submit frame ke `AiDetector.submitFrame()` saat AnalysisMode aktif
    - Observe `AiDetector.result` StateFlow untuk mendapatkan `AbnormalityResult`
    - Gunakan `OverlayRenderer.renderOverlay()` untuk menggambar overlay pada frame
    - Ganti logika overlay AI yang ada (inline `processTextToBitmapSafe`) dengan `OverlayRenderer`
    - Tampilkan indikator loading saat AI memproses (non-blocking)
    - Stop analysis saat AnalysisMode dinonaktifkan
    - _Requirements: 1.1, 1.3, 1.5, 4.2, 4.3, 4.5_

  - [x] 7.3 Implement low memory handling dan resource cleanup
    - Register `ComponentCallbacks2` untuk detect low memory
    - Pada `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)`: deactivate AnalysisMode, tampilkan notifikasi
    - Pada `onDestroyView()`: panggil `AiDetector.stopAnalysis()`, `ViaModelHelper.close()`
    - Handle `Interpreter.close()` failure: log error, lanjutkan cleanup resource lain
    - _Requirements: 6.4, 6.5_

  - [x] 7.4 Implement snapshot dan video recording dengan AI overlay
    - Saat snapshot diambil dengan AnalysisMode aktif: sertakan overlay pada gambar tersimpan
    - Saat video recording dengan AnalysisMode aktif: sertakan overlay pada setiap frame
    - _Requirements: 2.4, 2.5_

- [x] 8. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Integrate AI Detection ke MediaPageFragment (Galeri)
  - [x] 9.1 Add "Analisis AI" button dan overlay toggle di gallery viewer
    - Tambahkan tombol "Analisis AI" di layout `page_media.xml` (visible saat mode IMAGE)
    - Tambahkan tombol "Hapus Overlay" (visible setelah analisis selesai)
    - Pada klik "Analisis AI":
      - Validasi gambar (decode check, minimum 64x64)
      - Tampilkan loading indicator
      - Jalankan `AiDetector.analyzeImage()` pada `Dispatchers.Default`
      - Tampilkan hasil overlay via `OverlayRenderer` di atas `PhotoView`
    - Pada klik "Hapus Overlay": kembalikan gambar asli tanpa overlay
    - Handle error: tampilkan pesan "Gambar rusak atau format tidak didukung" atau "Gambar terlalu kecil untuk dianalisis (minimum 64x64 piksel)"
    - Batasi waktu analisis max 3 detik (timeout)
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

- [x] 10. Wire components dan final integration
  - [x] 10.1 Update build.gradle dengan test dependencies
    - Tambahkan Kotest dependencies untuk property-based testing:
      - `testImplementation 'io.kotest:kotest-runner-junit5:5.8.0'`
      - `testImplementation 'io.kotest:kotest-assertions-core:5.8.0'`
      - `testImplementation 'io.kotest:kotest-property:5.8.0'`
    - Tambahkan MockK: `testImplementation 'io.mockk:mockk:1.13.8'`
    - Tambahkan coroutines test: `testImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3'`
    - Konfigurasi JUnit5 di `android` block: `testOptions { unitTests.all { it.useJUnitPlatform() } }`
    - _Requirements: N/A (infrastructure)_

  - [x] 10.2 Add report metadata integration
    - Sertakan label klasifikasi dan `ConfidenceScore` dalam metadata laporan sesi pemeriksaan
    - Integrasikan dengan `PdfReportHelper` atau mekanisme ekspor yang ada
    - _Requirements: 5.5_

  - [x]* 10.3 Write unit tests for error handling dan edge cases
    - Test: toggle AI on/off state transitions
    - Test: fallback activation when model fails to load
    - Test: error message display for corrupted images
    - Test: low memory auto-disable behavior
    - Test: interpreter cleanup on fragment destroy
    - Test: "AI: ERROR" display when acetowhite also fails
    - _Requirements: 1.4, 6.4, 6.5, 7.5, 7.6_

- [x] 11. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- Proyek menggunakan Kotlin dengan kotlinx-coroutines, TFLite, dan arsitektur Fragment-based
- `ViaModelHelper` sudah ada dan berfungsi — refactor dilakukan secara incremental
- Layout XML perlu dimodifikasi untuk menambahkan tombol toggle dan overlay container

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["1.3", "2.1", "10.1"] },
    { "id": 2, "tasks": ["2.2", "2.3", "3.1"] },
    { "id": 3, "tasks": ["3.2", "3.3", "3.4", "5.1"] },
    { "id": 4, "tasks": ["5.2", "5.3", "5.4", "6.1"] },
    { "id": 5, "tasks": ["7.1", "7.2"] },
    { "id": 6, "tasks": ["7.3", "7.4"] },
    { "id": 7, "tasks": ["9.1"] },
    { "id": 8, "tasks": ["10.2", "10.3"] }
  ]
}
```
