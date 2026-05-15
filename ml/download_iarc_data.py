import os
import requests
import time

# Expanded list of Case IDs from IARC VIA Atlas
NORMAL_IDS = [
    "AFC", "AJL", "AGY", "AJE", "AIF", "AJG", "AGW", "AMK", "AFH", "ANC", 
    "AIH", "AHT", "AGV", "AMT", "AHE", "AHO", "AIC", "AHV", "AHG", "AFO", 
    "AIT", "AIO", "AJD", "AHX", "AHM", "AHJ", "AIB", "AIU", "AJA", "AIY", 
    "AJB", "AIE", "AIZ", "AJH", "AIW", "AIR", "AHH", "AGU", "AIV", "AIQ", 
    "AIM", "AIL", "AII", "AIG", "AJI", "AJF"
]

ABNORMAL_IDS = [
    "AEP", "AJP", "ADO", "ACS", "AJQ", "AEV", "AJN", "AJW", "AJV", "AJU", 
    "AJS", "AJX", "AJZ", "AKA", "AKC", "ALM", "ALN", "ALP", "AKN", "AKO", 
    "AKP", "AKQ", "AKR", "AKW", "AKY", "AKZ", "ALB", "ALA", "ALH", "ALJ", 
    "ALF", "ALK", "ALL", "ALO", "ALR", "ALS", "ALU", "ALT", "ALZ", "AMA", 
    "AMC", "AMB", "AMD", "AME", "AMF", "AMI", "AMH", "AMN", "AMM", "AMO", 
    "AMP", "AMQ", "AMR", "AMU", "AMX", "AMY", "ABP", "AEB", "ABR", "ABI", 
    "AET", "AFA", "AEM", "AAF", "ACE", "AAC", "AAR", "AEY", "AEU", "AFT", 
    "AFV", "AFW", "AFX", "AFY", "AFZ", "AGA", "AGB", "AGC", "AGD", "AGE", 
    "AGF", "AGG", "AGH", "AGI", "AGJ", "AGK", "AGL", "AGM", "AGN", "AGO", 
    "AGP", "AGQ", "AGR", "AGS", "AGT"
]

BASE_URL = "https://screening.iarc.fr/viavilipic/"
OUTPUT_DIR = "dataset_combined"

def download():
    print("Starting expanded download of real clinical images...")
    
    tasks = [
        ("normal", NORMAL_IDS),
        ("abnormal", ABNORMAL_IDS)
    ]
    
    total_downloaded = 0
    
    for category, ids in tasks:
        folder = os.path.join(OUTPUT_DIR, category)
        os.makedirs(folder, exist_ok=True)
        
        print(f"\nProcessing category: {category} ({len(ids)} cases)")
        
        for case_id in ids:
            # Try index 0 and 1 for each case (usually 0 is the main one)
            for idx in [0, 1]:
                filename = f"{case_id}{idx}a.jpg"
                url = f"{BASE_URL}{filename}"
                filepath = os.path.join(folder, filename)
                
                if os.path.exists(filepath):
                    continue
                    
                try:
                    # Use a small delay to be polite to the server
                    response = requests.get(url, timeout=15)
                    if response.status_code == 200:
                        with open(filepath, 'wb') as f:
                            f.write(response.content)
                        print(f"  [OK] {filename}")
                        total_downloaded += 1
                        time.sleep(0.1)
                    elif response.status_code == 404 and idx == 1:
                        # Skip if second index doesn't exist
                        continue
                    else:
                        print(f"  [Skip] {filename}: {response.status_code}")
                except Exception as e:
                    print(f"  [Error] {filename}: {e}")

    print(f"\nDownload complete. Total new images: {total_downloaded}")

if __name__ == "__main__":
    download()
