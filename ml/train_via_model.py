import tensorflow as tf
from tensorflow.keras import layers, models
from tensorflow.keras.callbacks import ModelCheckpoint, EarlyStopping, ReduceLROnPlateau
import os

# Configuration
DATASET_DIR = "dataset_combined"
IMG_SIZE = (224, 224)
BATCH_SIZE = 16 # Small batch size for small dataset
EPOCHS = 100    # More epochs for better convergence on larger dataset
MODEL_SAVE_PATH = "via_model.h5"
TFLITE_SAVE_PATH = "via_model.tflite"

def build_model():
    # Use EfficientNetV2B0 for better performance and efficiency
    base_model = tf.keras.applications.EfficientNetV2B0(
        weights='imagenet', 
        include_top=False, 
        input_shape=(IMG_SIZE[0], IMG_SIZE[1], 3)
    )
    base_model.trainable = False  # Freeze base model initially

    model = models.Sequential([
        layers.Input(shape=(IMG_SIZE[0], IMG_SIZE[1], 3)),
        # Advanced data augmentation
        layers.RandomFlip("horizontal_and_vertical"),
        layers.RandomRotation(0.2),
        layers.RandomTranslation(0.1, 0.1),
        layers.RandomZoom(0.2),
        layers.RandomContrast(0.1),
        
        base_model,
        layers.GlobalAveragePooling2D(),
        layers.BatchNormalization(),
        layers.Dropout(0.4), # Higher dropout to prevent overfitting on small data
        layers.Dense(128, activation='relu'),
        layers.Dropout(0.2),
        layers.Dense(1, activation='sigmoid')
    ])

    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
        loss='binary_crossentropy',
        metrics=['accuracy', tf.keras.metrics.Precision(), tf.keras.metrics.Recall()]
    )
    return model

def train():
    if not os.path.exists(DATASET_DIR):
        print(f"Error: Dataset directory '{DATASET_DIR}' not found.")
        return

    # Load dataset
    # We use a validation split of 0.2
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

    # Performance optimization
    AUTOTUNE = tf.data.AUTOTUNE
    train_ds = train_ds.prefetch(buffer_size=AUTOTUNE)
    val_ds = val_ds.prefetch(buffer_size=AUTOTUNE)

    model = build_model()
    model.summary()

    callbacks = [
        ModelCheckpoint(filepath=MODEL_SAVE_PATH, save_best_only=True, monitor='val_loss'),
        EarlyStopping(patience=10, monitor='val_loss', restore_best_weights=True),
        ReduceLROnPlateau(monitor='val_loss', factor=0.5, patience=3, min_lr=1e-6)
    ]

    print("\n[Phase 1] Training classification head...")
    model.fit(
        train_ds,
        validation_data=val_ds,
        epochs=EPOCHS // 2,
        callbacks=callbacks
    )

    print("\n[Phase 2] Fine-tuning top layers...")
    base_model = model.layers[5] # Index 5 is the EfficientNetV2B0 base model
    base_model.trainable = True
    # Freeze all but the top 30 layers for fine-tuning
    for layer in base_model.layers[:-30]:
        layer.trainable = False

    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-5),
        loss='binary_crossentropy',
        metrics=['accuracy', tf.keras.metrics.Precision(), tf.keras.metrics.Recall()]
    )

    model.fit(
        train_ds,
        validation_data=val_ds,
        epochs=EPOCHS,
        callbacks=callbacks
    )

    convert_to_tflite(model)

def convert_to_tflite(model=None):
    if model is None:
        model = tf.keras.models.load_model(MODEL_SAVE_PATH)

    print("Converting to TFLite (Optimized for Mobile)...")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    # Full integer quantization could be added here if we had representative data
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()

    with open(TFLITE_SAVE_PATH, 'wb') as f:
        f.write(tflite_model)
    print(f"TFLite model saved to {TFLITE_SAVE_PATH}")

if __name__ == "__main__":
    train()
