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


def _run_btmgmt(*args: str) -> bool:
    return _run(["btmgmt", "-i", "hci0", *args])


def enable_le_advertising(device_name: str = "PiCap") -> None:
    """
    Force the adapter to advertise a discoverable LE name.

    bless may register GATT successfully while the phone still cannot see the
    device if LocalName/service UUID are missing from the advert payload.
    """
    name = (device_name or "PiCap").strip() or "PiCap"
    short = name[:10]

    if shutil.which("btmgmt"):
        # Order matters: name first, then LE/connectable, then advertising.
        _run_btmgmt("power", "on")
        _run_btmgmt("le", "on")
        _run_btmgmt("connectable", "on")
        _run_btmgmt("bondable", "on")
        _run_btmgmt("name", name, short)
        # Toggle advertising so a fresh named advert is emitted after boot.
        _run_btmgmt("advertising", "off")
        _run_btmgmt("advertising", "on")
        _run_btmgmt("discov", "on")
        _run_btmgmt("io-cap", "3")
        logger.info("Enabled btmgmt LE advertising as %s", name)

    if shutil.which("bluetoothctl"):
        _run(["bluetoothctl", "--timeout", "5", "power", "on"])
        _run(["bluetoothctl", "--timeout", "5", "system-alias", name])
        _run(["bluetoothctl", "--timeout", "5", "discoverable", "on"])
        _run(["bluetoothctl", "--timeout", "5", "pairable", "on"])
        # Keep pairable briefly for first connect; app does not require OS pairing.
        logger.info("Set bluetoothctl alias/discoverable for %s", name)


def is_advertisement_error(exc: Exception) -> bool:
    message = str(exc).lower()
    return "advertisement" in message or "advert" in message
