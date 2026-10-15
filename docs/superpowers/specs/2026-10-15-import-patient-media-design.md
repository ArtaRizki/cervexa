# Design Specification: Import Data Pemeriksaan Pasien (Input Pasien + Foto Galeri/MicroSD)

**Tanggal**: 2026-10-15  
**Proyek**: Cervexa (com.idn.kmed.cervexa)  
**Status**: Approved by User  
**Tujuan**: Menyediakan fitur input data pasien dan import foto serviks secara offline dari Galeri / MicroSD ke dalam struktur sesi Cervexa tanpa perlu terhubung ke kamera mikroskop Elikliv MS2.

---

## 1. Latar Belakang & Masalah
- **Masalah Operasional**: Saat ini, sesi pemeriksaan pasien di Cervexa hanya dapat dibuat melalui live stream kamera Wi-Fi Elikliv MS2 (`VideoActivity`). Dalam kondisi lapangan, dokter/bidan sering kali mengambil foto langsung menggunakan kamera mikroskop offline (tersimpan di MicroSD kamera) atau mengalami kendala koneksi Wi-Fi kamera di lokasi pemeriksaan.
- **Masalah Data Hilang**: Ketika pengguna melakukan Clear Data/Cache atau re-install aplikasi, folder app-scoped storage (`/Android/data/com.idn.kmed.cervexa/`) terhapus. Pengguna membutuhkan cara untuk mengimpor kembali foto-foto pemeriksaan pasien dari MicroSD/galeri ke Cervexa.
- **Kebutuhan**: Tombol "+" di toolbar halaman Media untuk menginput identitas pasien dan memilih foto-foto pemeriksaan dari Galeri/MicroSD secara langsung.

---

## 2. Arsitektur & Alur Pengguna (User Flow)

```
[HomeActivity: Tab Media]
   └── Toolbar kanan atas menampilkan tombol "+" (ic_add)
         ↓ Klik "+"
[ImportPatientMediaActivity]
   ├── Form Data Pasien:
   │     - Nama Pasien (Wajib)
   │     - NIK (16 digit, Wajib)
   │     - Rumah Sakit / Klinik (Wajib)
   │     - No. Rekam Medis / NRM (Opsional)
   │     - Tanggal Lahir / DOB (DatePickerDialog, Opsional/Wajib)
   │
   └── Tombol "Pilih Foto Pemeriksaan"
         ↓ Validasi form
   [Photo Picker Android / File Picker]
         ↓ Pengguna memilih 1 atau banyak foto (Galeri / MicroSD)
   [Background I/O Processing]
         ├── Buat folder: Scans/<yyyy-MM-dd>/<NIK_NAMA>/
         ├── Buat subfolder: Snapshots/
         ├── Salin setiap gambar ke Snapshots/result_<timestamp>_<index>.jpg
         └── Tulis session.json (nama, nik, nrm, rs, dob_utc, saved_at)
         ↓ Selesai
   [Buka SessionMediaActivity]
         - Menampilkan foto yang diimpor secara instan
         - Siap dijalankan AI YOLOv8-Seg (IVA detection)
         - Siap dicetak ke PDF / Print Bridge
```

---

## 3. Komponen yang Dibuat / Dimodifikasi

1. **Toolbar Menu (`app/src/main/res/menu/toolbar.xml`)**:
   - Tambah menu item `action_add_media` (`@drawable/ic_add`, `showAsAction="always"`).
2. **Layout & Activity Baru**:
   - `app/src/main/res/layout/activity_import_patient_media.xml`: Layout form pasien elegan berbasis Material Design.
   - `app/src/main/java/com/idn/kmed/cervexa/patient/ImportPatientMediaActivity.kt`: Logika form, validasi NIK, pemanggilan Photo Picker (`ActivityResultContracts.PickMultipleVisualMedia` dengan fallback `GetMultipleContents`), pemrosesan stream byte gambar ke `Snapshots/`, dan pembuatan `session.json`.
3. **Navigasi Toolbar (`HomeActivity.kt`)**:
   - Menangani klik menu `action_add_media` untuk membuka `ImportPatientMediaActivity`.
   - Mengatur visibility `action_add_media` agar aktif saat tab Media terbuka.
4. **Manifest (`AndroidManifest.xml`)**:
   - Mendaftarkan `ImportPatientMediaActivity` (portrait, label: "Impor Pemeriksaan").

---

## 4. Format Metadata & Penyimpanan Sesi
Menggunakan struktur kompatibel 100% dengan `MediaRepository.kt`:
- Path: `getExternalFilesDir(Environment.DIRECTORY_PICTURES)/Scans/<yyyy-MM-dd>/<NIK_NAMA>/`
- File `session.json`:
```json
{
  "nama": "Pasien Contoh",
  "nik": "3171012345670001",
  "nrm": "RM-00123",
  "rs": "Klinik Medika",
  "dob_utc": 631152000000,
  "saved_at": 1729000000000
}
```
- Gambar di folder `Snapshots/`:
  `result_20261015_091500_0.jpg`, `result_20261015_091500_1.jpg`, dst.

---

## 5. Kompatibilitas Fitur Lanjutan
Karena struktur folder dan `session.json` identik:
- **Media List**: Otomatis muncul pada tab Media dengan thumbnail foto terakhir.
- **AI Detection**: Menggunakan `OverlayRenderer` dan `ViaSegmentationHelper` tanpa modifikasi.
- **PDF Report**: Menghasilkan rekam medis PDF standar A4 dengan filter kompensasi warna cetak.
