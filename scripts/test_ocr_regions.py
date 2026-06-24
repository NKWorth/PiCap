#!/usr/bin/env python3
"""Run OCR on a live frame or saved image and print region readings."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import cv2

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from picap.camera import CameraCapture
from picap.config_manager import ConfigManager
from picap.ocr import OcrEngine


def load_frame(args: argparse.Namespace):
    if args.image:
        path = Path(args.image)
        frame = cv2.imread(str(path))
        if frame is None:
            raise SystemExit(f"Could not read image: {path}")
        return frame, str(path)

    config = ConfigManager(args.config)
    camera = CameraCapture(config.get("camera", default={}))
    camera.open()
    try:
        return camera.capture()
    finally:
        camera.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Test PiCap OCR regions")
    parser.add_argument("--config", default="config.yaml")
    parser.add_argument("--image", help="Image file to read")
    parser.add_argument("--live", action="store_true", help="Capture from camera")
    args = parser.parse_args()

    if not args.image and not args.live:
        parser.error("Provide --image PATH or --live")

    config = ConfigManager(args.config)
    frame, source = load_frame(args)
    engine = OcrEngine(config.get("ocr", default={}))
    regions = config.get_regions() if engine.mode == "regions" else None
    readings = engine.read_image(frame, regions)

    print(f"Source: {source}")
    print(f"OCR mode: {engine.mode}")
    print(json.dumps([reading.to_dict() for reading in readings], indent=2))


if __name__ == "__main__":
    main()
