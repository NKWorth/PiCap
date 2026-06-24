#!/usr/bin/env python3
"""Patch bless for Raspberry Pi / BlueZ legacy advertising limits."""

from __future__ import annotations

import sys
from pathlib import Path


def find_bless_root() -> Path | None:
    try:
        import bless  # noqa: PLC0415
    except ImportError:
        return None
    return Path(bless.__file__).resolve().parent


def patch_advertisement(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    original = text
    for prop in ("TxPower", "MaxInterval", "MinInterval"):
        text = text.replace(
            f"    @dbus_property()\n    def {prop}(",
            f"    # @dbus_property()\n    def {prop}(",
        )
        text = text.replace(f"    @{prop}.setter", f"    # @{prop}.setter")
    if text == original:
        return False
    path.write_text(text, encoding="utf-8")
    return True


def patch_application(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    needle = "        # Only add the first UUID\n        advertisement._service_uuids.append(self.services[0].UUID)\n"
    replacement = (
        "        # Omit 128-bit service UUID from legacy advert (31-byte limit).\n"
        "        # Clients discover PiCap via LocalName and GATT service browse.\n"
    )
    if needle not in text:
        return False
    path.write_text(text.replace(needle, replacement), encoding="utf-8")
    return True


def main() -> int:
    root = find_bless_root()
    if root is None:
        print("bless is not installed; skipping patch", file=sys.stderr)
        return 0

    advertisement = root / "backends" / "bluezdbus" / "dbus" / "advertisement.py"
    application = root / "backends" / "bluezdbus" / "dbus" / "application.py"
    if not advertisement.exists() or not application.exists():
        print("bless BlueZ backend not found; skipping patch", file=sys.stderr)
        return 0

    adv_patched = patch_advertisement(advertisement)
    app_patched = patch_application(application)
    if adv_patched or app_patched:
        print(f"Patched bless at {root}")
        return 0

    print("bless already patched or layout changed; no changes made")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
