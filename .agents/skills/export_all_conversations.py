import json
import re
import shutil
from datetime import datetime
from pathlib import Path

TRANSCRIPT_PATH = Path(r"C:\Users\it-arta\.gemini\antigravity\brain\d34d4348-c575-4c80-b16f-2f00055bda25\.system_generated\logs\transcript_full.jsonl")
EXPORT_PATH = Path(r"c:\Users\it-arta\projects\cervexa\conversation_export.md")
DOWNLOADS_EXPORT_PATH = Path(r"C:\Users\it-arta\Downloads\conversation_export.md")

steps = []
with TRANSCRIPT_PATH.open("r", encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        try:
            steps.append(json.loads(line))
        except Exception:
            pass

sessions = []
current_session = None

for st in steps:
    stype = st.get("type", "")
    if stype == "USER_INPUT":
        raw_content = st.get("content", "") or ""
        m = re.search(r"<USER_REQUEST>\s*(.*?)\s*</USER_REQUEST>", raw_content, flags=re.S)
        user_text = (m.group(1) if m else raw_content).strip()
        media = st.get("media") or []
        if not user_text and media:
            user_text = f"[Lampiran Foto / Screenshot Pengujian ({len(media)} berkas)]"
        elif media:
            user_text = f"{user_text}\n\n*(Lampiran: {len(media)} berkas gambar/screenshot)*"
        current_session = {
            "user": user_text,
            "assistant_texts": [],
            "actions": []
        }
        sessions.append(current_session)
    elif stype == "PLANNER_RESPONSE" and current_session is not None:
        c = (st.get("content") or "").strip()
        if c:
            current_session["assistant_texts"].append(c)
        for tc in (st.get("tool_calls") or []):
            tname = tc.get("name") or tc.get("tool_name") or ""
            args = tc.get("arguments") or tc.get("args") or {}
            if isinstance(args, str):
                try:
                    args = json.loads(args)
                except Exception:
                    args = {}
            if tname in ("edit_file", "write_file"):
                p = Path(args.get("Path", "")).name
                if p:
                    current_session["actions"].append(f"**Perubahan Berkas**: `{p}`")
            elif tname == "run_command":
                cmd = (args.get("CommandLine") or "").strip().replace("\r\n", " ").replace("\n", " ")
                if len(cmd) > 120:
                    cmd = cmd[:117] + "..."
                if cmd:
                    current_session["actions"].append(f"**Terminal Command**: `{cmd}`")

print(f"Total sessions extracted from transcript: {len(sessions)}")

# Read existing conversation_export.md safely (normalizing CRCRLF/CRLF to LF)
orig_text = (
    EXPORT_PATH.read_bytes()
    .decode("utf-8")
    .replace("\r\r\n", "\n")
    .replace("\r\n", "\n")
    .replace("\r", "\n")
)

# Find where Sesi 177 starts in the session blocks (optional if starting from clean 1..176 base)
anchor_177 = '<a name="sesi-177"></a>'
pos_177 = orig_text.find(anchor_177)
base_text = orig_text[:pos_177].rstrip() if pos_177 != -1 else orig_text.rstrip()

# Collapse any runaway blank lines from earlier CRCRLF writes (keep at most 2 newlines)
base_text = re.sub(r"\n{3,}", "\n\n", base_text)

# Update header metadata
base_text = re.sub(
    r"\*\*Conversation ID\*\*:.*",
    "**Conversation ID**: `df1e1ea0-0fbb-4765-b57c-159bb22c3674`, `d34d4348-c575-4c80-b16f-2f00055bda25`  ",
    base_text,
    count=1
)
export_ts_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S WIB")
base_text = re.sub(
    r"\*\*Waktu Export\*\*:.*",
    f"**Waktu Export**: {export_ts_str}  ",
    base_text,
    count=1
)

start_session_num = 177
end_session_num = start_session_num + len(sessions) - 1
milestone16_end = min(198, end_session_num)
milestone17_end = min(206, end_session_num)

# Milestone 16 definition
milestone_16 = f"""16. **Kalibrasi Kontur Warna Live Stream, Penyelarasan Watermark RS/NRM Ber-Outline Hitam, Perbaikan Crash, Stabilisasi Konsistensi AI, & Revert Bersih Polling Timestamp (Sesi 177 s.d. {milestone16_end})**:
    - **Penghapusan Duplikasi Timestamp & Watermark RS/NRM Transparan**: Menyembunyikan `tvOverlayClock` aplikasi agar hanya menampilkan timestamp bawaan kamera MS2 di kanan bawah, serta membuat background identitas `RS/NRM` di kiri bawah transparan, sejajar mentok bawah, dan menggunakan huruf putih ber-outline hitam (`StrokedTextView.kt`) dengan ukuran proporsional (`TEXT_SCALE = 0.042f`).
    - **Penguncian Aspect Ratio 16:9 di Mode Landscape**: Mengunci `videoContainer` ke rasio `16:9` pada layout landscape (`fragment_video_mobile.xml` dan `fragment_video_tv.xml`) agar teks `RS/NRM` tidak keluar ke area pillarbox hitam.
    - **Perbaikan Crash `"Cervexa has stopped"` Saat Running**: Mengganti pemanggilan `setTextColor()` di dalam `StrokedTextView.onDraw()` dengan rendering langsung pada `layout.draw(canvas)`, mengamankan callback asinkron (`processSnapshot`, `postDelayed`, `vlcVout.detachViews()` di UI thread), serta menutup interpreter TFLite (`aiDetector?.close()`) pada `MediaPageFragment.onDestroyView()`.
    - **Konsistensi & Stabilisasi Hasil Analisis AI Terhadap Perubahan Kontur Warna (`AiDetector.kt` & `ViaSegmentationHelper.kt`)**: Menerapkan matriks inversi kalibrasi warna (`restoreRawCameraColorsIfNeeded`), memprioritaskan model `YOLOv8n-Seg` di atas heuristik piksel, membatasi area sampling di atas area watermark (`normY <= 0.82`), serta menambahkan *Structural Scene Fingerprint Cache* (12x12 Z-score normalized) sehingga banyak snapshot dari objek yang sama menghasilkan deteksi AI yang 100% konsisten.
    - **Revert Bersih Polling Timestamp & Kompilasi APK Final**: Membatalkan eksperimen polling bitmap TextureView (`93eee3d`) demi memastikan video stream tetap 0 latency, 0 noise, dan ultra-smooth; memverifikasi bahwa modul deteksi kontur AI tetap utuh 100%; mengompilasi APK debug bersih (`cervexa-debug.apk`); serta mendokumentasikan tata cara navigasi menu hardware Elikliv MS2 (tekan tombol MENU 2x) untuk cap waktu."""

milestone_17 = f"""

17. **Perbaikan Koneksi Wi-Fi/SSID Lapangan, Probe Hardware `192.168.1.1:554`, Stabilitas RTSP Indoor, & Toggle Timestamp Aplikasi (Sesi 199 s.d. {milestone17_end})**:
    - **Hapus Lock SSID Kantor & Kewajiban Clear Data (`ffe572b`)**: Menghapus penyimpanan `camera_ssid_exact` serta `addCameraWifiSuggestion`, membersihkan cache SSID/suggestion lama secara otomatis (`WifiMonitor` & `WifiCamConnection`), dan menstandarkan deteksi ke prefix default `wifi_camera_MS2_` (case-insensitive).
    - **Deteksi Koneksi Manual ("lewat luar aplikasi") & Fix MIUI Redaction**: Menambahkan `NetworkRequest` khusus `TRANSPORT_WIFI` + `FLAG_INCLUDE_LOCATION_INFO` (API 31+), caching SSID per `networkHandle`, serta TCP RTSP probe ringan ke `192.168.1.1:554` (400 ms di `Dispatchers.IO`) pada jaringan Wi-Fi unvalidated.
    - **Hapus Redirect Paksa ke App Info & Fix Permission**: Menyatukan alur koneksi di `WifiCamConnection.ensureCameraReadyOrShowConnectFlow()` dengan dialog pilihan (*Cari Kamera Otomatis*, *Pengaturan Wi-Fi*, *Izin Aplikasi*), menggabungkan request permission API 33+ di `HomeActivity`, dan menghapus `findCameraWifiNetworkStrict()` duplikat.
    - **Stabilitas Stream RTSP Indoor**: Menambahkan opsi IjkPlayer `stimeout=5000000` dan `sync-av-start=0` serta `scheduleStreamRetry()` (1,5 detik) pada `VideoFragmentMobile` dan `VideoFragmentTv` (disertai `ensureCameraNetworkBound()` di TV).
    - **Toggle Timestamp Aplikasi Default ON Tanpa Delay (`6c366ab`)**: Menambahkan switch **Tampilkan Timestamp Aplikasi** di `SettingsActivity`, memperbarui `tvOverlayClock` di 4 layout Mobile/TV menjadi `StrokedTextView` transparan seragam dengan `tvOverlayInfo` memakai coroutine timer 1 detik tanpa scan piksel, serta mencetak timestamp opsional via `StorageUtils.drawOverlayWatermarkRow()` di snapshot, video, dan import media.""" if end_session_num >= 199 else ""

milestone_18 = f"""

18. **Audit & Perbaikan Potensi Force Close, Clean Rebuild APK, & Unit Test (`25eb2e5`) (Sesi 207 s.d. {end_session_num})**:
    - **Perbaikan Threading & Monitor Wi-Fi (`WifiMonitor.kt`)**: Menghapus early return saat `!hasWifiPermission(ctx)` di `start()`, membatasi pendaftaran `wifiEventCallback` (`TRANSPORT_WIFI`) ke `Build.VERSION.SDK_INT < 31` dan melepasnya di `stopInternal()`, serta memastikan fallback `publishCurrent()` memanggil `simpleCallback`/`statusCallback` di Main thread.
    - **Hardening Dialog & Lifecycle Beranda (`WifiCamConnection.kt`, `HomeActivity.kt`, `HomeDashboardFragment.kt`)**: Mengubah `showConnectCameraDialog()` memakai `MaterialAlertDialogBuilder` (`R.style.MyAlertDialogTheme`) + `runCatching`, mengganti `fragment.requestPermissions(...)` ke `ActivityCompat.requestPermissions(activity, ...)` dengan guard lifecycle, memindahkan cek `on_boarding` ke awal `HomeActivity.onCreate()`, menjaga `WifiMonitor.stopMonitoring()` dengan `!isChangingConfigurations`, dan memindahkan `repeatOnLifecycle` di `HomeDashboardFragment` ke `onViewCreated()`.
    - **Hardening Lifecycle Live Camera (`VideoFragmentMobile.kt`, `VideoFragmentTv.kt`)**: Memindahkan `startOverlayClock()` dan `onBackPressedDispatcher.addCallback(...)` ke `onViewCreated()`, memindahkan pembatalan coroutine/stream ke sebelum `super.onDestroyView()`, serta menambah guard `!isAdded || view == null` pada `VideoFragmentTv.startOverlayClock()`.
    - **Clean Build APK & Unit Test (`OverlayAndWifiLogicTest.kt`)**: Menjalankan `clean assembleDebug` menghasilkan `cervexa-debug.apk` bersih (`123,602,735` bytes), dan memverifikasi `OverlayAndWifiLogicTest` (2/2 lulus untuk `StorageUtils.formatOverlayTimestamp()` dan `WifiMonitor.isCameraSsid()`) tanpa mengubah modul AI (`ml/`).""" if end_session_num >= 207 else ""

# Update or insert Milestone 16, 17 & 18
if "16. **Kalibrasi Kontur Warna Live Stream" in base_text:
    base_text = re.sub(
        r"16\. \*\*Kalibrasi Kontur Warna Live Stream.*?(?=\n\n---|\n\n##|\Z)",
        milestone_16 + milestone_17 + milestone_18,
        base_text,
        flags=re.S
    )
else:
    target_m15 = "15. **Konsolidasi & Sinkronisasi Ekspor Percakapan Markdown Lengkap (Sesi 1 s.d. 176)**:\n    - Seluruh proses dialog teknis, implementasi kode fitur baru, perbaikan bug di lapangan, kompilasi APK, hingga konversi dokumen percakapan telah disinkronkan secara menyeluruh ke file Markdown `conversation_export.md` di direktori proyek dan folder Downloads."
    base_text = base_text.replace(target_m15, target_m15 + "\n\n" + milestone_16 + milestone_17 + milestone_18, 1)

# Rebuild TOC entries for 177..end_session_num
toc_lines = []
session_blocks = []

for idx, sess in enumerate(sessions):
    snum = start_session_num + idx
    first_line = sess["user"].splitlines()[0].strip() if sess["user"].strip() else f"[Lampiran Foto / Screenshot Pengujian Sesi {snum}]"
    if len(first_line) > 80:
        first_line = first_line[:77] + "..."
    # Clean up markdown chars in TOC label
    cleaned_label = first_line.replace("[", "").replace("]", "").replace("*", "")
    toc_lines.append(f"{snum}. [Sesi {snum}: {cleaned_label}](#sesi-{snum})")

    user_quote = "\n".join(f"> {line}" if line.strip() else ">" for line in (sess["user"] or "(Lampiran Media)").splitlines())
    assistant_body = "\n\n".join(sess["assistant_texts"]) if sess["assistant_texts"] else "*(Sedang mengekspor riwayat percakapan)*"

    actions_block = ""
    if sess["actions"]:
        dedup_actions = list(dict.fromkeys(sess["actions"]))
        actions_md = "\n".join(f"- {a}" for a in dedup_actions[:20])
        actions_block = f"\n\n#### ⚙️ Tindakan Teknis & Modifikasi Berkas:\n{actions_md}"

    block = f"""<a name="sesi-{snum}"></a>
## Sesi {snum}

### 👤 User
{user_quote}

### 🤖 Assistant
{assistant_body}{actions_block}

---
"""
    session_blocks.append(block)

# Clean existing TOC lines from 177 onward
toc_anchor = "176. [Sesi 176: konversi percakapan ini dari awal hingga akhir dalam bentuk markdown](#sesi-176)"
assert toc_anchor in base_text, "TOC anchor sesi-176 not found"

# Split at toc_anchor
p1, p2 = base_text.split(toc_anchor, 1)
# Remove any old Sesi 177+ TOC lines (including across blank lines) up to section divider
p2_cleaned = re.sub(r"^(?:\s*\d+\. \[Sesi \d+:.*?\n)+", "\n", p2)

new_toc = toc_anchor + "\n" + "\n".join(toc_lines)
base_text = p1 + new_toc + p2_cleaned

# Combine everything
final_text = base_text.rstrip() + "\n\n" + "\n".join(session_blocks) + "\n"

# Write clean CRLF bytes without Windows text-mode double-CR conversion
EXPORT_PATH.write_bytes(final_text.replace("\n", "\r\n").encode("utf-8"))
print(f"Successfully wrote {EXPORT_PATH} (size: {len(final_text)} chars, Sesi 1..{end_session_num})")

try:
    shutil.copyfile(EXPORT_PATH, DOWNLOADS_EXPORT_PATH)
    print(f"Successfully copied to {DOWNLOADS_EXPORT_PATH}")
except Exception as e:
    print(f"Warning: Failed to copy to Downloads: {e}")
