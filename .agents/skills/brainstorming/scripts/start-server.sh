#!/usr/bin/env bash
# Start the brainstorm server and output connection info
# Usage: start-server.sh [--project-dir <path>] [--host <bind-host>] [--url-host <display-host>] [--foreground] [--background]
#
# Starts server on a random high port, outputs JSON with URL.
# Each session gets its own directory to avoid conflicts.
#
# Options:
#   --project-dir <path>  Store session files under <path>/.superpowers/brainstorm/
#                         instead of /tmp. Files persist after server stops.
#   --host <bind-host>    Host/interface to bind (default: 127.0.0.1).
#                         Use 0.0.0.0 in remote/containerized environments.
#   --url-host <host>     Hostname shown in returned URL JSON.
#   --idle-timeout-minutes <n>  Shut down after n minutes idle (default 240 = 4h).
#   --open                Auto-open the browser on the first screen (use only
#                         after the user approves the visual companion).
#   --foreground          Run server in the current terminal (no backgrounding).
#   --background          Force background mode (overrides Codex auto-foreground).

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Wi-Fi & Network Information Helper
WIFI_STATUS="Tidak Terdeteksi / Terputus"
WIFI_SSID="-"
WIFI_IP="-"
WIFI_SIGNAL="-"
WIFI_GATEWAY="-"
WIFI_ADAPTER="-"

