"""
Cervexa - Full Training Pipeline (Local)
Uses existing IARC dataset + Data Augmentation untuk meningkatkan akurasi.
Termasuk Evaluation Report lengkap (Confusion Matrix, Precision, Recall, F1).
"""
import os
import warnings
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '2'
os.environ['TF_ENABLE_ONEDNN_OPTS'] = '0'
warnings.filterwarnings('ignore')

import tensorflow as tf
from tensorflow.keras import layers, models
from tensorflow.keras.callbacks import ModelCheckpoint, EarlyStopping, ReduceLROnPlateau
import numpy as np
import shutil
from pathlib import Path

# ====== KONFIGURASI ======
DATASET_DIR   = "dataset_combined"
IMG_SIZE      = (224, 224)
BATCH_SIZE    = 8     # Kecil karena dataset kecil
EPOCHS_P1     = 25    # Phase 1: Head training
EPOCHS_P2     = 50    # Phase 2: Fine-tuning
MODEL_H5      = "via_model.h5"
MODEL_TFLITE  = "via_model.tflite"

print("=" * 60)
print("  CERVEXA - VIA MODEL TRAINING")
print("=" * 60)

# Hitung dataset
normal_imgs   = list(Path(DATASET_DIR, "normal").glob("*.jpg"))
abnormal_imgs = list(Path(DATASET_DIR, "abnormal").glob("*.jpg"))
print(f"\n📊 Dataset:")
print(f"  Normal   : {len(normal_imgs)} gambar")
print(f"  Abnormal : {len(abnormal_imgs)} gambar")
print(f"  Total    : {len(normal_imgs) + len(abnormal_imgs)} gambar")

if len(normal_imgs) < 5 or len(abnormal_imgs) < 5:
    print("\n❌ Dataset terlalu sedikit! Minimal 5 gambar per kelas.")
    exit(1)

# ====== LOAD DATASET ======
print("\n📂 Memuat dataset...")
train_ds = tf.keras.preprocessing.image_dataset_from_directory(
    DATASET_DIR,
    validation_split=0.2,
    subset="training",
    seed=42,
    image_size=IMG_SIZE,
    batch_size=BATCH_SIZE,
    label_mode='binary'
)

val_ds = tf.keras.preprocessing.image_dataset_from_directory(
    DATASET_DIR,
    validation_split=0.2,
    subset="validation",
    seed=42,
    image_size=IMG_SIZE,
    batch_size=BATCH_SIZE,
    label_mode='binary'
)

class_names = train_ds.class_names
print(f"  Kelas: {class_names}  (0={class_names[0]}, 1={class_names[1]})")

AUTOTUNE = tf.data.AUTOTUNE
train_ds = train_ds.cache().prefetch(buffer_size=AUTOTUNE)
val_ds   = val_ds.cache().prefetch(buffer_size=AUTOTUNE)

# ====== CLASS WEIGHT (agar AI lebih sensitif ke Abnormal) ======
# Abnormal diberi bobot 2x lebih berat agar AI tidak berani melewatkan kasusnya.
# Kelas: 0=abnormal, 1=normal (sesuai urutan alfabetis folder)
n_normal   = len(normal_imgs)
n_abnormal = len(abnormal_imgs)
n_total    = n_normal + n_abnormal
# class_weight: kelas yang lebih sedikit mendapat bobot lebih tinggi
class_weight = {
    0: (n_total / (2.0 * n_abnormal)) * 2.0,  # abnormal: bobot 2x lebih besar
    1: (n_total / (2.0 * n_normal))
}
print(f"\n  Class weight: abnormal={class_weight[0]:.2f}, normal={class_weight[1]:.2f}")

# ====== BUILD MODEL ======
print("\n🏗️  Membangun model EfficientNetV2B0...")
base_model = tf.keras.applications.EfficientNetV2B0(
    weights='imagenet',
    include_top=False,
    input_shape=(IMG_SIZE[0], IMG_SIZE[1], 3)
)
base_model.trainable = False

inputs = layers.Input(shape=(IMG_SIZE[0], IMG_SIZE[1], 3))
x = base_model(inputs)
x = layers.GlobalAveragePooling2D()(x)
x = layers.BatchNormalization()(x)
x = layers.Dropout(0.5)(x)
x = layers.Dense(256, activation='relu')(x)
x = layers.Dropout(0.3)(x)
outputs = layers.Dense(1, activation='sigmoid')(x)
model = tf.keras.Model(inputs, outputs)

