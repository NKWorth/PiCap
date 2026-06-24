#!/usr/bin/env bash
# Prepare Raspberry Pi Bluetooth for PiCap BLE advertising.
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "setup-pi-bluetooth.sh is intended for Linux/Raspberry Pi OS"
  exit 0
fi

if ! command -v bluetoothctl >/dev/null 2>&1; then
  echo "bluetoothctl not found; install bluez"
  exit 1
fi

echo "Unblocking Bluetooth rfkill (if present)..."
for soft in /sys/class/rfkill/rfkill*/soft; do
  if [[ -f "$soft" ]]; then
    echo 0 | sudo tee "$soft" >/dev/null || true
  fi
done

MAIN_CONF="/etc/bluetooth/main.conf"
if [[ -f "$MAIN_CONF" ]] && ! grep -q "^Experimental = true" "$MAIN_CONF"; then
  echo "Enabling BlueZ experimental mode in $MAIN_CONF"
  sudo sed -i 's/^#Experimental = false/Experimental = true/' "$MAIN_CONF" || true
  if ! grep -q "^Experimental = true" "$MAIN_CONF"; then
    echo "Experimental = true" | sudo tee -a "$MAIN_CONF" >/dev/null
  fi
fi

if [[ -f "$MAIN_CONF" ]] && ! grep -q "^JustWorksRepairing" "$MAIN_CONF"; then
  echo "Enabling JustWorksRepairing in $MAIN_CONF"
  echo "JustWorksRepairing = always" | sudo tee -a "$MAIN_CONF" >/dev/null
fi

echo "Restarting bluetooth service..."
sudo systemctl restart bluetooth
sleep 2

echo "Powering adapter and enabling LE advertising..."
sudo bluetoothctl power on || true
if command -v btmgmt >/dev/null 2>&1; then
  sudo btmgmt -i hci0 power on || true
  sudo btmgmt -i hci0 le on || true
  sudo btmgmt -i hci0 connectable on || true
  sudo btmgmt -i hci0 advertising on || true
  # NoInputNoOutput: auto-accept pairing without PIN prompts on the Pi
  sudo btmgmt -i hci0 io-cap 3 || true
fi

if id -nG "$USER" | tr ' ' '\n' | grep -qx bluetooth; then
  echo "User $USER is already in the bluetooth group"
else
  echo "Adding $USER to bluetooth group (log out and back in to apply)..."
  sudo usermod -aG bluetooth "$USER" || true
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
if [[ -x "$PROJECT_ROOT/.venv/bin/python" ]]; then
  echo "Patching bless in project venv..."
  "$PROJECT_ROOT/.venv/bin/python" "$SCRIPT_DIR/patch-bless.py"
elif command -v python3 >/dev/null 2>&1; then
  python3 "$SCRIPT_DIR/patch-bless.py" || true
fi

echo "Bluetooth setup complete."
bluetoothctl show 2>/dev/null | grep -E "Powered|Alias|Discoverable" || true