get_wifi_network_info() {
  # 1. Detection on Windows (MSYS / MINGW / Git Bash / Cygwin)
  if command -v netsh.exe >/dev/null 2>&1; then
    local wlan_out
    wlan_out=$(netsh.exe wlan show interfaces 2>/dev/null)
    local state_val
    state_val=$(echo "$wlan_out" | grep -E '^[[:space:]]*State[[:space:]]*:' | head -n 1 | awk -F: '{print $2}' | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    
    if [[ "$state_val" =~ ^(connected|terhubung)$ ]]; then
      WIFI_STATUS="Terhubung ($state_val)"
      WIFI_SSID=$(echo "$wlan_out" | grep -E '^[[:space:]]*SSID[[:space:]]*:' | head -n 1 | awk -F: '{print $2}' | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
      WIFI_SIGNAL=$(echo "$wlan_out" | grep -E '^[[:space:]]*Signal[[:space:]]*:' | head -n 1 | awk -F: '{print $2}' | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
      WIFI_ADAPTER=$(echo "$wlan_out" | grep -E '^[[:space:]]*Name[[:space:]]*:' | head -n 1 | awk -F: '{print $2}' | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    fi
  fi

  # 2. Resolve IPv4 using Node.js (cross-platform, reliable)
  if command -v node >/dev/null 2>&1; then
    local node_net_info
    node_net_info=$(node -e '
      const os = require("os");
      const nets = os.networkInterfaces();
      let wifiIp = "";
      let fallbackIp = "";
      for (const [name, addrs] of Object.entries(nets)) {
        for (const net of addrs) {
          if (net.family === "IPv4" && !net.internal) {
            if (/wi-?fi|wlan|wireless/i.test(name)) {
              wifiIp = net.address;
              break;
            } else if (!fallbackIp && !/vethernet|loopback|virtual/i.test(name)) {
              fallbackIp = net.address;
            }
          }
        }
        if (wifiIp) break;
      }
      console.log(wifiIp || fallbackIp || "");
    ' 2>/dev/null | tr -d '\r')
    if [[ -n "$node_net_info" ]]; then
      WIFI_IP="$node_net_info"
    fi
  fi

  # Fallback to ipconfig if WIFI_IP is still empty on Windows
  if [[ "$WIFI_IP" == "-" || -z "$WIFI_IP" ]] && command -v ipconfig.exe >/dev/null 2>&1; then
    WIFI_IP=$(ipconfig.exe | awk '
      /Wireless LAN adapter/ { in_wifi=1 }
      in_wifi && /IPv4 Address/ {
        split($0, a, ":")
        gsub(/[[:space:]\r]/, "", a[2])
        print a[2]
        exit
      }
    ')
  fi

  # 3. Detection on Linux (nmcli / iwgetid / ip)
  if [[ "$WIFI_STATUS" == "Tidak Terdeteksi / Terputus" ]] && command -v nmcli >/dev/null 2>&1; then
    local nm_active
    nm_active=$(nmcli -t -f active,ssid,signal,device dev wifi 2>/dev/null | grep '^yes:' | head -n 1)
    if [[ -n "$nm_active" ]]; then
      WIFI_STATUS="Terhubung"
      WIFI_SSID=$(echo "$nm_active" | cut -d: -f2)
      WIFI_SIGNAL="$(echo "$nm_active" | cut -d: -f3)%"
      local dev
      dev=$(echo "$nm_active" | cut -d: -f4)
      if [[ -n "$dev" ]] && command -v ip >/dev/null 2>&1; then
        local ip_found
        ip_found=$(ip -4 addr show dev "$dev" 2>/dev/null | grep -oP '(?<=inet\s)\d+(\.\d+){3}' | head -n 1)
        if [[ -n "$ip_found" ]]; then WIFI_IP="$ip_found"; fi
      fi
    fi
  fi

  # 4. Gateway detection
  if command -v route.exe >/dev/null 2>&1; then
    WIFI_GATEWAY=$(route.exe print 0.0.0.0 2>/dev/null | awk '$1=="0.0.0.0" && $2=="0.0.0.0" {print $3; exit}' | tr -d '\r')
  elif command -v ip >/dev/null 2>&1; then
    WIFI_GATEWAY=$(ip route show default 2>/dev/null | awk '{print $3; exit}')
  fi
}

display_wifi_network_info() {
  get_wifi_network_info

  echo "============================================================" >&2
  echo "              INFORMASI JARINGAN & WI-FI AKTIF             " >&2
  echo "============================================================" >&2
  echo "  * Status Koneksi : ${WIFI_STATUS}" >&2
  echo "  * Nama SSID WiFi : ${WIFI_SSID}" >&2
  echo "  * Kekuatan Sinyal: ${WIFI_SIGNAL:-Tidak Diketahui}" >&2
  echo "  * IP Address WiFi: ${WIFI_IP:-127.0.0.1}" >&2
  if [[ -n "$WIFI_GATEWAY" && "$WIFI_GATEWAY" != "-" ]]; then
    echo "  * Gateway Router : ${WIFI_GATEWAY}" >&2
  fi
  if [[ -n "$WIFI_ADAPTER" && "$WIFI_ADAPTER" != "-" ]]; then
    echo "  * Interface Kartu: ${WIFI_ADAPTER}" >&2
  fi
  if [[ "$BIND_HOST" == "127.0.0.1" || "$BIND_HOST" == "localhost" ]]; then
    echo "  * Host Binding   : 127.0.0.1 (Hanya lokal laptop)" >&2
    echo "  * Tips Akses HP  : Gunakan '--host 0.0.0.0' jika ingin dibuka dari HP / Smart TV" >&2
  else
    echo "  * Host Binding   : ${BIND_HOST} (Dapat diakses perangkat lain via Wi-Fi)" >&2
  fi
  echo "============================================================" >&2
  echo "" >&2
}

# Parse arguments
PROJECT_DIR=""
FOREGROUND="false"
FORCE_BACKGROUND="false"
BIND_HOST="127.0.0.1"
URL_HOST=""
IDLE_TIMEOUT_MINUTES=""
ONLY_WIFI_INFO="false"
QUIET="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project-dir)
      PROJECT_DIR="$2"
      shift 2
      ;;
    --host)
      BIND_HOST="$2"
      shift 2
      ;;
    --url-host)
      URL_HOST="$2"
      shift 2
      ;;
    --idle-timeout-minutes)
      IDLE_TIMEOUT_MINUTES="$2"
      shift 2
      ;;
    --open)
      export BRAINSTORM_OPEN=1
      shift
      ;;
    --foreground|--no-daemon)
      FOREGROUND="true"
      shift
      ;;
    --background|--daemon)
      FORCE_BACKGROUND="true"
      shift
      ;;
    --wifi|--ip|--info)
      ONLY_WIFI_INFO="true"
      shift
      ;;
    --quiet|--no-banner)
      QUIET="true"
      shift
      ;;
    --help|-h)
      echo "Penggunaan: start-server.sh [options]"
      echo "Options:"
      echo "  --wifi, --ip, --info      Tampilkan info WiFi dan IP aktif lalu keluar"
      echo "  --host <bind-host>        Host interface untuk bind (default: 127.0.0.1, gunakan 0.0.0.0 untuk Wi-Fi)"
      echo "  --url-host <host>         Hostname yang ditampilkan pada URL"
      echo "  --project-dir <path>      Direktori proyek untuk menyimpan state sesi"
      echo "  --foreground              Jalankan di foreground terminal"
      echo "  --background              Jalankan di background"
      echo "  --quiet                   Sembunyikan banner informasi WiFi"
      exit 0
      ;;
    *)
      echo "{\"error\": \"Unknown argument: $1\"}"
      exit 1
      ;;
  esac
