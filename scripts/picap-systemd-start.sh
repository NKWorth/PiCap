#!/usr/bin/env bash
# systemd ExecStart wrapper: prep BT, start pairing agent, then run PiCap.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIG="${PICAP_CONFIG:-$PROJECT_ROOT/config.yaml}"
AGENT_LOG="${PICAP_AGENT_LOG:-$PROJECT_ROOT/data/bluetooth-agent.log}"
PYTHON="${PROJECT_ROOT}/.venv/bin/python"

mkdir -p "$PROJECT_ROOT/data"

# Root prep is normally ExecStartPre=+picap-bt-prep.sh; call again as a no-op-safe fallback.
if [[ "$(id -u)" -eq 0 ]]; then
  "$SCRIPT_DIR/picap-bt-prep.sh" || true
fi

if [[ -x "$PYTHON" ]]; then
  if ! pgrep -f "$SCRIPT_DIR/bluetooth_agent.py" >/dev/null 2>&1; then
    nohup "$PYTHON" "$SCRIPT_DIR/bluetooth_agent.py" >>"$AGENT_LOG" 2>&1 &
    disown || true
  fi
fi

exec "$PYTHON" -m picap serve --config "$CONFIG"
