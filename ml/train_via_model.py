import tensorflow as tf
from tensorflow.keras import layers, models
from tensorflow.keras.applications import EfficientNetB0
from tensorflow.keras.callbacks import ModelCheckpoint, EarlyStopping
import os

# Configuration
DATASET_DIR = "dataset_combined"  # Make sure to organize your downloaded images here into 'normal' and 'abnormal' folders
IMG_SIZE = (224, 224)
BATCH_SIZE = 32
EPOCHS = 20
MODEL_SAVE_PATH = "via_model.h5"
TFLITE_SAVE_PATH = "via_model.tflite"

def build_model():
    # Base model: EfficientNetB0 (pre-trained on ImageNet)
    base_model = EfficientNetB0(
        weights='imagenet', 
        include_top=False, 
        input_shape=(IMG_SIZE[0], IMG_SIZE[1], 3)
    )
    base_model.trainable = False  # Freeze base model initially

    # Classification head
    model = models.Sequential([
        base_model,
        layers.GlobalAveragePooling2D(),
        layers.Dropout(0.2),
        layers.Dense(1, activation='sigmoid')  # Binary classification
    ])

    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
        loss='binary_crossentropy',
        metrics=['accuracy']
    )
    return model

def train():
    if not os.path.exists(DATASET_DIR):
        print(f"Error: Dataset directory '{DATASET_DIR}' not found.")
        print("Please organize your images into 'normal' and 'abnormal' subdirectories within this folder.")
        return

    # Data loaders with augmentation
    train_ds = tf.keras.preprocessing.image_dataset_from_directory(
        DATASET_DIR,
        validation_split=0.2,
        subset="training",
        seed=123,
        image_size=IMG_SIZE,
        batch_size=BATCH_SIZE,
        label_mode='binary' # 0 for first folder, 1 for second folder
    )

    val_ds = tf.keras.preprocessing.image_dataset_from_directory(
        DATASET_DIR,
        validation_split=0.2,
        subset="validation",
        seed=123,
        image_size=IMG_SIZE,
        batch_size=BATCH_SIZE,
        label_mode='binary'
    )

    # Performance optimization
    AUTOTUNE = tf.data.AUTOTUNE
    train_ds = train_ds.cache().prefetch(buffer_size=AUTOTUNE)
    val_ds = val_ds.cache().prefetch(buffer_size=AUTOTUNE)

    # Data augmentation layer (can be added directly to model or dataset)
    data_augmentation = tf.keras.Sequential([
      layers.RandomFlip("horizontal_and_vertical"),
      layers.RandomRotation(0.2),
    ])

    model = build_model()

    callbacks = [
        ModelCheckpoint(filepath=MODEL_SAVE_PATH, save_best_only=True, monitor='val_loss'),
        EarlyStopping(patience=5, monitor='val_loss', restore_best_weights=True)
    ]

    print("Starting training...")
    history = model.fit(
        train_ds.map(lambda x, y: (data_augmentation(x, training=True), y)),
        validation_data=val_ds,
        epochs=EPOCHS,
        callbacks=callbacks
    )

    # Fine-tuning: unfreeze some top layers of the base model
    print("Starting fine-tuning...")
    base_model = model.layers[0]
    base_model.trainable = True
    for layer in base_model.layers[:-20]: # Freeze all but top 20 layers
        layer.trainable = False
        
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-5), # Lower learning rate for fine-tuning
        loss='binary_crossentropy',
        metrics=['accuracy']
    )

    model.fit(
        train_ds.map(lambda x, y: (data_augmentation(x, training=True), y)),
        validation_data=val_ds,
        epochs=EPOCHS // 2,
        callbacks=callbacks
    )

    convert_to_tflite(model)

def convert_to_tflite(model=None):
    if model is None:
        if not os.path.exists(MODEL_SAVE_PATH):
            print(f"Error: Model file '{MODEL_SAVE_PATH}' not found for conversion.")
            return
        model = tf.keras.models.load_model(MODEL_SAVE_PATH)

    print("Converting to TFLite...")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    # Enable optimizations (quantization)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()

    with open(TFLITE_SAVE_PATH, 'wb') as f:
        f.write(tflite_model)
    print(f"TFLite model saved to {TFLITE_SAVE_PATH}")

if __name__ == "__main__":
    train()
