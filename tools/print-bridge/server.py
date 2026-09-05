#!/usr/bin/env python3
"""
Cervexa Print Bridge Server
Menerima berkas dokumen PDF dari aplikasi Cervexa (Smart TV / Mobile)
dan mencetaknya secara langsung ke printer klinik (misal: HP Smart Tank 480).
"""

import os
import sys
import json
import socket
import shutil
import tempfile
import subprocess
from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.parse

PORT = 9123
TEMP_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "temp_jobs")
BIN_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bin")
os.makedirs(TEMP_DIR, exist_ok=True)
os.makedirs(BIN_DIR, exist_ok=True)

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

def get_printers():
    default_printer = "Default System Printer"
    printers = []
    
    if sys.platform == "win32":
        # 1. Coba lewat win32print jika tersedia
        try:
            import win32print
            default_printer = win32print.GetDefaultPrinter()
            for p in win32print.EnumPrinters(win32print.PRINTER_ENUM_LOCAL | win32print.PRINTER_ENUM_CONNECTIONS):
                printers.append(p[2])
            return default_printer, printers
        except Exception:
            pass

        # 2. Coba lewat PowerShell
        try:
            cmd = "Get-CimInstance Win32_Printer | Select-Object Name, Default | ConvertTo-Json"
            res = subprocess.run(["powershell", "-NoProfile", "-Command", cmd], capture_output=True, text=True, timeout=4)
            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout)
                if isinstance(data, dict):
                    data = [data]
                for item in data:
                    name = item.get("Name")
                    if name:
                        printers.append(name)
                        if item.get("Default"):
                            default_printer = name
                if printers and default_printer == "Default System Printer":
                    default_printer = printers[0]
                return default_printer, printers
        except Exception:
            pass

        # 3. Coba wmic
        try:
            res = subprocess.run(["wmic", "printer", "get", "name,default"], capture_output=True, text=True, timeout=4)
            if res.returncode == 0:
                for line in res.stdout.splitlines()[1:]:
                    parts = line.strip().split()
                    if parts:
                        name = " ".join(parts[1:]) if parts[0] in ["TRUE", "FALSE"] else " ".join(parts)
                        printers.append(name)
                        if parts[0] == "TRUE":
                            default_printer = name
                return default_printer, printers
        except Exception:
            pass

    elif sys.platform == "darwin" or sys.platform.startswith("linux"):
        try:
            res = subprocess.run(["lpstat", "-p"], capture_output=True, text=True, timeout=3)
            for line in res.stdout.splitlines():
                if "printer" in line:
                    printers.append(line.split()[1])
            res_def = subprocess.run(["lpstat", "-d"], capture_output=True, text=True, timeout=3)
            if ":" in res_def.stdout:
                default_printer = res_def.stdout.split(":")[1].strip()
        except Exception:
            pass

    return default_printer, printers

