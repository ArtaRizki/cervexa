import os
import numpy as np
from PIL import Image

def create_dummy_images(folder, count, is_abnormal=False):
    os.makedirs(folder, exist_ok=True)
    for i in range(count):
        # Create a random image (224x224)
        if is_abnormal:
            # Abnormal has more white/red patches
            img_array = np.random.randint(150, 255, (224, 224, 3), dtype=np.uint8)
        else:
            # Normal is more pinkish/uniform
            img_array = np.random.randint(100, 200, (224, 224, 3), dtype=np.uint8)
            img_array[:, :, 0] = np.random.randint(200, 255, (224, 224)) # more red
            
        img = Image.fromarray(img_array)
        img.save(os.path.join(folder, f"sample_{i}.jpg"))

print("Generating dummy images for training simulation...")
create_dummy_images("dataset_combined/normal", 50, is_abnormal=False)
create_dummy_images("dataset_combined/abnormal", 50, is_abnormal=True)
print("Dummy dataset created successfully!")
