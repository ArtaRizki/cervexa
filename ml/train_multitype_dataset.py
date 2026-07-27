"""
Cervexa - Multi-Type Cervical Cancer / VIA Lesion Training Script
==================================================================
Script ini dirancang khusus untuk melatih model klasifikasi serviks/VIA (Normal vs Abnormal)
berdasarkan dataset Type 1 (dan siap untuk penambahan Type 2 & Type 3 di masa depan).

Fitur Utama:
1. Multi-Directory Aggregator:
   - Otomatis memindai direktori yang diberikan (misal: TYPE1/Type_1, TYPE2, TYPE3)
   - Mengelompokkan folder apa pun yang berawalan 'abnormal' ke Kelas 0 (Abnormal)
   - Mengelompokkan folder apa pun yang berawalan 'normal' ke Kelas 1 (Normal)
2. 100% Kompatibel dengan Aplikasi Android Cervexa (ViaModelHelper.kt):
   - Input : 224x224 RGB
   - Output: Softmax 2 Kelas [1, 2] -> Index 0 = Abnormal, Index 1 = Normal
   - Tepat sesuai dengan tensor yang dibaca oleh kode Kotlin Cervexa!
3. Penanganan Imbalanced Class (Class Weighting):
   - Menyamakan pengaruh kelas Normal dan Abnormal saat training agar tidak bias.
4. Data Augmentation Khusus Gambar Medis/Mikroskop:
   - Rotasi acak, flip horizontal/vertikal, zoom, serta sedikit pergeseran kontras/cahaya
     agar model tangguh terhadap variasi pencahayaan lampu LED mikroskop MS2.
5. Evaluasi Medis Lengkap:
   - Confusion Matrix (TP, TN, FP, FN)
   - Sensitivity (Recall Kasus Abnormal - Sangat Krusial!)
   - Specificity (Kemampuan Mendeteksi Normal)
6. Otomatis Ekspor ke via_model.tflite & Deploy ke folder assets Android.

Cara Pakai:
    python train_multitype_dataset.py
"""

import os
import sys
import io
import warnings
import shutil
from pathlib import Path

# Ensure utf-8 output on Windows console and enable line buffering for live logs
if hasattr(sys.stdout, 'buffer'):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace', line_buffering=True)
if hasattr(sys.stderr, 'buffer'):
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace', line_buffering=True)

os.environ['TF_CPP_MIN_LOG_LEVEL'] = '2'
warnings.filterwarnings('ignore')

import tensorflow as tf
from tensorflow.keras import layers, models
from tensorflow.keras.callbacks import ModelCheckpoint, EarlyStopping, ReduceLROnPlateau
import numpy as np

# ==============================================================================
# 1. KONFIGURASI DIREKTORI & PARAMETER TRAINING
# ==============================================================================
# Daftar direktori dataset (bisa ditambahkan TYPE2, TYPE3 nanti saat sudah diunduh)
DATASET_DIRECTORIES = [
    r"C:\Users\it-arta\Downloads\TYPE1\Type_1",
    # r"C:\Users\it-arta\Downloads\TYPE2",  # <-- Buka komen & sesuaikan path saat Type 2 tersedia
    # r"C:\Users\it-arta\Downloads\TYPE3",  # <-- Buka komen & sesuaikan path saat Type 3 tersedia
]

IMG_SIZE         = (224, 224)
BATCH_SIZE       = 16
EPOCHS_PHASE_1   = 20   # Training Classification Head
EPOCHS_PHASE_2   = 40   # Fine-Tuning Top Layers
MODEL_H5_PATH    = "via_model_multitype.h5"
MODEL_TFLITE_PATH= "via_model.tflite"
ASSETS_DIR       = "../app/src/main/assets"

print("=" * 70)
print("  CERVEXA - MULTI-TYPE CERVICAL LESION AI TRAINER (TFLITE)")
print("=" * 70)

# ==============================================================================
# 2. PEMINDAIAN & PENGUMPULAN DATASET (ABNORMAL VS NORMAL)
# ==============================================================================
abnormal_paths = []
normal_paths = []

