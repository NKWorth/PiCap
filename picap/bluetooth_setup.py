"""Helpers for Raspberry Pi Bluetooth adapter setup."""

from __future__ import annotations

import logging
import shutil
import subprocess

logger = logging.getLogger(__name__)


def _run(command: list[str], *, timeout: float = 5.0) -> bool:
    try:
        result = subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        if result.returncode != 0:
            detail = result.stderr.strip() or result.stdout.strip()
            if detail:
                logger.debug("%s failed: %s", command, detail)
            return False
        return True
    except (OSError, subprocess.TimeoutExpired) as exc:
        logger.debug("Command %s failed: %s", command, exc)
        return False


def _run_btmgmt(*args: str) -> None:
    command = ["btmgmt", "-i", "hci0", *args]
    _run(command)


def enable_le_advertising(device_name: str = "PiCap") -> None:
    """Enable kernel-level LE advertising when BlueZ dbus adverts fail."""
    if shutil.which("btmgmt"):
        for args in ("power", "on"), ("le", "on"), ("connectable", "on"), ("advertising", "on"):
            _run_btmgmt(*args)

    if shutil.which("bluetoothctl"):
        _run(["bluetoothctl", "--timeout", "5", "power", "on"])
        _run(["bluetoothctl", "--timeout", "5", "system-alias", device_name])


def is_advertisement_error(exc: Exception) -> bool:
    message = str(exc).lower()
    return "advertisement" in message or "advert" in message
