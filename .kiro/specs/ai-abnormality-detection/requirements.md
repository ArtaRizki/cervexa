# Requirements Document

## Introduction

Fitur **AI Abnormality Detection** menambahkan kemampuan analisis kecerdasan buatan ke aplikasi Android Cervexa — platform skrining kanker serviks berbasis metode VIA (Visual Inspection with Acetic Acid). Fitur ini terinspirasi dari sistem Vis-BUS (AI-based Breast Ultrasound Image Analysis) yang mampu menandai area abnormal pada gambar medis secara visual.

Sistem menggunakan model TFLite (`via_model.tflite`) yang sudah tersedia di assets aplikasi, diintegrasikan ke dua konteks utama: **live streaming** (kamera real-time via RTSP) dan **galeri media** (gambar/video yang sudah tersimpan). Output utama adalah:
1. **Bounding box / highlight overlay** pada area yang terdeteksi abnormal
2. **Label klasifikasi** (NORMAL / ABNORMAL) beserta **confidence score** dalam persentase
3. **Fallback** ke deteksi berbasis warna (Acetowhite) jika model TFLite gagal dimuat

---

## Glossary

- **AI_Detector**: Komponen yang menjalankan inferensi model TFLite dan menghasilkan hasil deteksi abnormalitas
- **ViaModelHelper**: Kelas Kotlin yang membungkus TFLite Interpreter untuk model `via_model.tflite`
- **AbnormalityResult**: Struktur data yang merepresentasikan hasil deteksi, berisi label, confidence score, dan koordinat area abnormal
- **OverlayRenderer**: Komponen yang menggambar bounding box dan label hasil deteksi di atas frame gambar/video
- **LiveStream**: Aliran video real-time dari kamera kolposkopi via protokol RTSP, ditampilkan di `VideoFragmentMobile`
- **GalleryViewer**: Tampilan media tersimpan (gambar/video) di `MediaPageFragment` dan `MediaPagerActivity`
- **ConfidenceScore**: Nilai probabilitas (0.0–1.0) yang dihasilkan model, merepresentasikan keyakinan model terhadap klasifikasi
- **AcetowhiteDetection**: Algoritma fallback berbasis analisis warna piksel untuk mendeteksi bercak putih khas lesi pra-kanker
- **AnalysisMode**: Status toggle yang menentukan apakah AI Detector aktif atau tidak pada sesi tertentu
- **Threshold**: Nilai batas ConfidenceScore (default 0.5) yang memisahkan klasifikasi NORMAL dan ABNORMAL

---

## Requirements

### Requirement 1: Inferensi Model AI pada Frame Live Stream

**User Story:** Sebagai dokter/tenaga medis, saya ingin sistem AI menganalisis setiap frame video live secara real-time, sehingga saya dapat segera mengetahui apakah area yang sedang diperiksa menunjukkan tanda abnormal.

#### Acceptance Criteria

1. WHEN `AnalysisMode` aktif dan frame baru diterima dari LiveStream, THE `AI_Detector` SHALL menjalankan inferensi menggunakan `ViaModelHelper.detectAbnormality()` pada frame tersebut dalam waktu tidak lebih dari 200ms per frame.
2. WHEN inferensi selesai, THE `AI_Detector` SHALL menghasilkan `AbnormalityResult` yang berisi label klasifikasi (NORMAL atau ABNORMAL), `ConfidenceScore`, dan koordinat area yang terdeteksi.
3. WHILE `AnalysisMode` aktif, THE `OverlayRenderer` SHALL menampilkan label klasifikasi dan `ConfidenceScore` dalam format "AI: ABNORMAL (XX%)" atau "AI: NORMAL (XX%)" di atas frame LiveStream.
4. IF model TFLite gagal dimuat atau inferensi menghasilkan exception, THEN THE `AI_Detector` SHALL beralih ke `AcetowhiteDetection` sebagai fallback dan menampilkan indikator "(fallback)" pada overlay.
5. WHEN `AnalysisMode` tidak aktif, THE `AI_Detector` SHALL menghentikan inferensi dan THE `OverlayRenderer` SHALL menyembunyikan semua overlay AI dari frame.

---

### Requirement 2: Visualisasi Bounding Box Area Abnormal

**User Story:** Sebagai dokter/tenaga medis, saya ingin melihat area mana yang terdeteksi abnormal ditandai secara visual pada gambar, sehingga saya dapat memfokuskan perhatian ke area yang relevan secara klinis.

#### Acceptance Criteria

