#!/usr/bin/env python3
"""Interactive OCR region calibration on a camera frame or image file."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2
import numpy as np
import yaml

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from picap.camera import CameraCapture
from picap.config_manager import ConfigManager

DEFAULT_NAMES = [
    "order_point_15min_avg",
    "current_otw_15min_avg",
]


class RegionCalibrator:
    def __init__(self, image: np.ndarray, existing: list[dict] | None = None) -> None:
        self.base = image.copy()
        self.image = image.copy()
        self.regions: list[dict] = list(existing or [])
        self.drawing = False
        self.start: tuple[int, int] | None = None
        self.current: tuple[int, int] | None = None
        self.window = "PiCap region calibration (drag boxes, s=save, u=undo, r=reset, q=quit)"

    def run(self) -> list[dict]:
        cv2.namedWindow(self.window, cv2.WINDOW_NORMAL)
        cv2.setMouseCallback(self.window, self._on_mouse)
        print(
            "Draw rectangles around each MM:SS field.\n"
            "  drag       — define a region\n"
            "  u          — undo last region\n"
            "  r          — clear all regions\n"
            "  s / Enter  — save YAML and preview image\n"
            "  q / Esc    — quit without saving\n"
        )
        while True:
            self._redraw()
            key = cv2.waitKey(20) & 0xFF
            if key in {27, ord("q")}:
                break
            if key == ord("u") and self.regions:
                self.regions.pop()
            if key == ord("r"):
                self.regions.clear()
            if key in {ord("s"), 13}:
                self._save()
                break
        cv2.destroyAllWindows()
        return self.regions

    def _on_mouse(self, event: int, x: int, y: int, _flags: int, _param: object) -> None:
        if event == cv2.EVENT_LBUTTONDOWN:
            self.drawing = True
            self.start = (x, y)
            self.current = (x, y)
        elif event == cv2.EVENT_MOUSEMOVE and self.drawing and self.start:
            self.current = (x, y)
        elif event == cv2.EVENT_LBUTTONUP and self.start:
            self.drawing = False
            x1, y1 = self.start
            x2, y2 = x, y
            region = {
                "x": min(x1, x2),
                "y": min(y1, y2),
                "width": abs(x2 - x1),
                "height": abs(y2 - y1),
            }
            if region["width"] > 4 and region["height"] > 4:
                self.regions.append(region)
            self.start = None
            self.current = None

    def _redraw(self) -> None:
        canvas = self.base.copy()
        for index, region in enumerate(self.regions):
            x, y, w, h = region["x"], region["y"], region["width"], region["height"]
            name = region.get("name", f"region_{index + 1}")
            cv2.rectangle(canvas, (x, y), (x + w, y + h), (0, 255, 0), 2)
            cv2.putText(canvas, name, (x, max(20, y - 8)), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2)
        if self.drawing and self.start and self.current:
            x1, y1 = self.start
            x2, y2 = self.current
            cv2.rectangle(canvas, (x1, y1), (x2, y2), (0, 200, 255), 2)
        cv2.imshow(self.window, canvas)

    def _save(self) -> None:
        if not self.regions:
            print("No regions drawn.")
            return
        for index, region in enumerate(self.regions):
            if "name" not in region:
                region["name"] = DEFAULT_NAMES[index] if index < len(DEFAULT_NAMES) else f"region_{index + 1}"
            if "format" not in region:
                region["format"] = "time"
        preview = self.base.copy()
        for region in self.regions:
            x, y, w, h = region["x"], region["y"], region["width"], region["height"]
            cv2.rectangle(preview, (x, y), (x + w, y + h), (0, 255, 0), 2)
        out_preview = ROOT / "data" / "regions_preview.jpg"
        out_preview.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(out_preview), preview)
        print(f"Preview saved: {out_preview}")
        print("\nPaste into config.yaml:\n")
        print(yaml.safe_dump({"ocr": {"mode": "regions"}, "regions": self.regions}, sort_keys=False))


def load_image(args: argparse.Namespace) -> np.ndarray:
    if args.image:
        path = Path(args.image)
        image = cv2.imread(str(path))
        if image is None:
            raise SystemExit(f"Could not read image: {path}")
        return image

    config = ConfigManager(args.config)
    camera = CameraCapture(config.get("camera", default={}))
    camera.open()
    try:
        frame, path = camera.capture()
        print(f"Captured: {path}")
        return frame
    finally:
        camera.close()


def existing_regions(config_path: Path) -> list[dict]:
    config = ConfigManager(config_path)
    return [region.to_dict() for region in config.get_regions()]


def main() -> None:
    parser = argparse.ArgumentParser(description="Calibrate OCR regions for PiCap")
    parser.add_argument("--config", default="config.yaml", help="Config path (for live capture)")
    parser.add_argument("--image", help="Use an existing image instead of capturing")
    parser.add_argument("--capture", action="store_true", help="Capture from camera (default if no --image)")
    args = parser.parse_args()

    if not args.image and not args.capture:
        parser.error("Provide --image PATH or --capture")

    image = load_image(args)
    existing = existing_regions(Path(args.config)) if Path(args.config).exists() else []
    calibrator = RegionCalibrator(image, existing=existing if existing else None)
    calibrator.run()


if __name__ == "__main__":
    main()