for base_dir in DATASET_DIRECTORIES:
    p = Path(base_dir)
    if not p.exists():
        print(f"[WARN] Peringatan: Direktori '{base_dir}' tidak ditemukan, dilewati.")
        continue
    
    print(f"\n[SCAN] Memindai direktori: {base_dir}")
    for sub in p.iterdir():
        if sub.is_dir():
            name_lower = sub.name.lower()
            imgs = list(sub.glob("*.jpg")) + list(sub.glob("*.png")) + list(sub.glob("*.jpeg"))
            if "abnormal" in name_lower:
                abnormal_paths.extend(imgs)
                print(f"   - [{sub.name}] (ABNORMAL) : {len(imgs)} gambar")
            elif "normal" in name_lower:
                normal_paths.extend(imgs)
                print(f"   - [{sub.name}] (NORMAL)   : {len(imgs)} gambar")
            else:
                print(f"   - [{sub.name}] (Dilewati, nama tidak mengandung normal/abnormal)")

total_abnormal = len(abnormal_paths)
total_normal = len(normal_paths)
total_imgs = total_abnormal + total_normal

print(f"\n[INFO] TOTAL KESELURUHAN DATASET:")
print(f"   - ABNORMAL (Index 0) : {total_abnormal} gambar")
print(f"   - NORMAL   (Index 1) : {total_normal} gambar")
print(f"   - Total Keseluruhan  : {total_imgs} gambar")

if total_imgs < 10:
    print("\n[ERROR] Jumlah gambar terlalu sedikit untuk dilatih!")
    sys.exit(1)

# ==============================================================================
# 3. PERSIAPAN DATASET TENSORFLOW (KATEGORICAL SOFTMAX [1, 2])
# ==============================================================================
# Kita membuat temporary list/array untuk membuat dataset yang konsisten
# Index 0 = Abnormal, Index 1 = Normal (PENTING untuk ViaModelHelper.kt di Android!)
all_filepaths = [str(p) for p in abnormal_paths] + [str(p) for p in normal_paths]
# Label one-hot / integer: 0 untuk abnormal, 1 untuk normal
all_labels = [0] * total_abnormal + [1] * total_normal

# Memeriksa integritas file (melewati gambar corrupt/rusak/EOF premature)
print("\n[CHECK] Memeriksa integritas file gambar (melewati gambar corrupt)...")
valid_files = []
valid_labels = []
for f, l in zip(all_filepaths, all_labels):
    try:
        raw = tf.io.read_file(f)
        _ = tf.image.decode_jpeg(raw, channels=3, try_recover_truncated=True, acceptable_fraction=0.5)
        valid_files.append(f)
        valid_labels.append(l)
    except Exception as e:
        print(f"   [SKIP] Melewati gambar rusak [{Path(f).name}]: {e}")

all_filepaths = np.array(valid_files)
all_labels = np.array(valid_labels)

# Shuffle data seed
np.random.seed(42)
indices = np.arange(len(all_filepaths))
np.random.shuffle(indices)

all_filepaths = all_filepaths[indices]
all_labels = all_labels[indices]

# Split 80% Train, 20% Validation
split_idx = int(len(all_filepaths) * 0.8)
train_files, val_files = all_filepaths[:split_idx], all_filepaths[split_idx:]
train_labels, val_labels = all_labels[:split_idx], all_labels[split_idx:]

print(f"\n[SPLIT] Pembagian Dataset (Setelah filter gambar valid):")
print(f"   - Data Latih (Train) : {len(train_files)} gambar")
print(f"   - Data Uji   (Val)   : {len(val_files)} gambar")

def parse_image_and_label(filepath, label):
    img = tf.io.read_file(filepath)
    # Gunakan try_recover_truncated=True agar tidak crash saat membaca header JPEG yang tidak sempurna
    img = tf.image.decode_jpeg(img, channels=3, try_recover_truncated=True, acceptable_fraction=0.5)
    img = tf.image.resize(img, IMG_SIZE)
    # One-hot encode: Index 0=Abnormal, Index 1=Normal -> shape (2,)
    label_onehot = tf.one_hot(label, depth=2)
    return img, label_onehot

AUTOTUNE = tf.data.AUTOTUNE