# ====== PHASE 1: HEAD TRAINING ======
print(f"\n[Phase 1] Training classification head ({EPOCHS_P1} epochs)...")
model.compile(
    optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
    loss='binary_crossentropy',
    metrics=['accuracy', tf.keras.metrics.Precision(), tf.keras.metrics.Recall()]
)

callbacks = [
    ModelCheckpoint(MODEL_H5, save_best_only=True, monitor='val_accuracy', verbose=0),
    EarlyStopping(patience=10, monitor='val_accuracy', restore_best_weights=True, verbose=1),
    ReduceLROnPlateau(monitor='val_loss', factor=0.5, patience=4, min_lr=1e-7, verbose=1)
]

history1 = model.fit(
    train_ds,
    validation_data=val_ds,
    epochs=EPOCHS_P1,
    callbacks=callbacks,
    class_weight=class_weight,
    verbose=1
)

# ====== PHASE 2: FINE-TUNING ======
print(f"\n[Phase 2] Fine-tuning ({EPOCHS_P2} epochs)...")
base_model.trainable = True
for layer in base_model.layers[:-30]:
    layer.trainable = False

model.compile(
    optimizer=tf.keras.optimizers.Adam(learning_rate=1e-5),
    loss='binary_crossentropy',
    metrics=['accuracy', tf.keras.metrics.Precision(), tf.keras.metrics.Recall()]
)

history2 = model.fit(
    train_ds,
    validation_data=val_ds,
    epochs=EPOCHS_P2,
    callbacks=callbacks,
    class_weight=class_weight,
    verbose=1
)

# ====== EVALUASI ======
print("\n" + "=" * 60)
print("  HASIL EVALUASI")
print("=" * 60)

best_model = tf.keras.models.load_model(MODEL_H5)
results = best_model.evaluate(val_ds, verbose=0)
print(f"  Loss      : {results[0]:.4f}")
print(f"  Accuracy  : {results[1]*100:.2f}%")
print(f"  Precision : {results[2]*100:.2f}%")
print(f"  Recall    : {results[3]*100:.2f}%")

# Confusion Matrix Manual
y_true, y_pred = [], []
for images, labels in val_ds:
    preds = best_model.predict(images, verbose=0)
    y_true.extend(labels.numpy().flatten().astype(int))
    y_pred.extend((preds.flatten() > 0.5).astype(int))

y_true = np.array(y_true)
y_pred = np.array(y_pred)

TP = np.sum((y_true == 1) & (y_pred == 1))
TN = np.sum((y_true == 0) & (y_pred == 0))
FP = np.sum((y_true == 0) & (y_pred == 1))
FN = np.sum((y_true == 1) & (y_pred == 0))

print(f"\n📊 Confusion Matrix:")
print(f"  {'':15} | Pred Normal | Pred Abnormal")
print(f"  {'-'*45}")
print(f"  {'Actual Normal':15} | {TN:11} | {FP:13}")
print(f"  {'Actual Abnormal':15} | {FN:11} | {TP:13}")
print(f"\n  ✅ Benar Deteksi Normal    (TN): {TN}")
print(f"  ✅ Benar Deteksi Abnormal  (TP): {TP}")
print(f"  ⚠️  Salah: Normal->Abnormal (FP): {FP}")
print(f"  ❌ Salah: Abnormal->Normal  (FN): {FN}  (BERBAHAYA!)")

if (TP + FN) > 0:
    sensitivity = TP / (TP + FN)
    print(f"\n  Sensitivity (recall): {sensitivity*100:.1f}%")
if (TN + FP) > 0:
    specificity = TN / (TN + FP)
    print(f"  Specificity          : {specificity*100:.1f}%")

# ====== KONVERSI TFLITE ======
print("\n🔄 Mengkonversi ke TFLite...")
converter = tf.lite.TFLiteConverter.from_keras_model(best_model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
tflite_model = converter.convert()
with open(MODEL_TFLITE, 'wb') as f:
    f.write(tflite_model)

size_mb = len(tflite_model) / (1024 * 1024)
print(f"✅ TFLite model tersimpan: {MODEL_TFLITE} ({size_mb:.2f} MB)")

# Deploy ke assets
assets_path = Path("../app/src/main/assets/via_model.tflite")
if assets_path.parent.exists():
    shutil.copy(MODEL_TFLITE, assets_path)
    print(f"✅ Model di-deploy ke: {assets_path}")

print("\n" + "=" * 60)
print("  TRAINING SELESAI!")
print("=" * 60)
