#!/usr/bin/env bash
# Prepare Raspberry Pi Bluetooth for PiCap BLE advertising.
#
# Safe to run from systemd (as root via ExecStartPre=+) or interactively with sudo.
set -uo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "setup-pi-bluetooth.sh is intended for Linux/Raspberry Pi OS"
  exit 0
fi

if ! command -v bluetoothctl >/dev/null 2>&1; then
  echo "bluetoothctl not found; install bluez"
  exit 1
fi

run_root() {
  if [[ "$(id -u)" -eq 0 ]]; then
    "$@"
  elif command -v sudo >/dev/null 2>&1; then
    sudo -n "$@" 2>/dev/null || sudo "$@"
  else
    echo "Need root privileges for: $*" >&2
    return 1
  fi
}

TARGET_USER="${PICAP_USER:-${SUDO_USER:-}}"
if [[ -z "$TARGET_USER" || "$TARGET_USER" == "root" ]]; then
  if [[ -n "${USER:-}" && "$USER" != "root" ]]; then
    TARGET_USER="$USER"
  else
    TARGET_USER="woodworn"
  fi
fi

echo "Unblocking Bluetooth rfkill (if present)..."
for soft in /sys/class/rfkill/rfkill*/soft; do
  if [[ -f "$soft" ]]; then
    run_root tee "$soft" >/dev/null <<<"0" || true
  fi
done

MAIN_CONF="/etc/bluetooth/main.conf"
if [[ -f "$MAIN_CONF" ]] && ! grep -q "^Experimental = true" "$MAIN_CONF"; then
  echo "Enabling BlueZ experimental mode in $MAIN_CONF"
  run_root sed -i 's/^#Experimental = false/Experimental = true/' "$MAIN_CONF" || true
  if ! grep -q "^Experimental = true" "$MAIN_CONF"; then
    run_root tee -a "$MAIN_CONF" >/dev/null <<<"Experimental = true" || true
  fi
fi

if [[ -f "$MAIN_CONF" ]] && ! grep -q "^JustWorksRepairing" "$MAIN_CONF"; then
  echo "Enabling JustWorksRepairing in $MAIN_CONF"
  run_root tee -a "$MAIN_CONF" >/dev/null <<<"JustWorksRepairing = always" || true
fi

# Avoid restarting bluetooth during boot/service start if it is already active.
if systemctl is-active --quiet bluetooth 2>/dev/null; then
  echo "Bluetooth service already active"
else
  echo "Starting bluetooth service..."
  run_root systemctl start bluetooth || true
  sleep 2
fi

echo "Powering adapter and enabling LE advertising..."
# bluetoothctl can hang without an agent; always bound it with timeout.
if command -v timeout >/dev/null 2>&1; then
  run_root timeout 5 bluetoothctl power on || true
else
  run_root bluetoothctl power on || true
fi
if command -v btmgmt >/dev/null 2>&1; then
  run_root timeout 5 btmgmt -i hci0 power on || true
  run_root timeout 5 btmgmt -i hci0 le on || true
  run_root timeout 5 btmgmt -i hci0 connectable on || true
  run_root timeout 5 btmgmt -i hci0 advertising on || true
  # NoInputNoOutput: auto-accept pairing without PIN prompts on the Pi
  run_root timeout 5 btmgmt -i hci0 io-cap 3 || true
fi

if id -nG "$TARGET_USER" 2>/dev/null | tr ' ' '\n' | grep -qx bluetooth; then
  echo "User $TARGET_USER is already in the bluetooth group"
else
  echo "Adding $TARGET_USER to bluetooth group (log out and back in to apply)..."
  run_root usermod -aG bluetooth "$TARGET_USER" || true
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
if [[ -x "$PROJECT_ROOT/.venv/bin/python" ]]; then
  echo "Patching bless in project venv..."
  "$PROJECT_ROOT/.venv/bin/python" "$SCRIPT_DIR/patch-bless.py" || true
elif command -v python3 >/dev/null 2>&1; then
  python3 "$SCRIPT_DIR/patch-bless.py" || true
fi

echo "Bluetooth setup complete."
if command -v timeout >/dev/null 2>&1; then
  timeout 3 bluetoothctl pairable off 2>/dev/null || true
  timeout 3 bluetoothctl show 2>/dev/null | grep -E "Powered|Alias|Discoverable|Pairable" || true
else
  bluetoothctl pairable off 2>/dev/null || true
  bluetoothctl show 2>/dev/null | grep -E "Powered|Alias|Discoverable|Pairable" || true
fi
exit 0
