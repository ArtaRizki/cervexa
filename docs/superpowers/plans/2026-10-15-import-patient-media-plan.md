# Import Patient Media Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Menambahkan fitur tombol "+" di toolbar tab Media untuk menginput data identitas pasien dan mengimpor foto serviks dari Galeri / MicroSD ke dalam struktur sesi Cervexa secara offline.

**Architecture:** Membuat `ImportPatientMediaActivity` mandiri dengan form data pasien (Nama, NIK, RS, NRM, DOB), meluncurkan Android Photo Picker (`PickMultipleVisualMedia` / `GetMultipleContents`), menyalin foto yang dipilih ke direktori `Scans/<yyyy-MM-dd>/<NIK_NAMA>/Snapshots/`, menulis `session.json`, dan membuka langsung `SessionMediaActivity`.

**Tech Stack:** Kotlin, AndroidX Activity Result Contracts (`PickMultipleVisualMedia`), Android Material Components, Kotlin Coroutines Dispatchers.IO, Gradle.

## Global Constraints
- Target platform: Android (minSdk 21+, compileSdk 34).
- Keutuhan data: Format `session.json` dan struktur folder harus 100% kompatibel dengan `MediaRepository.kt` dan `SessionMediaActivity.kt`.
- Tidak mengubah (zero-regression) alur koneksi live streaming kamera MS2 pada `RegistrationPatientActivity.kt` dan `VideoActivity.kt`.

---

### Task 1: Toolbar Menu & Handler Navigasi di HomeActivity

**Files:**
- Modify: `app/src/main/res/menu/toolbar.xml`
- Modify: `app/src/main/java/com/idn/kmed/cervexa/home/HomeActivity.kt`

**Interfaces:**
- Produces: Menu item `action_add_media` yang membuka `ImportPatientMediaActivity` dan hanya terlihat saat tab Media aktif.

- [ ] **Step 1: Tambahkan item menu `action_add_media` pada `app/src/main/res/menu/toolbar.xml`**
- [ ] **Step 2: Tambahkan navigasi klik `action_add_media` di `HomeActivity.kt` dan atur visibility menu (tampil saat tab Media, sembunyi saat tab Beranda)**
- [ ] **Step 3: Uji kompilasi cepat dengan `.\gradlew compileDebugKotlin`**

---

### Task 2: Layout Form Impor Pasien (`activity_import_patient_media.xml`)

**Files:**
- Create: `app/src/main/res/layout/activity_import_patient_media.xml`

**Interfaces:**
- Produces: Layout form input data pasien (Nama, NIK, RS, NRM, DOB DatePicker) dengan tombol "Pilih Foto Pemeriksaan" dan progress bar loading.

- [ ] **Step 1: Buat berkas `activity_import_patient_media.xml` dengan AppBar Toolbar, TextInputEditText untuk Nama, NIK, RS, NRM, DOB, serta tombol submit dan layout loading overlay**
- [ ] **Step 2: Verifikasi resource XML tidak memiliki error sintaks**

---

### Task 3: Implementasi `ImportPatientMediaActivity.kt` & Pendaftaran Manifest

**Files:**
- Create: `app/src/main/java/com/idn/kmed/cervexa/patient/ImportPatientMediaActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: Form inputs & Android Photo Picker URIs.
- Produces: Sesi tersimpan di `StorageUtils.ensureSessionDir(...)` + file `session.json` + `SessionMediaActivity` diluncurkan.

- [ ] **Step 1: Daftarkan `ImportPatientMediaActivity` di `AndroidManifest.xml`**
- [ ] **Step 2: Buat `ImportPatientMediaActivity.kt` dengan validasi input, Photo Picker launcher (`PickMultipleVisualMedia`), I/O image copy ke folder `Snapshots/`, penulisan `session.json`, dan navigasi ke `SessionMediaActivity`**
- [ ] **Step 3: Jalankan kompilasi `.\gradlew compileDebugKotlin`**

---

### Task 4: Kompilasi & Perakitan APK Lengkap

**Files:**
- Generate: `app/build/outputs/apk/debug/app-debug.apk`
- Copy to: `cervexa-debug.apk`

- [ ] **Step 1: Jalankan `.\gradlew assembleDebug`**
- [ ] **Step 2: Salin file APK hasil build ke `cervexa-debug.apk` di root proyek**
- [ ] **Step 3: Verifikasi ukuran berkas dan integritas build**
