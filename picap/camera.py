"""Camera capture for OpenCV and Pi Camera Module."""

from __future__ import annotations

import logging
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from picap.frame_quality import is_blank_frame

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class CaptureOutput:
    frame: np.ndarray
    image_path: Path
    reused_last_good: bool = False


class CameraCapture:
    def __init__(self, camera_config: dict[str, Any]) -> None:
        self.source = camera_config.get("source", "opencv")
        self.device_index = int(camera_config.get("device_index", 0))
        self.resolution = tuple(camera_config.get("resolution", [1920, 1080]))
        self.capture_dir = Path(camera_config.get("capture_dir", "data/captures"))
        self.capture_dir.mkdir(parents=True, exist_ok=True)
        self.blank_mean_threshold = float(camera_config.get("blank_mean_threshold", 12.0))
        self.blank_dark_value = int(camera_config.get("blank_dark_value", 16))
        self.blank_dark_ratio = float(camera_config.get("blank_dark_ratio", 0.98))
        self.capture_warmup_frames = max(0, int(camera_config.get("capture_warmup_frames", 2)))
        self.capture_retries = max(1, int(camera_config.get("capture_retries", 3)))
        self._picamera: Any | None = None
        self._opencv_cap: cv2.VideoCapture | None = None
        self._lock = threading.Lock()
        self._last_good_frame: np.ndarray | None = None
        self._last_good_path: Path | None = None

    @property
    def is_open(self) -> bool:
        return self._opencv_cap is not None or self._picamera is not None

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

    def remember_last_good(self, frame: np.ndarray, image_path: Path) -> None:
        if self._is_blank(frame):
            return
        self._last_good_frame = frame.copy()
        self._last_good_path = image_path.resolve()

    def try_load_last_good(self, image_path: Path) -> bool:
        path = image_path.resolve()
        if not path.is_file():
            return False
        frame = cv2.imread(str(path))
        if frame is None or self._is_blank(frame):
            return False
        self._last_good_frame = frame
        self._last_good_path = path
        logger.info("Loaded last good capture from %s", path)
        return True

    def capture(self) -> CaptureOutput:
        with self._lock:
            if not self.is_open:
                self.open()

            frame: np.ndarray | None = None
            for attempt in range(self.capture_retries):
                candidate = self._read_frame_with_warmup()
                if not self._is_blank(candidate):
                    frame = candidate
                    break
                logger.warning(
                    "Blank camera frame on attempt %s/%s",
                    attempt + 1,
                    self.capture_retries,
                )

            if frame is None:
                fallback = self._fallback_output()
                if fallback is not None:
                    return fallback
                raise RuntimeError(
                    "Camera returned a blank image and no previous good capture is available"
                )

            timestamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
            image_path = self.capture_dir / f"capture_{timestamp}.jpg"
            if not cv2.imwrite(str(image_path), frame):
                raise RuntimeError(f"Failed to write image to {image_path}")

            self.remember_last_good(frame, image_path)
            return CaptureOutput(frame=frame, image_path=image_path, reused_last_good=False)

    def preview_jpeg(self, *, max_width: int = 640, quality: int = 75) -> bytes:
        with self._lock:
            if not self.is_open:
                self.open()
            frame = self._read_frame_with_warmup()
            if self._is_blank(frame):
                if self._last_good_frame is None:
                    raise RuntimeError(
                        "Camera returned a blank preview and no previous good capture is available"
                    )
                logger.warning("Blank preview frame; using last good capture")
                frame = self._last_good_frame.copy()

        height, width = frame.shape[:2]
        if width > max_width > 0:
            scale = max_width / width
            frame = cv2.resize(frame, (max_width, max(1, int(height * scale))))

        ok, encoded = cv2.imencode(
            ".jpg",
            frame,
            [int(cv2.IMWRITE_JPEG_QUALITY), max(1, min(quality, 100))],
        )
        if not ok:
            raise RuntimeError("Failed to encode preview JPEG")
        return encoded.tobytes()

    def _fallback_output(self) -> CaptureOutput | None:
        if self._last_good_frame is None or self._last_good_path is None:
            return None
        logger.warning("Using last good capture: %s", self._last_good_path)
        return CaptureOutput(
            frame=self._last_good_frame.copy(),
            image_path=self._last_good_path,
            reused_last_good=True,
        )

    def _is_blank(self, frame: np.ndarray) -> bool:
        return is_blank_frame(
            frame,
            mean_threshold=self.blank_mean_threshold,
            dark_value=self.blank_dark_value,
            dark_ratio=self.blank_dark_ratio,
        )

    def _read_frame_with_warmup(self) -> np.ndarray:
        for _ in range(self.capture_warmup_frames):
            try:
                self._read_frame()
            except RuntimeError:
                if self.capture_warmup_frames == 0:
                    raise
        return self._read_frame()

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
