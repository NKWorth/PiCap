#!/usr/bin/env bash
# Fast, non-blocking Bluetooth prep for systemd (never hangs restart/boot).
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

# Prefer btmgmt — bluetoothctl can block forever waiting for an agent.
if command -v btmgmt >/dev/null 2>&1; then
  run_timeout 3 btmgmt -i hci0 power on
  run_timeout 3 btmgmt -i hci0 le on
  run_timeout 3 btmgmt -i hci0 connectable on
  run_timeout 3 btmgmt -i hci0 advertising on
  run_timeout 3 btmgmt -i hci0 io-cap 3
fi

exit 0