def print_pdf_file(pdf_path, printer_name=None):
    """
    Kirim berkas PDF ke printer secara silent.
    """
    # Cek apakah SumatraPDF ada di folder bin, PATH, atau Program Files standar
    sumatra_path = None
    candidates = [
        os.path.join(BIN_DIR, "SumatraPDF.exe"),
        shutil.which("SumatraPDF.exe") or "",
        os.path.join(os.environ.get("PROGRAMFILES", r"C:\Program Files"), "SumatraPDF", "SumatraPDF.exe"),
        os.path.join(os.environ.get("PROGRAMFILES(X86)", r"C:\Program Files (x86)"), "SumatraPDF", "SumatraPDF.exe"),
        os.path.join(os.environ.get("LOCALAPPDATA", ""), "SumatraPDF", "SumatraPDF.exe"),
    ]
    for c in candidates:
        if c and os.path.isfile(c):
            sumatra_path = c
            break

    if sumatra_path and os.path.exists(sumatra_path):
        cmd = [sumatra_path, "-silent"]
        if printer_name:
            cmd.extend(["-print-to", printer_name])
        else:
            cmd.append("-print-to-default")
        cmd.append(pdf_path)
        print(f"[PRINT] Menjalankan: {' '.join(cmd)}")
        res = subprocess.run(cmd, capture_output=True, text=True)
        if res.returncode == 0:
            return True, f"Berhasil dicetak via SumatraPDF ke {printer_name or 'Default Printer'}"
        else:
            print(f"[ERROR] SumatraPDF error: {res.stderr}")

    if sys.platform == "win32":
        # Fallback Windows: Jalankan print verb via PowerShell
        print(f"[PRINT] Mencetak via Windows PowerShell Print Verb...")
        target_printer_arg = f' -PrinterName "{printer_name}"' if printer_name else ''
        ps_script = f'Start-Process -FilePath "{pdf_path}" -Verb Print{target_printer_arg}'
        res = subprocess.run(["powershell", "-NoProfile", "-Command", ps_script], capture_output=True, text=True)
        if res.returncode == 0:
            return True, f"Pekerjaan cetak telah dikirim ke Windows Spooler ({printer_name or 'Default Printer'})"
        else:
            return False, f"PowerShell Print Error: {res.stderr.strip()}"

    elif sys.platform == "darwin" or sys.platform.startswith("linux"):
        cmd = ["lp"]
        if printer_name:
            cmd.extend(["-d", printer_name])
        cmd.append(pdf_path)
        res = subprocess.run(cmd, capture_output=True, text=True)
        if res.returncode == 0:
            return True, f"Cetak berhasil via CUPS (lp) ke {printer_name or 'Default Printer'}"
        else:
            return False, res.stderr.strip()

    return False, "Sistem operasi tidak didukung untuk cetak langsung."


class PrintBridgeHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        print(f"[{self.log_date_time_string()}] {self.client_address[0]} -> {format % args}")

    def send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Printer-Name, X-Job-Title")

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_cors_headers()
        self.end_headers()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path in ["/status", "/api/status"]:
            default_p, all_p = get_printers()
            payload = {
                "status": "ready",
                "service": "Cervexa Print Bridge",
                "version": "1.0.0",
                "ip": get_local_ip(),
                "port": PORT,
                "os": sys.platform,
                "default_printer": default_p,
                "available_printers": all_p
            }
            body = json.dumps(payload, indent=2).encode("utf-8")
            self.send_response(200)
            self.send_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        # Web Dashboard Sederhana
        default_p, all_p = get_printers()
        local_ip = get_local_ip()
        html = f"""<!DOCTYPE html>
<html lang="id">
<head>
    <meta charset="UTF-8">
    <title>Cervexa Print Bridge</title>
    <style>
        body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; padding: 40px 20px; }}
        .card {{ max-width: 600px; margin: 0 auto; background: #1e293b; border-radius: 12px; padding: 24px; box-shadow: 0 10px 25px rgba(0,0,0,0.5); border: 1px solid #334155; }}
        h1 {{ color: #38bdf8; margin-top: 0; font-size: 22px; display: flex; align-items: center; gap: 8px; }}
        .badge {{ display: inline-block; padding: 4px 10px; border-radius: 9999px; background: #10b981; color: #fff; font-size: 12px; font-weight: bold; }}
        .info-row {{ margin: 16px 0; padding: 12px; background: #0f172a; border-radius: 8px; border-left: 4px solid #38bdf8; }}
        .label {{ font-size: 12px; color: #94a3b8; text-transform: uppercase; letter-spacing: 0.5px; }}
        .value {{ font-size: 16px; font-weight: bold; color: #f1f5f9; margin-top: 4px; }}
        .code {{ font-family: monospace; background: #334155; padding: 2px 6px; border-radius: 4px; color: #facc15; }}
    </style>
</head>
<body>
    <div class="card">
        <h1>🖨️ Cervexa Print Bridge <span class="badge">AKTIF</span></h1>
        <p style="color: #cbd5e1; font-size: 14px;">Print Bridge siap menerima dokumen rekam medis dari Smart TV Cervexa.</p>
        
        <div class="info-row">
            <div class="label">Alamat IP & Port untuk Smart TV:</div>
            <div class="value"><span class="code">{local_ip}:{PORT}</span></div>
        </div>

        <div class="info-row">
            <div class="label">Printer Default:</div>
            <div class="value">{default_p}</div>
        </div>

        <div class="info-row">
            <div class="label">Daftar Printer Terdeteksi:</div>
            <div class="value" style="font-size: 14px; font-weight: normal; margin-top: 6px;">
                {'<br>'.join('• ' + p for p in all_p) if all_p else '• Default System Printer'}
            </div>
        </div>

        <p style="color: #64748b; font-size: 12px; margin-top: 24px; text-align: center;">
            Cervexa Medical Diagnostic Suite &bull; Print Gateway v1.0
        </p>
    </div>
</body>
</html>"""
        body = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path not in ["/print", "/api/print"]:
            self.send_error(404, "Endpoint not found")
            return

        content_length = int(self.headers.get("Content-Length", 0))
        content_type = self.headers.get("Content-Type", "")
        printer_name = self.headers.get("X-Printer-Name")
        job_title = self.headers.get("X-Job-Title", "Cervexa Rekam Medis")

        if content_length <= 0:
            self.send_json_error(400, "Ukuran dokumen kosong (Content-Length is 0)")
            return

        raw_data = self.rfile.read(content_length)

        # Parsing PDF binary
        pdf_bytes = b""
        if "multipart/form-data" in content_type:
            # Sederhana extract part PDF dari multipart
            boundary = content_type.split("boundary=")[-1].strip().encode()
            parts = raw_data.split(b"--" + boundary)
            for part in parts:
                if b"%PDF" in part:
                    pdf_start = part.find(b"%PDF")
                    pdf_bytes = part[pdf_start:].rstrip(b"\r\n-")
                    break
        else:
            # Direct binary application/pdf
            pdf_bytes = raw_data

        if not pdf_bytes.startswith(b"%PDF"):
            # Coba cari tanda tangan %PDF
            idx = pdf_bytes.find(b"%PDF")
            if idx != -1:
                pdf_bytes = pdf_bytes[idx:]
            else:
                self.send_json_error(400, "Berkas yang dikirim bukan format PDF yang valid")
                return

        # Simpan ke file sementara
        filename = f"cervexa_job_{os.getpid()}_{int(tempfile._get_candidate_names().__next__(), 36)}.pdf"
        saved_path = os.path.join(TEMP_DIR, filename)
        try:
            with open(saved_path, "wb") as f:
                f.write(pdf_bytes)

            print(f"[PRINT] Menerima dokumen ({len(pdf_bytes)} bytes) -> {saved_path}")
            success, msg = print_pdf_file(saved_path, printer_name)

            if success:
                resp = {
                    "status": "success",
                    "message": msg,
                    "filename": filename,
                    "size_bytes": len(pdf_bytes)
                }
                body = json.dumps(resp).encode("utf-8")
                self.send_response(200)
                self.send_cors_headers()
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            else:
                self.send_json_error(500, f"Gagal mencetak dokumen: {msg}")

        except Exception as e:
            self.send_json_error(500, f"Terjadi kesalahan saat memproses print job: {str(e)}")

    def send_json_error(self, code, message):
        body = json.dumps({"status": "error", "message": message}).encode("utf-8")
        self.send_response(code)
        self.send_cors_headers()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    local_ip = get_local_ip()
    default_p, _ = get_printers()
    server_address = ("0.0.0.0", PORT)
    httpd = HTTPServer(server_address, PrintBridgeHandler)
    print("=" * 60)
    print("  CERVEXA PRINT BRIDGE SERVER (v1.0)")
    print("=" * 60)
    print(f"  • Status          : AKTIF & MENDENGARKAN")
    print(f"  • Alamat IP Lokal : {local_ip}:{PORT}")
    print(f"  • Printer Default : {default_p}")
    print(f"  • Masukkan IP ini pada Pengaturan Smart TV Cervexa:")
    print(f"    --> {local_ip}:{PORT}")
    print("=" * 60)
    print("  Tekan Ctrl+C untuk menghentikan server.\n")

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[STOP] Server dihentikan.")
        httpd.server_close()

if __name__ == "__main__":
    main()
