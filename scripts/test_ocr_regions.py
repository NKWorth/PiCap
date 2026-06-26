#!/usr/bin/env python3
"""Run OCR on a live frame or saved image and print region readings."""

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
ensure_import("cv2", pip_hint="On Raspberry Pi OS: sudo apt install -y python3-venv tesseract-ocr")

import cv2

from picap.camera import CameraCapture
from picap.config_manager import ConfigManager
from picap.ocr import OcrEngine
from picap.stored_capture import load_image_file, load_latest_stored_capture

_CAMERA_BUSY_HELP = (
    "The camera may already be in use by the running PiCap service, or the wrong "
    "camera source/device is configured.\n\n"
    "Try one of these instead:\n"
    "  .venv/bin/python scripts/test_ocr_regions.py --config config.yaml --latest --save-crops\n"
    "  .venv/bin/python scripts/test_ocr_regions.py --config config.yaml --image data/captures/FILE.jpg --save-crops\n\n"
    "To test with a live camera, stop PiCap first:\n"
    "  bash scripts/start-picap.sh --stop"
)


def load_live_frame(config_path: str) -> tuple[object, str]:
    config = ConfigManager(config_path)
    camera = CameraCapture(config.get("camera", default={}))
    try:
        camera.open()
    except RuntimeError as exc:
        raise SystemExit(f"{exc}\n\n{_CAMERA_BUSY_HELP}") from exc
    try:
        output = camera.capture()
        return output.frame, str(output.image_path)
    except RuntimeError as exc:
        raise SystemExit(f"{exc}\n\n{_CAMERA_BUSY_HELP}") from exc
    finally:
        camera.close()


def load_frame(args: argparse.Namespace) -> tuple[object, str]:
    if args.image:
        path = Path(args.image)
        return load_image_file(path), str(path.resolve())
    if args.latest:
        return load_latest_stored_capture(args.config)
    if args.live:
        return load_live_frame(args.config)
    raise SystemExit("No image source selected.")


def main() -> None:
    parser = argparse.ArgumentParser(description="Test PiCap OCR regions")
    parser.add_argument("--config", default="config.yaml")
    source_group = parser.add_mutually_exclusive_group()
    source_group.add_argument("--image", help="Image file to read")
    source_group.add_argument("--live", action="store_true", help="Capture from camera")
    source_group.add_argument(
        "--latest",
        action="store_true",
        help="Use the latest stored capture from the database (default)",
    )
    parser.add_argument(
        "--save-crops",
        action="store_true",
        help="Write preprocessed OCR crops to data/ocr_debug/",
    )
    args = parser.parse_args()

    if not args.image and not args.live and not args.latest:
        args.latest = True

    config = ConfigManager(args.config)
    frame, source = load_frame(args)
    engine = OcrEngine(config.get("ocr", default={}))
    height, width = frame.shape[:2]
    regions = config.get_regions_for_image(width, height) if engine.mode == "regions" else None
    readings = engine.read_image(frame, regions)

    if args.save_crops and regions:
        debug_dir = ROOT / "data" / "ocr_debug"
        debug_dir.mkdir(parents=True, exist_ok=True)
        height, width = frame.shape[:2]
        for region in regions:
            x1 = max(0, region.x)
            y1 = max(0, region.y)
            x2 = min(width, region.x + region.width)
            y2 = min(height, region.y + region.height)
            if x2 <= x1 or y2 <= y1:
                continue
            crop = frame[y1:y2, x1:x2]
            raw_path = debug_dir / f"{region.name}_raw.jpg"
            processed_path = debug_dir / f"{region.name}_processed.jpg"
            cv2.imwrite(str(raw_path), crop)
            cv2.imwrite(str(processed_path), engine.render_debug_crop(crop, region.format))
            print(f"Saved {raw_path.name} and {processed_path.name}")

    print(f"Source: {source}")
    print(f"OCR mode: {engine.mode}")
    print(json.dumps([reading.to_dict() for reading in readings], indent=2))


if __name__ == "__main__":
    main()
