#!/usr/bin/env bash
# Start, stop, or check PiCap on Raspberry Pi.
#
# Usage:
#   ./scripts/start-picap.sh              # start in background (HTTP + BLE)
#   ./scripts/start-picap.sh --foreground   # run in foreground (logs to terminal)
#   ./scripts/start-picap.sh --http-only    # HTTP API only
#   ./scripts/start-picap.sh --stop         # stop running instance
#   ./scripts/start-picap.sh --restart      # stop if running, then start
#   ./scripts/start-picap.sh --status       # show process and API status
#   ./scripts/start-picap.sh --full-bt-setup  # run full Bluetooth setup first

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIG="${PICAP_CONFIG:-$PROJECT_ROOT/config.yaml}"
LOG_FILE="${PICAP_LOG:-$PROJECT_ROOT/data/picap.log}"
PID_FILE="${PICAP_PID:-$PROJECT_ROOT/data/picap.pid}"
AGENT_PID_FILE="${PICAP_AGENT_PID:-$PROJECT_ROOT/data/bluetooth-agent.pid}"
AGENT_LOG="${PICAP_AGENT_LOG:-$PROJECT_ROOT/data/bluetooth-agent.log}"
MODE="serve"
FOREGROUND=0
ACTION="start"
FULL_BT_SETUP=0

usage() {
  sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --foreground|-f) FOREGROUND=1; shift ;;
    --http-only) MODE="serve-http"; shift ;;
    --stop) ACTION="stop"; shift ;;
    --restart|-r) ACTION="restart"; shift ;;
    --status) ACTION="status"; shift ;;
    --full-bt-setup) FULL_BT_SETUP=1; shift ;;
    --config) CONFIG="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 1 ;;
  esac
done

cd "$PROJECT_ROOT"
mkdir -p "$(dirname "$LOG_FILE")" "$(dirname "$PID_FILE")"

find_agent_pids() {
  pgrep -f "scripts/bluetooth_agent.py" 2>/dev/null || true
}

stop_bluetooth_agent() {
  local pids
  pids="$(find_agent_pids)"
  if [[ -n "$pids" ]]; then
    kill $pids 2>/dev/null || true
  fi
  rm -f "$AGENT_PID_FILE"
}

start_bluetooth_agent() {
  if [[ "$MODE" != "serve" ]]; then
    return
  fi
  if [[ -n "$(find_agent_pids)" ]]; then
    return
  fi
  if [[ ! -x "$PROJECT_ROOT/.venv/bin/python" ]]; then
    return
  fi
  nohup "$PROJECT_ROOT/.venv/bin/python" "$SCRIPT_DIR/bluetooth_agent.py" >>"$AGENT_LOG" 2>&1 &
  echo $! >"$AGENT_PID_FILE"
}

find_picap_pids() {
  pgrep -f "python -m picap (serve|serve-http)" 2>/dev/null || true
}

stop_picap() {
  local pids
  pids="$(find_picap_pids)"
  if [[ -z "$pids" ]]; then
    echo "PiCap is not running."
    rm -f "$PID_FILE"
    return 0
  fi
  echo "Stopping PiCap (PID(s): $pids)..."
  kill $pids 2>/dev/null || true
  sleep 1
  pids="$(find_picap_pids)"
  if [[ -n "$pids" ]]; then
    kill -9 $pids 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
  stop_bluetooth_agent
  echo "PiCap stopped."
}

http_port() {
  if [[ -f "$CONFIG" ]]; then
    local port
    port="$(awk '/^http:/{found=1} found && /^[[:space:]]+port:/{print $2; exit}' "$CONFIG")"
    echo "${port:-8080}"
  else
    echo 8080
  fi
}

show_status() {
  local pids port
  pids="$(find_picap_pids)"
  port="$(http_port)"
  if [[ -n "$pids" ]]; then
    echo "PiCap is running (PID(s): $pids)"
    echo "Log file: $LOG_FILE"
  else
    echo "PiCap is not running."
  fi
  if command -v curl >/dev/null 2>&1; then
    echo "API: http://127.0.0.1:${port}/api/status"
    curl -s --max-time 3 "http://127.0.0.1:${port}/api/status" || echo "(API not responding yet)"
    echo
  fi
}