train_ds = tf.data.Dataset.from_tensor_slices((train_files, train_labels))
train_ds = train_ds.map(parse_image_and_label, num_parallel_calls=AUTOTUNE)
train_ds = train_ds.shuffle(buffer_size=1000).batch(BATCH_SIZE).prefetch(AUTOTUNE)

val_ds = tf.data.Dataset.from_tensor_slices((val_files, val_labels))
val_ds = val_ds.map(parse_image_and_label, num_parallel_calls=AUTOTUNE)
val_ds = val_ds.batch(BATCH_SIZE).prefetch(AUTOTUNE)

# ==============================================================================
# 4. CLASS WEIGHTS (MENGATASI CLASS IMBALANCE)
# ==============================================================================
# Karena Abnormal (1720) > Normal (750), kita beri bobot proporsional
# agar model tidak bias selalu menebak kelas yang lebih banyak.
class_weight = {
    0: total_imgs / (2.0 * max(1, total_abnormal)),  # Bobot Abnormal
    1: total_imgs / (2.0 * max(1, total_normal))     # Bobot Normal (lebih tinggi)
}
print(f"\n[WEIGHTS] Bobot Kelas (Class Weights):")
print(f"   - Kelas 0 (Abnormal) : {class_weight[0]:.2f}")
print(f"   - Kelas 1 (Normal)   : {class_weight[1]:.2f}")

# ==============================================================================
# 5. ARSITEKTUR MODEL (EFFICIENTNET-LITE / EFFICIENTNETV2B0 + SOFTMAX 2-CLASS)
# ==============================================================================
print("\n[MODEL] Membangun arsitektur model EfficientNetV2B0...")

base_model = tf.keras.applications.EfficientNetV2B0(
    weights='imagenet',
    include_top=False,
    input_shape=(IMG_SIZE[0], IMG_SIZE[1], 3)
)
base_model.trainable = False  # Bekukan feature extractor awal

# Data Augmentation khusus untuk gambar serviks mikroskop
data_augmentation = tf.keras.Sequential([
    layers.RandomFlip("horizontal_and_vertical"),
    layers.RandomRotation(0.25),
    layers.RandomZoom(0.15),
    layers.RandomContrast(0.15),
], name="data_augmentation")

inputs = layers.Input(shape=(IMG_SIZE[0], IMG_SIZE[1], 3))
x = data_augmentation(inputs)
x = base_model(x, training=False)
x = layers.GlobalAveragePooling2D()(x)
x = layers.BatchNormalization()(x)
x = layers.Dropout(0.4)(x)
x = layers.Dense(128, activation='relu')(x)
x = layers.Dropout(0.25)(x)
# CATATAN KRUSIAL: Gunakan Softmax 2 Kelas -> [1, 2]
# Index 0 = Abnormal, Index 1 = Normal (Kompatibel 100% dengan ViaModelHelper.kt)
outputs = layers.Dense(2, activation='softmax', name="classification_output")(x)

model = tf.keras.Model(inputs, outputs, name="Cervexa_Via_MultiType")

model.compile(
    optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
    loss='categorical_crossentropy',
    metrics=['accuracy']
)

model.summary()

# ==============================================================================
# 6. TRAINING PHASE 1: CLASSIFICATION HEAD
# ==============================================================================
callbacks = [
    ModelCheckpoint(MODEL_H5_PATH, save_best_only=True, monitor='val_accuracy', verbose=1),
    EarlyStopping(patience=8, monitor='val_accuracy', restore_best_weights=True, verbose=1),
    ReduceLROnPlateau(monitor='val_loss', factor=0.5, patience=3, min_lr=1e-6, verbose=1)
]

print(f"\n[PHASE 1] Melatih Head Classification ({EPOCHS_PHASE_1} epoch)...")
model.fit(
    train_ds,
    validation_data=val_ds,
    epochs=EPOCHS_PHASE_1,
    callbacks=callbacks,
    class_weight=class_weight,
    verbose=1
)

# ==============================================================================
# 7. TRAINING PHASE 2: FINE-TUNING TOP LAYERS
# ==============================================================================
print(f"\n[PHASE 2] Fine-Tuning Top Layers ({EPOCHS_PHASE_2} epoch)...")
base_model.trainable = True
# Bekukan sebagian besar layer awal, hanya latih 40 layer terakhir
for layer in base_model.layers[:-40]:
    layer.trainable = False

