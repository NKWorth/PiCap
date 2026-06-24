"""Detect unusable camera frames such as all-black captures."""

from __future__ import annotations

import cv2
import numpy as np


def frame_mean_luminance(frame: np.ndarray) -> float:
    if frame.size == 0:
        return 0.0
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY) if frame.ndim == 3 else frame
    return float(gray.mean())


def dark_pixel_ratio(frame: np.ndarray, *, dark_value: int) -> float:
    if frame.size == 0:
        return 1.0
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY) if frame.ndim == 3 else frame
    return float((gray < dark_value).sum()) / float(gray.size)


def is_blank_frame(
    frame: np.ndarray,
    *,
    mean_threshold: float = 12.0,
    dark_value: int = 16,
    dark_ratio: float = 0.98,
) -> bool:
    """Return True when the frame is effectively black or empty."""
    if frame.size == 0:
        return True
    if frame_mean_luminance(frame) <= mean_threshold:
        return True
    return dark_pixel_ratio(frame, dark_value=dark_value) >= dark_ratio
