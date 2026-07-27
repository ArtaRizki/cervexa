---
name: cervical-lesion-model-training
description: >
  Panduan dan best practices lengkap untuk melatih model Machine Learning
  klasifikasi lesi serviks/VIA (Normal vs Abnormal) menggunakan format
  TensorFlow Lite (.tflite) berukuran ringan (~6.9 MB) untuk aplikasi Android Cervexa.
  Gunakan skill ini saat melatih dataset Type 1, Type 2, atau Type 3, mengonversi model
  ke TFLite, menangani imbalanced class, memperbaiki error gambar JPEG yang rusak/corrupt,
  atau saat developer lain memerlukan instruksi training model AI serviks Cervexa.
---

# Cervexa AI / VIA Lesion Model Training — Developer Skill & Guide

Skill ini berisi pengetahuan teknis, spesifikasi tensor, struktur dataset, dan panduan *training/fine-tuning* untuk model AI klasifikasi lesi serviks (Metode VIA - Visual Inspection with Acetic Acid) yang berjalan secara *offline/edge inference* di aplikasi Android Cervexa (`com.idn.kmed.cervexa`).

---

## 1. Spesifikasi Model & Kompatibilitas Android (`ViaModelHelper.kt`)

Aplikasi Android Cervexa menjalankan inferensi menggunakan **TensorFlow Lite** dengan spesifikasi yang **sangat ketat**. Setiap model baru yang dilatih **WAJIB** mematuhi aturan spesifikasi tensor berikut agar kompatibel dengan kode di [`app/src/main/java/com/idn/kmed/cervexa/ml/ViaModelHelper.kt`](file:///C:/Users/it-arta/projects/cervexa/app/src/main/java/com/idn/kmed/cervexa/ml/ViaModelHelper.kt):

| Parameter | Spesifikasi Wajib | Keterangan |
|---|---|---|
| **Input Shape** | `[1, 224, 224, 3]` | Resolusi 224x224 piksel, RGB, format Float32 |
| **Output Shape** | **`[1, 2]`** | **Softmax 2-Class Activation** (bukan Sigmoid 1 unit!) |
| **Index 0 (Class 0)** | **`ABNORMAL`** | Probabilitas kelas lesi serviks abnormal |
| **Index 1 (Class 1)** | **`NORMAL`** | Probabilitas kelas serviks normal |
| **Classification Threshold**| `0.5f` | Jika `scores[0] > 0.5f` → diklasifikasikan sebagai `ABNORMAL` |
| **Ukuran Model Target** | `5 MB – 8 MB` | Gunakan *post-training dynamic range quantization* jika diperlukan |

> [!CAUTION]
> **JANGAN PERNAH** mengubah urutan indeks kelas menjadi `0 = Normal, 1 = Abnormal`. Kode Kotlin di `ViaModelHelper.kt` secara hardcode membaca `scores[0]` sebagai probabilitas `Abnormal`. Secara alfabetis, folder `"abnormal"` akan selalu diurutkan ke indeks 0 oleh TensorFlow Keras.

---

## 2. Struktur Dataset & Skrip Utama (`ml/train_multitype_dataset.py`)

Skrip resmi untuk melakukan training dan evaluasi ada di dalam repositori:
- **Script File**: [`ml/train_multitype_dataset.py`](file:///C:/Users/it-arta/projects/cervexa/ml/train_multitype_dataset.py)
- **Launcher Batch**: [`ml/run_training.bat`](file:///C:/Users/it-arta/projects/cervexa/ml/run_training.bat)

### A. Mendukung 4 Sumber Dataset (Type 1, Type 2, Type 3, additional_Type_3_v2)
Skrip sudah dilengkapi fitur *Multi-Directory Scanner* untuk menggabungkan 4 sumber dataset servis yang ada:
1. `Type_1` (Dataset utama awal)
2. `TYPE_2` (Hasil ekstraksi dari `TYPE_2.zip`)
3. `Type_3` (Dataset tipe 3)
4. `additional_Type_3_v2` (Tambahan variasi tipe 3 versi 2)

Untuk melatih keempatnya sekaligus, cukup buka komentar path pada `DATASET_DIRECTORIES` di `ml/train_multitype_dataset.py`:
```python
DATASET_DIRECTORIES = [
    r"C:\Users\it-arta\Downloads\TYPE1\Type_1",
    r"C:\Users\it-arta\Downloads\TYPE_2",                 # <-- Dari ekstrak TYPE_2.zip
    r"C:\Users\it-arta\Downloads\Type_3",                 # <-- Folder Type_3
    r"C:\Users\it-arta\Downloads\additional_Type_3_v2",   # <-- Folder additional_Type_3_v2
]
```
Skrip akan otomatis memindai, menyaring gambar rusak, dan menggabungkan seluruh subfolder berawalan `abnormal*` ke Kelas 0, dan subfolder berawalan `normal*` ke Kelas 1 dari semua direktori yang ada di dalam list.

---

## 3. Strategi Training & Arsitektur Deep Learning

### A. Arsitektur Model (EfficientNetV2B0)
- Kita menggunakan **`tf.keras.applications.EfficientNetV2B0`** sebagai *feature extractor* dengan bobot awal *ImageNet*.
- Layer klasifikasi atas (*top layers*):
  - `GlobalAveragePooling2D()` -> `BatchNormalization()` -> `Dropout(0.4)`
  - `Dense(128, activation='relu')` -> `Dropout(0.25)`
  - **`Dense(2, activation='softmax')`** (Wajib 2 kelas agar sesuai output tensor `[1, 2]`)

### B. Penanganan Ketimpangan Kelas (Class Imbalance)
Dalam dataset medis/VIA, jumlah gambar `Abnormal` sering kali jauh lebih banyak dibanding `Normal` (contoh: di Type 1 terdapat 1.720 Abnormal vs 750 Normal).
- Skrip secara otomatis menghitung proporsi **`class_weight`**:
  ```python
  class_weight = {
      0: total_imgs / (2.0 * total_abnormal),  # Bobot Abnormal (~0.72)
      1: total_imgs / (2.0 * total_normal)     # Bobot Normal (~1.65)
  }
  ```
- Tanpa `class_weight`, model akan cenderung bias dan selalu menebak `Abnormal`.

### C. Augmentasi Medis Khusus Mikroskop MS2
Kamera mikroskop Elikliv MS2 memiliki lampu LED dengan intensitas yang bervariasi. Untuk membuat model kebal terhadap perubahan cahaya dan rotasi kamera saat pemeriksaan:
- Gunakan `RandomFlip("horizontal_and_vertical")`
- Gunakan `RandomRotation(0.25)` dan `RandomZoom(0.15)`
- Gunakan `RandomContrast(0.15)`

---

## 4. Pelajaran Penting & Solusi Troubleshooting (Gotchas)

### 1. Masalah: Crash `Premature end of JPEG file` / `jpeg::Uncompress failed`
- **Gejala**: Saat training mencapai Epoch 1, proses tiba-tiba terhenti (*crash*) dengan error `InvalidArgumentError: Graph execution error: [[{{node DecodeJpeg}}]]`.
- **Penyebab**: Terdapat file gambar `.jpg` dalam dataset yang tidak sempurna/corrupt (terputus saat proses download atau copy).
- **Solusi yang Harus Diterapkan**:
  1. **Pre-Training Integrity Filter**: Gunakan loop verifikasi sebelum dataset dimasukkan ke Keras untuk mendeteksi dan menyingkirkan file rusak secara otomatis:
     ```python
     for f, l in zip(all_filepaths, all_labels):
         try:
             raw = tf.io.read_file(f)
             _ = tf.image.decode_jpeg(raw, channels=3, try_recover_truncated=True, acceptable_fraction=0.5)
             valid_files.append(f)
             valid_labels.append(l)
         except Exception as e:
             print(f"   [SKIP] Melewati gambar rusak [{Path(f).name}]: {e}")
     ```
  2. **Aktifkan Pemulihan JPEG (`try_recover_truncated=True`)**: Pada fungsi `parse_image_and_label()`, selalu gunakan:
     ```python
     img = tf.image.decode_jpeg(img, channels=3, try_recover_truncated=True, acceptable_fraction=0.5)
     ```

### 2. Masalah: `UnicodeEncodeError: 'charmap' codec can't encode character...` di Windows
- **Gejala**: Script Python error dengan pesan `UnicodeEncodeError` saat mencoba mencetak emoji (misal `📁`, `⚠️`, `📊`) di PowerShell / command prompt Windows.
- **Penyebab**: Terminal Windows menggunakan encoding default `cp1252` yang tidak dapat mengenali karakter emoji Unicode.
- **Solusi**:
  1. Hindari penggunaan emoji pada perintah `print()` di dalam skrip training. Gunakan tag ASCII standar seperti `[INFO]`, `[SCAN]`, `[CHECK]`, `[WARN]`, `[ERROR]`, `[OK]`.
  2. Gunakan *wrapper* UTF-8 pada `sys.stdout` dan `sys.stderr` di awal script:
     ```python
     if hasattr(sys.stdout, 'buffer'):
         sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace', line_buffering=True)
     ```

### 3. Masalah: Log Training Tidak Muncul secara Real-Time di Background Task
- **Penyebab**: Python melakukan buffering output (*block buffering*) secara default apabila dijalankan bukan dari interactive TTY.
- **Solusi**: Gunakan flag `-u` (*unbuffered*) saat memanggil Python:
  ```powershell
  py -3.12 -u ml/train_multitype_dataset.py
  ```

---

## 5. Cara Cepat Eksekusi Training (Untuk Developer Lain)

### Cara 1: Menggunakan Launcher Batch Otomatis (Rekomendasi)
Cukup jalankan file batch dari terminal atau klik ganda di Windows Explorer:
```powershell
.\ml\run_training.bat
```
*(Launcher akan otomatis mencarikan Python 3.12 yang memiliki TensorFlow).*

### Cara 2: Menjalankan Langsung via Python 3.12 (Unbuffered)
```powershell
py -3.12 -u ml/train_multitype_dataset.py
```

### Hasil Akhir Training
Setelah evaluasi Confusion Matrix selesai dicetak, skrip akan:
1. Menyimpan model Keras ke `ml/via_model_multitype.h5`
2. Mengonversi dan mengoptimalkan ke **`ml/via_model.tflite`**
3. **Otomatis menyalin (deploy)** file `via_model.tflite` ke folder **`app/src/main/assets/via_model.tflite`** sehingga aplikasi Android siap dibuild dengan model terbaru!

---

## 6. Laporan Evaluasi Medis yang Diperhatikan
Saat proses evaluasi di akhir training, perhatikan 3 metrik medis utama:
1. **Sensitivity (Recall Abnormal)**: Kemampuan model mendeteksi pasien dengan lesi VIA abnormal. **Target minimal: > 85% - 90%**.
2. **Specificity (Recall Normal)**: Kemampuan model mengenali pasien sehat tanpa memberi alarm palsu berlebih.
3. **False Negative (FN)**: Jumlah kasus aktual Abnormal yang diprediksi Normal. Angka ini **harus serendah mungkin** karena kesalahan negatif palsu sangat berbahaya dalam diagnosa kanker serviks.

---

## 7. Strategi & Workflow Training di PC Terpisah / Cloud GPU (Sangat Direkomendasikan!)

Ketika dataset berkembang menjadi 4 sumber (`Type_1`, `TYPE_2`, `Type_3`, dan `additional_Type_3_v2`), jumlah gambar bisa mencapai **10.000+ gambar medis**. Melatih dataset sebesar ini di laptop/PC pengembangan kerja biasa dengan CPU (*tanpa GPU NVIDIA CUDA*) memiliki beberapa kelemahan:
- **Waktu Training Sangat Lama**: Pada CPU native Windows, 1 epoch memakan waktu ~5–10 menit. Total 60 epoch bisa memakan waktu 6–10 jam.
- **Konsumsi RAM/CPU Tinggi**: Membuat PC terasa lambat untuk aktivitas pengembangan Android Studio sekaligus.

### Keuntungan Melakukan Training di PC Terpisah / Server GPU:
1. **Akselerasi Ekstrem (GPU NVIDIA / Colab / Linux / WSL2)**: Pada GPU seperti RTX 3060/4060 atau Google Colab T4/V100, 1 epoch hanya memakan waktu **5–15 detik**. Seluruh proses 60 epoch selesai dalam **< 15 menit**.
2. **Isolasi Beban Kerja**: PC utama Anda tetap ringan untuk koding Android, testing perangkat MS2, dan debugging APK.

### Alur Kerja (Workflow) Pemindahan Training ke PC Terpisah:
```
[PC TERPISAH / CLOUD GPU]
 ├── 1. Unduh 4 folder dataset (Type_1, TYPE_2, Type_3, additional_Type_3_v2)
 ├── 2. Jalankan skrip: py -3.12 -u ml/train_multitype_dataset.py
 └── 3. Skrip menghasilkan model optimal: via_model.tflite (~6.9 MB)
        │
        │  (Cukup kirim / unduh file via_model.tflite saja!)
        ▼
[PC PENGEMBANGAN UTAMA / REPO ANDROID]
 ├── 1. Ganti file di: app/src/main/assets/via_model.tflite
 └── 2. Build APK: .\gradlew assembleDebug
```

> [!TIP]
> **Anda tidak perlu menyalin ribuan gambar dataset kembali ke PC pengembangan utama ini!** Cukup salin **hanya 1 file hasil akhir (`via_model.tflite` berukuran ~6.9 MB)** dari PC Terpisah ke folder `app/src/main/assets/` di repositori ini, dan langsung build APK. Aplikasi Android Cervexa akan langsung mendapatkan seluruh peningkatan kecerdasan dari 4 dataset tersebut!
