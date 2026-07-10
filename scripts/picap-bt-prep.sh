#!/usr/bin/env bash
# Fast Bluetooth prep for systemd boot/restart (never hangs forever).
set -uo pipefail

run_timeout() {
  local seconds="$1"
  shift
  if command -v timeout >/dev/null 2>&1; then
    timeout --signal=KILL "${seconds}s" "$@" >/dev/null 2>&1 || true
  else
    "$@" >/dev/null 2>&1 || true
  fi
}

# Unblock rfkill quickly if present.
for soft in /sys/class/rfkill/rfkill*/soft; do
  if [[ -f "$soft" ]]; then
    echo 0 >"$soft" 2>/dev/null || true
  fi
done

# After a power cycle the adapter can take several seconds to appear.
echo "Waiting for Bluetooth adapter hci0..."
for _ in $(seq 1 40); do
  if [[ -d /sys/class/bluetooth/hci0 ]]; then
    echo "hci0 is present"
    break
  fi
  sleep 1
done

if [[ ! -d /sys/class/bluetooth/hci0 ]]; then
  echo "hci0 not ready yet; continuing anyway" >&2
fi

# Prefer btmgmt — bluetoothctl can block forever waiting for an agent.
if command -v btmgmt >/dev/null 2>&1; then
  run_timeout 3 btmgmt -i hci0 power on
  run_timeout 3 btmgmt -i hci0 le on
  run_timeout 3 btmgmt -i hci0 connectable on
  run_timeout 3 btmgmt -i hci0 advertising on
  run_timeout 3 btmgmt -i hci0 io-cap 3
fi

exit 0
