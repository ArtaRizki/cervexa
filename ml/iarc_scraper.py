import os
import requests
from bs4 import BeautifulSoup
import time
import re

# Base URL for IARC Atlas
BASE_URL = "https://screening.iarc.fr/"
ATLAS_URL = "https://screening.iarc.fr/atlasviadiag.php"

# Categories mapping to our dataset folders
CATEGORIES = {
    "Negative": "normal",
    "CIN1": "abnormal",
    "CIN2": "abnormal",
    "CIN3": "abnormal",
    "Cancer": "abnormal",
    "Positive": "abnormal"
}

OUTPUT_DIR = "dataset_combined"

def download_image(url, folder, filename):
    if not os.path.exists(folder):
        os.makedirs(folder)
    
    filepath = os.path.join(folder, filename)
    if os.path.exists(filepath):
        # print(f"Skipping: {filename} (already exists)")
        return
    
    try:
        response = requests.get(url, stream=True, timeout=15)
        if response.status_code == 200:
            with open(filepath, 'wb') as f:
                for chunk in response.iter_content(1024):
                    f.write(chunk)
            print(f"Downloaded: {filename} to {folder}")
        else:
            print(f"Failed to download {url}: {response.status_code}")
    except Exception as e:
        print(f"Error downloading {url}: {e}")

def scrape_atlas():
    print("Starting IARC VIA Atlas scraping...")
    
    # Get the main atlas page
    response = requests.get(ATLAS_URL)
    if response.status_code != 200:
        print("Failed to access IARC atlas page.")
        return
    
    soup = BeautifulSoup(response.text, 'html.parser')
    
    # Find all links to atlasviadiag_detail.php
    case_links = soup.find_all('a', href=re.compile(r'atlasviadiag_detail\.php\?Id='))
    print(f"Found {len(case_links)} case links.")
    
    for link in case_links:
        href = link.get('href')
        # Extract ID and FinalDiag from URL
        # Example: atlasviadiag_detail.php?Id=AFC&FinalDiag=Negative
        match_id = re.search(r'Id=([^&]+)', href)
        match_diag = re.search(r'FinalDiag=([^&]+)', href)
        
        if not match_id or not match_diag:
            continue
            
        case_id = match_id.group(1)
        diag = match_diag.group(1)
        
        # Map diagnosis to our categories
        target_folder = "abnormal"
        for key, folder in CATEGORIES.items():
            if key.lower() in diag.lower():
                target_folder = folder
                break
        
        print(f"Processing Case {case_id} ({diag}) -> {target_folder}")
        
        # Go to case detail page to find images
        detail_url = BASE_URL + href
        try:
            detail_res = requests.get(detail_url, timeout=10)
            if detail_res.status_code != 200:
                continue
            
            detail_soup = BeautifulSoup(detail_res.text, 'html.parser')
            
            # Images are in <img src="viavilipic/...">
            imgs = detail_soup.find_all('img', src=re.compile(r'viavilipic/'))
            for img in imgs:
                img_src = img.get('src')
                img_url = BASE_URL + img_src
                img_name = img_src.split('/')[-1]
                
                # Only download if it's likely a full image (not just small thumbs if any)
                # But in this atlas, they seem to be the primary images
                download_image(img_url, os.path.join(OUTPUT_DIR, target_folder), f"{case_id}_{img_name}")
                time.sleep(0.2) # Polite delay
                
        except Exception as e:
            print(f"Error processing case {case_id}: {e}")

if __name__ == "__main__":
    scrape_atlas()
