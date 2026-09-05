# 🖨️ Cervexa Print Bridge Server

Aplikasi Print Gateway ringan untuk komputer/laptop klinik (Windows / Mac / Linux).
Memungkinkan **Smart TV Cervexa** mencetak berkas rekam medis PDF secara langsung ke printer klinik (misal: **HP Smart Tank 480/580**, Epson, Canon) melalui jaringan kabel LAN / Wi-Fi tanpa perlu aplikasi tambahan di Smart TV.

---

## 🚀 Cara Menjalankan di Komputer Klinik

1. **Pastikan Komputer dan Smart TV Terhubung ke Jaringan yang Sama:**
   * **Smart TV:** Colokkan kabel LAN (Ethernet RJ45) dari Smart TV ke router klinik. (Wi-Fi TV tetap terhubung ke hotspot kamera mikroskop Elikliv MS2).
   * **Komputer Klinik:** Terhubung ke router klinik yang sama (via kabel LAN atau Wi-Fi klinik) dan printer HP sudah terpasang di komputer ini.

2. **Jalankan Server:**
   * Cukup **klik dua kali (*double click*)** berkas:
     ```
     run_bridge.bat
     ```
   * Jendela terminal akan muncul dan menampilkan alamat IP komputer, contoh:
     ```
     =======================================================
       CERVEXA PRINT BRIDGE SERVER (v1.0)
     =======================================================
       • Status          : AKTIF & MENDENGARKAN
       • Alamat IP Lokal : 192.168.1.50:9123
       • Printer Default : HP Smart Tank 480 series
     =======================================================
     ```

3. **Masukkan IP ke Smart TV Cervexa:**
   * Buka aplikasi Cervexa di Smart TV $\rightarrow$ Masuk ke menu **Pengaturan** (ikon gear di pojok kanan atas).
   * Cari bagian **Print Bridge (Cetak Jaringan)**.
   * Aktifkan toggle, lalu masukkan IP yang tertera di komputer (contoh: `192.168.1.50:9123`).
   * Tekan tombol **"Uji Koneksi"** $\rightarrow$ Pastikan muncul status *"Terhubung ke HP Smart Tank"*.

4. **Selesai!**
   Setiap kali dokter menekan tombol **"Cetak"** di halaman Media Smart TV, dokumen akan otomatis dikirim dan dicetak ke printer HP.

---

## 💡 Opsional: Silent Background Printing (Tanpa Muncul Jendela Pop-up)

Secara bawaan (*default*), Print Bridge akan menggunakan print verb Windows.
Agar proses cetak benar-benar sunyi (*silent*) tanpa jendela pop-up apapun di layar kasir/dokter:
1. Unduh executable portabel gratis **SumatraPDF** dari situs resmi:
   `https://www.sumatrapdfreader.org/download-free-pdf-viewer` (pilih versi *Portable*).
2. Letakkan file `SumatraPDF.exe` di dalam folder:
   ```
   tools/print-bridge/bin/SumatraPDF.exe
   ```
3. Print Bridge akan otomatis mendeteksi dan menggunakannya untuk mencetak di latar belakang (*silent mode*).
