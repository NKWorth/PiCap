#!/usr/bin/env bash
# Clear stale phone pairings that break PiCap BLE connections.
#
# PiCap uses BLE GATT from the Android app — do not pair via Android Bluetooth
# settings. If you did, run this script on the Pi and forget the device on your phone.
#
# Usage:
#   bash scripts/reset-bluetooth-pairings.sh

set -eu

if ! command -v bluetoothctl >/dev/null 2>&1; then
  echo "bluetoothctl not found" >&2
  exit 1
fi

echo "Removing paired Bluetooth devices..."
bluetoothctl devices 2>/dev/null | while read -r _ mac _rest; do
  if [[ -n "${mac:-}" ]]; then
    echo "  remove $mac"
    bluetoothctl remove "$mac" 2>/dev/null || true
  fi
done

echo "Disabling pairable mode (PiCap connects via BLE, not classic pairing)..."
bluetoothctl pairable off 2>/dev/null || true

echo "Done. On your phone: forget/unpair PiCap or PiCam in Bluetooth settings."
echo "Then connect using Scan in the PiCap app only."