done

# If user only requested Wi-Fi / IP info
if [[ "$ONLY_WIFI_INFO" == "true" ]]; then
  display_wifi_network_info
  exit 0
fi

# Always display network/wifi banner unless suppressed
if [[ "$QUIET" != "true" ]]; then
  display_wifi_network_info
fi

if [[ -z "$URL_HOST" ]]; then
  if [[ "$BIND_HOST" == "127.0.0.1" || "$BIND_HOST" == "localhost" ]]; then
    URL_HOST="localhost"
  elif [[ "$BIND_HOST" == "0.0.0.0" && "$WIFI_IP" != "-" && -n "$WIFI_IP" ]]; then
    URL_HOST="$WIFI_IP"
  else
    URL_HOST="$BIND_HOST"
  fi
fi

if [[ -n "$IDLE_TIMEOUT_MINUTES" ]]; then
  if ! [[ "$IDLE_TIMEOUT_MINUTES" =~ ^[0-9]+$ ]] || [[ "$IDLE_TIMEOUT_MINUTES" -lt 1 ]]; then
    echo "{\"error\": \"--idle-timeout-minutes must be a positive integer\"}"
    exit 1
  fi
  export BRAINSTORM_IDLE_TIMEOUT_MS=$(( IDLE_TIMEOUT_MINUTES * 60 * 1000 ))
fi

is_windows_like_shell() {
  case "${OSTYPE:-}" in
    msys*|cygwin*|mingw*) return 0 ;;
  esac
  if [[ -n "${MSYSTEM:-}" ]]; then
    return 0
  fi
  local uname_s
  uname_s="$(uname -s 2>/dev/null || true)"
  case "$uname_s" in
    MSYS*|MINGW*|CYGWIN*) return 0 ;;
  esac
  return 1
}

# Some environments reap detached/background processes. Auto-foreground when detected.
if [[ -n "${CODEX_CI:-}" && "$FOREGROUND" != "true" && "$FORCE_BACKGROUND" != "true" ]]; then
  FOREGROUND="true"
fi

# Windows/Git Bash reaps nohup background processes. Auto-foreground when detected.
if [[ "$FOREGROUND" != "true" && "$FORCE_BACKGROUND" != "true" ]]; then
  if is_windows_like_shell; then
    FOREGROUND="true"
  fi
fi

# Session files (server.log, server-info, .last-token) embed the session key —
# keep everything this script and the server create owner-only.
umask 077

# Generate unique session directory
SESSION_ID="$$-$(date +%s)"

if [[ -n "$PROJECT_DIR" ]]; then
  SESSION_DIR="${PROJECT_DIR}/.superpowers/brainstorm/${SESSION_ID}"
  # Persist the bound port and key per project so a restart reuses them and an
  # already-open browser tab reconnects to the same URL with a valid cookie.
  export BRAINSTORM_PORT_FILE="${PROJECT_DIR}/.superpowers/brainstorm/.last-port"
  export BRAINSTORM_TOKEN_FILE="${PROJECT_DIR}/.superpowers/brainstorm/.last-token"
else
  SESSION_DIR="/tmp/brainstorm-${SESSION_ID}"
fi