1. WHEN `AbnormalityResult` mengandung koordinat area abnormal dengan `ConfidenceScore` lebih dari `Threshold`, THE `OverlayRenderer` SHALL menggambar bounding box berwarna merah di atas area tersebut pada frame.
2. WHEN `AbnormalityResult` mengklasifikasikan frame sebagai NORMAL, THE `OverlayRenderer` SHALL menampilkan border atau indikator berwarna hijau tanpa bounding box area spesifik.
3. THE `OverlayRenderer` SHALL menskalakan ukuran bounding box, ketebalan garis, dan ukuran teks label secara proporsional terhadap resolusi frame agar tampilan konsisten di berbagai ukuran layar.
4. WHEN snapshot diambil saat `AnalysisMode` aktif, THE `OverlayRenderer` SHALL menyertakan overlay bounding box dan label AI pada gambar yang tersimpan.
5. WHEN video direkam saat `AnalysisMode` aktif, THE `OverlayRenderer` SHALL menyertakan overlay bounding box dan label AI pada setiap frame video yang tersimpan.

---

### Requirement 3: Analisis AI pada Gambar di Galeri

**User Story:** Sebagai dokter/tenaga medis, saya ingin menjalankan analisis AI pada gambar yang sudah tersimpan di galeri, sehingga saya dapat meninjau ulang hasil pemeriksaan dengan bantuan AI kapan saja.

#### Acceptance Criteria

1. WHEN pengguna membuka gambar di `GalleryViewer`, THE `GalleryViewer` SHALL menampilkan tombol "Analisis AI" yang dapat diaktifkan oleh pengguna.
2. WHEN pengguna menekan tombol "Analisis AI" pada gambar, THE `AI_Detector` SHALL menjalankan inferensi pada gambar tersebut dan menampilkan `AbnormalityResult` dalam waktu tidak lebih dari 3 detik.
3. WHEN inferensi pada gambar selesai, THE `OverlayRenderer` SHALL menampilkan bounding box dan label klasifikasi beserta `ConfidenceScore` di atas gambar.
4. WHEN pengguna menekan tombol "Hapus Overlay", THE `OverlayRenderer` SHALL menghapus semua overlay AI dan menampilkan gambar asli tanpa modifikasi.
5. IF gambar tidak dapat di-decode, THEN THE `AI_Detector` SHALL menampilkan pesan error "Gambar rusak atau format tidak didukung" tanpa menjalankan inferensi.
6. IF ukuran gambar kurang dari 64x64 piksel, THEN THE `AI_Detector` SHALL menampilkan pesan error "Gambar terlalu kecil untuk dianalisis (minimum 64x64 piksel)" tanpa menjalankan inferensi.

---

### Requirement 4: Toggle Aktivasi AI Detection

**User Story:** Sebagai dokter/tenaga medis, saya ingin dapat mengaktifkan dan menonaktifkan fitur AI Detection kapan saja selama sesi live, sehingga saya dapat memilih kapan analisis AI diperlukan tanpa mengganggu alur kerja pemeriksaan.

#### Acceptance Criteria

1. THE `VideoFragmentMobile` SHALL menampilkan tombol toggle "AI ON/OFF" yang dapat diakses pengguna selama sesi LiveStream berlangsung.
2. WHEN pengguna mengaktifkan toggle AI, THE `AI_Detector` SHALL mulai memproses setiap frame LiveStream dan THE tombol toggle SHALL menampilkan indikator visual status "ON" (misalnya warna hijau atau ikon aktif).
3. WHEN pengguna menonaktifkan toggle AI, THE `AI_Detector` SHALL berhenti memproses frame dan THE `OverlayRenderer` SHALL menghapus semua overlay AI dari tampilan.
4. THE `VideoFragmentMobile` SHALL menyimpan status `AnalysisMode` terakhir ke SharedPreferences sehingga status dipertahankan saat pengguna kembali ke layar yang sama dalam sesi yang sama.
5. WHILE `AI_Detector` sedang memproses frame, THE `VideoFragmentMobile` SHALL menampilkan indikator loading yang tidak menghalangi tampilan frame utama.

---

### Requirement 5: Confidence Score dan Label Klasifikasi

**User Story:** Sebagai manajemen/dokter, saya ingin melihat label NORMAL/ABNORMAL beserta persentase confidence score dari hasil analisis AI, sehingga saya dapat menilai tingkat keyakinan sistem terhadap hasil deteksi.

#### Acceptance Criteria