model.compile(
    optimizer=tf.keras.optimizers.Adam(learning_rate=1e-5),  # Learning rate sangat kecil untuk Fine-Tuning
    loss='categorical_crossentropy',
    metrics=['accuracy']
)

model.fit(
    train_ds,
    validation_data=val_ds,
    epochs=EPOCHS_PHASE_2,
    callbacks=callbacks,
    class_weight=class_weight,
    verbose=1
)

# ==============================================================================
# 8. EVALUASI MEDIS & CONFUSION MATRIX
# ==============================================================================
print("\n" + "=" * 70)
print("  EVALUASI AKURASI & MEDIS (CONFUSION MATRIX)")
print("=" * 70)

best_model = tf.keras.models.load_model(MODEL_H5_PATH)

y_true = []
y_pred = []

for images, labels in val_ds:
    preds = best_model.predict(images, verbose=0)
    # Argmax dari probability [abnormal_score, normal_score]
    y_pred.extend(np.argmax(preds, axis=1))
    y_true.extend(np.argmax(labels.numpy(), axis=1))

y_true = np.array(y_true)
y_pred = np.array(y_pred)

# Index 0 = Abnormal, Index 1 = Normal
TP = np.sum((y_true == 0) & (y_pred == 0))  # Abnormal terdeteksi Abnormal
TN = np.sum((y_true == 1) & (y_pred == 1))  # Normal terdeteksi Normal
FP = np.sum((y_true == 1) & (y_pred == 0))  # Normal terdeteksi Abnormal (False Alarm)
FN = np.sum((y_true == 0) & (y_pred == 1))  # Abnormal terdeteksi Normal (BAHAYA / Missed!)

accuracy = np.mean(y_true == y_pred) * 100.0
sensitivity = (TP / max(1, TP + FN)) * 100.0  # Recall untuk Abnormal
specificity = (TN / max(1, TN + FP)) * 100.0  # Kemampuan mendeteksi Normal

print(f"\n[STATS] Hasil Statistik Validation Set ({len(y_true)} gambar):")
print(f"   - Akurasi Keseluruhan (Accuracy) : {accuracy:.2f}%")
print(f"   - Sensitivitas (Recall Abnormal) : {sensitivity:.2f}%  <-- Krusial untuk diagnosa kanker!")
print(f"   - Spesifisitas (Recall Normal)   : {specificity:.2f}%")

print(f"\n[MATRIX] Confusion Matrix:")
print(f"   {'':17} | Prediksi ABNORMAL | Prediksi NORMAL")
print(f"   {'-'*54}")
print(f"   {'Aktual ABNORMAL':17} | {TP:17} | {FN:15} (False Negative - Bahaya!)")
print(f"   {'Aktual NORMAL':17} | {FP:17} | {TN:15}")

# ==============================================================================
# 9. EKSPOR KE TFLITE & AUTOMATIC DEPLOY KE ANDROID ASSETS
# ==============================================================================
print("\n[EXPORT] Mengonversi model ke format TensorFlow Lite (Mobile Optimized)...")
converter = tf.lite.TFLiteConverter.from_keras_model(best_model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
tflite_model = converter.convert()

with open(MODEL_TFLITE_PATH, 'wb') as f:
    f.write(tflite_model)

size_mb = len(tflite_model) / (1024 * 1024)
print(f"[OK] Berhasil mengekspor model: {MODEL_TFLITE_PATH} (Ukuran: {size_mb:.2f} MB)")

# Salin otomatis ke direktori app/src/main/assets jika ada
assets_path = Path(ASSETS_DIR) / "via_model.tflite"
if assets_path.parent.exists():
    shutil.copy(MODEL_TFLITE_PATH, assets_path)
    print(f"[DEPLOY] Model otomatis dipasang (deploy) ke dalam aplikasi: {assets_path}")
else:
    print(f"[INFO] Silakan salin manual file '{MODEL_TFLITE_PATH}' ke folder assets aplikasi Android Anda.")

print("\n[DONE] SEMUA PROSES SELESAI DENGAN SUKSES!")
print("=" * 70)