STATE_DIR="${SESSION_DIR}/state"
PID_FILE="${STATE_DIR}/server.pid"
LOG_FILE="${STATE_DIR}/server.log"
SERVER_ID_FILE="${STATE_DIR}/server-instance-id"

# Create fresh session directory with content and state peers
mkdir -p "${SESSION_DIR}/content" "$STATE_DIR"

SERVER_ID=""
if [[ -r /dev/urandom ]]; then
  SERVER_ID="$(od -An -N24 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n' || true)"
fi
if ! [[ "$SERVER_ID" =~ ^[A-Za-z0-9_-]{32,64}$ ]]; then
  SERVER_ID="$(printf '%08x%08x%08x%08x' "$$" "$(date +%s)" "${RANDOM:-0}" "${RANDOM:-0}")"
fi
printf '%s\n' "$SERVER_ID" > "$SERVER_ID_FILE"
chmod 600 "$SERVER_ID_FILE" 2>/dev/null || true

# Kill any existing server
if [[ -f "$PID_FILE" ]]; then
  old_pid=$(cat "$PID_FILE")
  kill "$old_pid" 2>/dev/null
  rm -f "$PID_FILE"
fi

cd "$SCRIPT_DIR" || exit 1

# Resolve the harness PID (grandparent of this script).
# $PPID is the ephemeral shell the harness spawned to run us — it dies
# when this script exits. The harness itself is $PPID's parent.
OWNER_PID="$(ps -o ppid= -p "$PPID" 2>/dev/null | tr -d ' ')"
if [[ -z "$OWNER_PID" || "$OWNER_PID" == "1" ]]; then
  OWNER_PID="$PPID"
fi

# Windows/MSYS2: Node.js cannot see POSIX PIDs from the MSYS2 namespace.
# Passing a PID node cannot verify causes server to log owner-pid-invalid
# and self-terminate at the 60-second lifecycle check. Clear it so the
# watchdog is disabled and the idle timeout becomes the only shutdown trigger.
if is_windows_like_shell; then
  OWNER_PID=""
fi

# Foreground mode for environments that reap detached/background processes.
if [[ "$FOREGROUND" == "true" ]]; then
  env BRAINSTORM_DIR="$SESSION_DIR" BRAINSTORM_HOST="$BIND_HOST" BRAINSTORM_URL_HOST="$URL_HOST" BRAINSTORM_OWNER_PID="$OWNER_PID" node server.cjs "--brainstorm-server-id=$SERVER_ID" &
  SERVER_PID=$!
  echo "$SERVER_PID" > "$PID_FILE"
  wait "$SERVER_PID"
  exit $?
fi

# Start server, capturing output to log file
# Use nohup to survive shell exit; disown to remove from job table
nohup env BRAINSTORM_DIR="$SESSION_DIR" BRAINSTORM_HOST="$BIND_HOST" BRAINSTORM_URL_HOST="$URL_HOST" BRAINSTORM_OWNER_PID="$OWNER_PID" node server.cjs "--brainstorm-server-id=$SERVER_ID" > "$LOG_FILE" 2>&1 &
SERVER_PID=$!
disown "$SERVER_PID" 2>/dev/null
echo "$SERVER_PID" > "$PID_FILE"

# Wait for server-started message (check log file)
for _ in {1..50}; do
  if grep -q "server-started" "$LOG_FILE" 2>/dev/null; then
    # Verify server is still alive after a short window (catches process reapers)
    alive="true"
    for _ in {1..20}; do
      if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        alive="false"
        break
      fi
      sleep 0.1
    done
    if [[ "$alive" != "true" ]]; then
      echo "{\"error\": \"Server started but was killed. Retry in a persistent terminal with: $SCRIPT_DIR/start-server.sh${PROJECT_DIR:+ --project-dir $PROJECT_DIR} --host $BIND_HOST --url-host $URL_HOST --foreground\"}"
      exit 1
    fi
    grep "server-started" "$LOG_FILE" | head -1
    exit 0
  fi
  sleep 0.1
done

# Timeout - server didn't start
echo '{"error": "Server failed to start within 5 seconds"}'
exit 1
