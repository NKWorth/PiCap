#!/usr/bin/env python3
"""Auto-detect OTW monitor OCR regions from a capture image."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from picap.script_bootstrap import ensure_import, reexec_in_project_venv

reexec_in_project_venv()
ensure_import("cv2")

import cv2
import yaml

from picap.auto_calibrate import AutoCalibrateError, auto_calibrate_regions
from picap.camera import CameraCapture
from picap.config_manager import ConfigManager


def load_image(args: argparse.Namespace) -> tuple[object, Path | None]:
    if args.image:
        path = Path(args.image)
        image = cv2.imread(str(path))
        if image is None:
            raise SystemExit(f"Could not read image: {path}")
        return image, path.resolve()

    config = ConfigManager(args.config)
    camera = CameraCapture(config.get("camera", default={}))
    camera.open()
    try:
        output = camera.capture()
        print(f"Captured: {output.image_path}")
        return output.frame, output.image_path
    finally:
        camera.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Auto-detect OTW OCR regions for PiCap")
    parser.add_argument("--config", default="config.yaml", help="Config path (for live capture)")
    parser.add_argument("--image", help="Use an existing image instead of capturing")
    parser.add_argument("--capture", action="store_true", help="Capture from camera")
    parser.add_argument("--apply", action="store_true", help="Write detected regions into config.yaml")
    args = parser.parse_args()

    if not args.image and not args.capture:
        parser.error("Provide --image PATH or --capture")

    image, image_path = load_image(args)
    try:
        result = auto_calibrate_regions(image)
    except AutoCalibrateError as exc:
        raise SystemExit(str(exc)) from exc

    payload = result.to_dict()
    if image_path is not None:
        payload["image_path"] = str(image_path)
    print(json.dumps(payload, indent=2))

    if args.apply:
        config_path = Path(args.config)
        config = ConfigManager(config_path)
        patch = {
            "replace": False,
            "ocr": {"mode": "regions"},
            "regions": payload["regions"],
        }
        updated = config.update_from_api(patch)
        print(f"\nUpdated {config_path} with {len(updated.get('regions', []))} regions")


if __name__ == "__main__":
    main()