prepare_bluetooth() {
  if [[ "$FULL_BT_SETUP" -eq 1 ]]; then
    "$SCRIPT_DIR/setup-pi-bluetooth.sh"
    return
  fi

  if [[ "$(uname -s)" != "Linux" ]]; then
    return
  fi

  if command -v btmgmt >/dev/null 2>&1; then
    sudo btmgmt -i hci0 power on 2>/dev/null || btmgmt -i hci0 power on 2>/dev/null || true
    sudo btmgmt -i hci0 le on 2>/dev/null || btmgmt -i hci0 le on 2>/dev/null || true
    sudo btmgmt -i hci0 connectable on 2>/dev/null || btmgmt -i hci0 connectable on 2>/dev/null || true
    sudo btmgmt -i hci0 advertising on 2>/dev/null || btmgmt -i hci0 advertising on 2>/dev/null || true
    sudo btmgmt -i hci0 io-cap 3 2>/dev/null || btmgmt -i hci0 io-cap 3 2>/dev/null || true
  fi

  if [[ -x "$PROJECT_ROOT/.venv/bin/python" ]]; then
    "$PROJECT_ROOT/.venv/bin/python" "$SCRIPT_DIR/patch-bless.py" >/dev/null || true
  fi
}

start_picap() {
  if [[ ! -x "$PROJECT_ROOT/.venv/bin/python" ]]; then
    echo "Virtualenv not found. Create it first:" >&2
    echo "  cd $PROJECT_ROOT" >&2
    echo "  python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt" >&2
    exit 1
  fi

  if [[ ! -f "$CONFIG" ]]; then
    if [[ -f "$PROJECT_ROOT/config.example.yaml" ]]; then
      cp "$PROJECT_ROOT/config.example.yaml" "$CONFIG"
      echo "Created $CONFIG from config.example.yaml"
    else
      echo "Config not found: $CONFIG" >&2
      exit 1
    fi
  fi

  local existing
  existing="$(find_picap_pids)"
  if [[ -n "$existing" ]]; then
    echo "PiCap is already running (PID(s): $existing). Use --stop first."
    exit 1
  fi

  if [[ "$MODE" == "serve" ]]; then
    prepare_bluetooth
    start_bluetooth_agent
  fi

  # shellcheck disable=SC1091
  source "$PROJECT_ROOT/.venv/bin/activate"
  local port
  port="$(http_port)"

  echo "Starting PiCap ($MODE)..."
  if [[ "$FOREGROUND" -eq 1 ]]; then
    echo "Press Ctrl+C to stop."
    exec python -m picap "$MODE" --config "$CONFIG"
  fi

  nohup python -m picap "$MODE" --config "$CONFIG" >>"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
  sleep 2

  if curl -sf --max-time 5 "http://127.0.0.1:${port}/api/status" >/dev/null; then
    echo "PiCap started successfully."
    echo "  API:  http://$(hostname -I 2>/dev/null | awk '{print $1}'):${port}"
    echo "  Log:  $LOG_FILE"
    echo "  Stop: $SCRIPT_DIR/start-picap.sh --stop"
    curl -s --max-time 3 "http://127.0.0.1:${port}/api/status"
    echo
  else
    echo "PiCap process started but API is not responding yet." >&2
    echo "Check the log: $LOG_FILE" >&2
    tail -20 "$LOG_FILE" 2>/dev/null || true
    exit 1
  fi
}

restart_picap() {
  if [[ -n "$(find_picap_pids)" ]]; then
    stop_picap
  else
    rm -f "$PID_FILE"
    stop_bluetooth_agent
    echo "PiCap is not running; starting..."
  fi
  start_picap
}

case "$ACTION" in
  start) start_picap ;;
  stop) stop_picap ;;
  restart) restart_picap ;;
  status) show_status ;;
esac
