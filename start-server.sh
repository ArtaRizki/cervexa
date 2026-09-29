#!/usr/bin/env bash
# Wrapper to run start-server.sh from workspace root
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_SCRIPT="$SCRIPT_DIR/.agents/skills/brainstorming/scripts/start-server.sh"

if [[ -f "$TARGET_SCRIPT" ]]; then
  exec bash "$TARGET_SCRIPT" "$@"
else
  echo "[ERROR] Script $TARGET_SCRIPT tidak ditemukan." >&2
  exit 1
fi
