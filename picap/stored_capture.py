"""Load stored capture images from the database or filesystem."""

from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np

from picap.camera import CameraCapture
from picap.config_manager import ConfigManager
from picap.db import Database


def resolve_stored_image_path(image_path_str: str, capture_dir: Path) -> Path | None:
    raw = Path(image_path_str)
    candidates: list[Path] = []
    if raw.is_absolute():
        candidates.append(raw)
    else:
        candidates.append(Path.cwd() / raw)
        candidates.append(capture_dir / raw.name)
        candidates.append(capture_dir.parent / raw)
    seen: set[Path] = set()
    for candidate in candidates:
        try:
            resolved = candidate.resolve()
        except OSError:
            continue
        if resolved in seen:
            continue
        seen.add(resolved)
        if resolved.is_file():
            return resolved
    return None


def load_image_file(path: Path) -> np.ndarray:
    frame = cv2.imread(str(path))
    if frame is None:
        raise RuntimeError(f"Could not read image: {path}")
    return frame


def load_latest_stored_capture(config_path: str | Path) -> tuple[np.ndarray, str]:
    config = ConfigManager(config_path)
    database = Database(config.get("database", "path", default="data/picap.db"))
    latest = database.get_latest_reading()
    if not latest:
        raise RuntimeError("No stored captures in the database. Take a capture first.")

    camera = CameraCapture(config.get("camera", default={}))
    image_path = resolve_stored_image_path(str(latest["image_path"]), camera.capture_dir)
    if image_path is None:
        raise RuntimeError(
            "Latest capture image file is missing on disk: "
            f"{latest.get('image_path')}"
        )
    return load_image_file(image_path), str(image_path)
