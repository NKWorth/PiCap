"""Downscale and encode frames for BLE calibration image transfer."""

from __future__ import annotations

import cv2
import numpy as np

DEFAULT_MAX_WIDTH = 800
DEFAULT_JPEG_QUALITY = 75
BLE_CHUNK_SIZE = 480


def encode_calibration_jpeg(
    frame: np.ndarray,
    *,
    max_width: int = DEFAULT_MAX_WIDTH,
    quality: int = DEFAULT_JPEG_QUALITY,
) -> tuple[bytes, int, int]:
    if frame is None or frame.size == 0:
        raise ValueError("Empty image frame")

    image = frame
    height, width = image.shape[:2]
    if max_width > 0 and width > max_width:
        scale = max_width / width
        image = cv2.resize(
            image,
            (max_width, max(1, int(height * scale))),
            interpolation=cv2.INTER_AREA,
        )
        height, width = image.shape[:2]

    ok, encoded = cv2.imencode(
        ".jpg",
        image,
        [int(cv2.IMWRITE_JPEG_QUALITY), max(1, min(int(quality), 100))],
    )
    if not ok:
        raise RuntimeError("Failed to encode calibration JPEG")
    return encoded.tobytes(), int(width), int(height)
