"""Camera capture for OpenCV and Pi Camera Module."""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import cv2
import numpy as np

logger = logging.getLogger(__name__)


class CameraCapture:
    def __init__(self, camera_config: dict[str, Any]) -> None:
        self.source = camera_config.get("source", "opencv")
        self.device_index = int(camera_config.get("device_index", 0))
        self.resolution = tuple(camera_config.get("resolution", [1920, 1080]))
        self.capture_dir = Path(camera_config.get("capture_dir", "data/captures"))
        self.capture_dir.mkdir(parents=True, exist_ok=True)
        self._picamera: Any | None = None
        self._opencv_cap: cv2.VideoCapture | None = None

    def open(self) -> None:
        if self.source == "picamera2":
            self._open_picamera()
        else:
            self._open_opencv()

    def close(self) -> None:
        if self._opencv_cap is not None:
            self._opencv_cap.release()
            self._opencv_cap = None
        if self._picamera is not None:
            self._picamera.stop()
            self._picamera.close()
            self._picamera = None

    def capture(self) -> tuple[np.ndarray, Path]:
        frame = self._read_frame()
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
        image_path = self.capture_dir / f"capture_{timestamp}.jpg"
        if not cv2.imwrite(str(image_path), frame):
            raise RuntimeError(f"Failed to write image to {image_path}")
        return frame, image_path

    def _open_opencv(self) -> None:
        cap = cv2.VideoCapture(self.device_index)
        if not cap.isOpened():
            raise RuntimeError(f"Unable to open camera device {self.device_index}")
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, self.resolution[0])
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, self.resolution[1])
        self._opencv_cap = cap

    def _open_picamera(self) -> None:
        try:
            from picamera2 import Picamera2
        except ImportError as exc:
            raise RuntimeError(
                "picamera2 is not installed. Install it on Raspberry Pi OS."
            ) from exc

        picam = Picamera2()
        config = picam.create_still_configuration(
            main={"size": self.resolution},
        )
        picam.configure(config)
        picam.start()
        self._picamera = picam
        logger.info("Pi Camera Module initialized")

    def _read_frame(self) -> np.ndarray:
        if self.source == "picamera2":
            if self._picamera is None:
                raise RuntimeError("Camera is not open")
            frame = self._picamera.capture_array()
            if frame.ndim == 3 and frame.shape[2] == 3:
                return cv2.cvtColor(frame, cv2.COLOR_RGB2BGR)
            return frame

        if self._opencv_cap is None:
            raise RuntimeError("Camera is not open")
        ok, frame = self._opencv_cap.read()
        if not ok or frame is None:
            raise RuntimeError("Failed to read frame from camera")
        return frame