1. THE `AI_Detector` SHALL mengklasifikasikan hasil inferensi sebagai ABNORMAL jika `ConfidenceScore` lebih dari 0.5, dan sebagai NORMAL jika `ConfidenceScore` kurang dari atau sama dengan 0.5.
2. WHEN hasil klasifikasi adalah ABNORMAL dengan `ConfidenceScore` lebih dari 0.75, THE `OverlayRenderer` SHALL menampilkan teks "ABNORMAL (XX%)" dengan warna merah solid; WHEN `ConfidenceScore` antara 0.5 dan 0.75, THE `OverlayRenderer` SHALL menampilkan teks "ABNORMAL (XX%)" dengan warna oranye untuk mengindikasikan keyakinan rendah; di mana XX adalah nilai `ConfidenceScore` dikalikan 100 dan dibulatkan ke bilangan bulat terdekat.
3. WHEN hasil klasifikasi adalah NORMAL, THE `OverlayRenderer` SHALL menampilkan teks "NORMAL (XX%)" dengan warna hijau, di mana XX adalah nilai (1 - `ConfidenceScore`) dikalikan 100 dan dibulatkan ke bilangan bulat terdekat, dan THE `OverlayRenderer` SHALL memastikan warna merah tidak digunakan untuk klasifikasi NORMAL.
4. THE `OverlayRenderer` SHALL menampilkan label dan persentase dengan ukuran teks yang terbaca pada resolusi frame minimum 320x240 piksel.
5. WHERE fitur ekspor laporan tersedia, THE `AI_Detector` SHALL menyertakan label klasifikasi dan `ConfidenceScore` dalam metadata laporan sesi pemeriksaan.

---

### Requirement 6: Performa dan Stabilitas Inferensi

**User Story:** Sebagai pengguna aplikasi, saya ingin fitur AI berjalan tanpa menyebabkan lag atau crash pada aplikasi, sehingga pengalaman pemeriksaan tetap lancar dan andal.

#### Acceptance Criteria

1. THE `AI_Detector` SHALL menjalankan inferensi pada thread terpisah dari UI thread untuk mencegah blocking pada antarmuka pengguna.
2. WHILE `AnalysisMode` aktif pada LiveStream, THE `AI_Detector` SHALL memproses frame dengan frame rate tidak kurang dari 5 FPS pada perangkat dengan spesifikasi minimum (ARM Cortex-A53, 2GB RAM).
3. IF antrian frame inferensi melebihi 3 frame yang belum diproses, THEN THE `AI_Detector` SHALL membuang frame lama dan hanya memproses frame terbaru untuk mencegah penumpukan memori.
4. WHEN `VideoFragmentMobile` dihancurkan (onDestroyView), THE `ViaModelHelper` SHALL menutup TFLite Interpreter secara langsung; IF penutupan Interpreter gagal, THEN THE `ViaModelHelper` SHALL mencatat error ke log dan melanjutkan pelepasan resource memori lainnya secara independen.
5. IF perangkat mengalami kondisi memori rendah (low memory), THEN THE `AI_Detector` SHALL menonaktifkan `AnalysisMode` secara otomatis dan menampilkan notifikasi "AI dinonaktifkan: memori tidak cukup" kepada pengguna.

---

### Requirement 7: Fallback Acetowhite Detection

**User Story:** Sebagai pengguna aplikasi, saya ingin sistem tetap dapat memberikan indikasi abnormalitas meskipun model AI utama tidak tersedia, sehingga pemeriksaan dapat tetap berjalan dengan bantuan analisis minimal.

#### Acceptance Criteria

1. WHEN `ViaModelHelper` gagal memuat file `via_model.tflite` dari assets, THE `AI_Detector` SHALL secara otomatis mengaktifkan `AcetowhiteDetection` sebagai mekanisme fallback.
2. WHILE `AcetowhiteDetection` aktif sebagai fallback, THE `OverlayRenderer` SHALL menampilkan label "(Acetowhite)" di samping hasil klasifikasi untuk membedakan dari hasil model TFLite.
3. THE `AcetowhiteDetection` SHALL menganalisis area tengah frame (25%–75% dari lebar dan tinggi) untuk mendeteksi piksel dengan karakteristik acetowhite (R > 150, G > 150, B > 130, dan selisih antar channel < 45).
4. WHEN rasio piksel acetowhite terhadap total piksel sampel melebihi 15%, THE `AcetowhiteDetection` SHALL mengklasifikasikan frame sebagai ABNORMAL dengan `ConfidenceScore` proporsional terhadap rasio tersebut.
5. IF logika klasifikasi `AcetowhiteDetection` gagal mengkategorikan hasil deteksi, THEN THE `AI_Detector` SHALL menangani kegagalan klasifikasi secara terpisah dari exception deteksi, mencatat error ke log, dan mengembalikan `ConfidenceScore` bernilai -1 sebagai indikator kegagalan klasifikasi.
6. IF `AcetowhiteDetection` juga menghasilkan exception, THEN THE `AI_Detector` SHALL menampilkan label "AI: ERROR" dan menonaktifkan `AnalysisMode` untuk sesi tersebut.
