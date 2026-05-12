import os
import requests
from bs4 import BeautifulSoup
import time

# Base URL for IARC Image Bank
BASE_URL = "https://screening.iarc.fr/"
LIST_PAGE = "cervicalimagebank.php"

# Categories to download
CATEGORIES = {
    "Normal": "Normal",
    "CIN1": "CIN 1",
    "CIN2": "CIN 2",
    "CIN3": "CIN 3",
    "Cancer": "Cancer"
}

OUTPUT_DIR = "dataset_combined"

def download_image(url, folder, filename):
    if not os.path.exists(folder):
        os.makedirs(folder)
    
    filepath = os.path.join(folder, filename)
    if os.path.exists(filepath):
        return
    
    try:
        response = requests.get(url, stream=True, timeout=10)
        if response.status_code == 200:
            with open(filepath, 'wb') as f:
                for chunk in response.iter_content(1024):
                    f.write(chunk)
            print(f"Downloaded: {filename}")
        else:
            print(f"Failed to download {url}: {response.status_code}")
    except Exception as e:
        print(f"Error downloading {url}: {e}")

def scrape_iarc():
    print("Starting IARC Image Bank scraping...")
    
    response = requests.get(BASE_URL + LIST_PAGE)
    if response.status_code != 200:
        print("Failed to access IARC website.")
        return
    
    soup = BeautifulSoup(response.text, 'html.parser')
    
    # Find all links
    links = soup.find_all('a')
    
    for link in links:
        category_name = link.text.strip()
        if category_name in CATEGORIES.values():
            folder_name = [k for k, v in CATEGORIES.items() if v == category_name][0]
            category_url = BASE_URL + link.get('href')
            
            print(f"\nProcessing category: {category_name}")
            
            # Go to category page
            cat_response = requests.get(category_url)
            if cat_response.status_code != 200:
                continue
            
            cat_soup = BeautifulSoup(cat_response.text, 'html.parser')
            
            # Find thumbnails
            # Images are usually in <img src="pic/thumb/..."> and link to large images
            img_links = cat_soup.find_all('a', href=lambda x: x and 'pic/' in x)
            
            for img_link in img_links:
                img_url = BASE_URL + img_link.get('href')
                img_name = img_url.split('/')[-1]
                
                # Check if it's a direct image link
                if img_url.endswith('.jpg') or img_url.endswith('.png'):
                    target_subfolder = "normal" if folder_name == "Normal" else "abnormal"
                    download_image(img_url, os.path.join(OUTPUT_DIR, target_subfolder), img_name)
                    time.sleep(0.5) # Be polite

if __name__ == "__main__":
    scrape_iarc()
