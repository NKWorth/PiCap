"""OCR extraction with automatic number detection or optional fixed regions."""

from __future__ import annotations

import logging
import re
from typing import Any

import cv2
import numpy as np
import pytesseract
from PIL import Image

from picap.models import Region, RegionReading

logger = logging.getLogger(__name__)

_NUMBER_PATTERN = re.compile(r"[0-9]+(?:\.[0-9]+)?")
_TIME_PATTERN = re.compile(r"(\d{1,2})\s*[:.]\s*(\d{2})")


class OcrEngine:
    def __init__(self, ocr_config: dict[str, Any]) -> None:
        self.mode = ocr_config.get("mode", "auto")
        self.tesseract_config = ocr_config.get(
            "tesseract_config", "--psm 7 -c tessedit_char_whitelist=0123456789.-"
        )
        self.auto_psm = int(ocr_config.get("auto_psm", 11))
        self.min_confidence = float(ocr_config.get("min_confidence", 60))
        self.min_digits = int(ocr_config.get("min_digits", 1))
        self.upscale_factor = float(ocr_config.get("upscale_factor", 2.0))
        self.merge_line_tolerance = int(ocr_config.get("merge_line_tolerance", 15))
        self.merge_gap_tolerance = int(ocr_config.get("merge_gap_tolerance", 30))
        self.time_tesseract_config = ocr_config.get(
            "time_tesseract_config",
            "--psm 7 -c tessedit_char_whitelist=0123456789:",
        )

    def read_image(
        self,
        image: np.ndarray,
        regions: list[Region] | None = None,
    ) -> list[RegionReading]:
        if self.mode == "regions" and regions:
            return self.read_regions(image, regions)
        return self.read_auto(image)

    def read_regions(self, image: np.ndarray, regions: list[Region]) -> list[RegionReading]:
        readings: list[RegionReading] = []
        height, width = image.shape[:2]
        for region in regions:
            x1 = max(0, region.x)
            y1 = max(0, region.y)
            x2 = min(width, region.x + region.width)
            y2 = min(height, region.y + region.height)
            if x2 <= x1 or y2 <= y1:
                readings.append(
                    RegionReading(region.name, None, 0.0, x=region.x, y=region.y, width=region.width, height=region.height)
                )
                continue

            crop = image[y1:y2, x1:x2]
            value, confidence = self._read_value(crop, region.format)
            readings.append(
                RegionReading(
                    region.name,
                    value,
                    confidence,
                    x=region.x,
                    y=region.y,
                    width=region.width,
                    height=region.height,
                )
            )
        return readings

    def read_auto(self, image: np.ndarray) -> list[RegionReading]:
        processed, scale = self._preprocess(image)
        config = (
            f"--psm {self.auto_psm} "
            "-c tessedit_char_whitelist=0123456789.- "
            "-c preserve_interword_spaces=1"
        )
        data = pytesseract.image_to_data(
            Image.fromarray(processed),
            config=config,
            output_type=pytesseract.Output.DICT,
        )

        detections: list[dict[str, Any]] = []
        for index, text in enumerate(data["text"]):
            if not text or data["conf"][index] == "-1":
                continue
            confidence = float(data["conf"][index])
            if confidence < self.min_confidence:
                continue

            cleaned = self._normalize_number(text)
            if cleaned is None or len(re.sub(r"[^0-9]", "", cleaned)) < self.min_digits:
                continue

            x = int(float(data["left"][index]) / scale)
            y = int(float(data["top"][index]) / scale)
            width = max(1, int(float(data["width"][index]) / scale))
            height = max(1, int(float(data["height"][index]) / scale))
            detections.append(
                {
                    "value": cleaned,
                    "confidence": confidence,
                    "x": x,
                    "y": y,
                    "width": width,
                    "height": height,
                }
            )

        merged = self._merge_detections(detections)
        merged.sort(key=lambda item: (item["y"], item["x"]))

        readings: list[RegionReading] = []
        for index, item in enumerate(merged, start=1):
            readings.append(
                RegionReading(
                    name=f"value_{index}",
                    value=item["value"],
                    confidence=item["confidence"],
                    x=item["x"],
                    y=item["y"],
                    width=item["width"],
                    height=item["height"],
                )
            )

        logger.info("Auto OCR detected %s numeric values", len(readings))
        return readings

    def _read_value(self, crop: np.ndarray, value_format: str = "number") -> tuple[str | None, float]:
        tesseract_config = self.time_tesseract_config if value_format == "time" else self.tesseract_config
        processed, _scale = self._preprocess(crop)
        data = pytesseract.image_to_data(
            Image.fromarray(processed),
            config=tesseract_config,
            output_type=pytesseract.Output.DICT,
        )

        normalize = self._normalize_time if value_format == "time" else self._normalize_number

        best_value: str | None = None
        best_confidence = 0.0
        for text, conf in zip(data["text"], data["conf"]):
            if not text or conf == "-1":
                continue
            confidence = float(conf)
            cleaned = normalize(text)
            if cleaned and confidence >= self.min_confidence and confidence >= best_confidence:
                best_value = cleaned
                best_confidence = confidence

        if best_value is None:
            fallback = pytesseract.image_to_string(
                Image.fromarray(processed),
                config=tesseract_config,
            )
            cleaned = normalize(fallback)
            if cleaned:
                best_value = cleaned
                best_confidence = float(self.min_confidence)

        return best_value, best_confidence

    def _read_number(self, crop: np.ndarray) -> tuple[str | None, float]:
        return self._read_value(crop, "number")

    def _preprocess(self, image: np.ndarray) -> tuple[np.ndarray, float]:
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        scale = self.upscale_factor
        scaled = cv2.resize(gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
        blurred = cv2.GaussianBlur(scaled, (3, 3), 0)
        _, thresh = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        return thresh, scale

    def _merge_detections(self, detections: list[dict[str, Any]]) -> list[dict[str, Any]]:
        if not detections:
            return []

        sorted_items = sorted(detections, key=lambda item: (item["y"], item["x"]))
        merged: list[dict[str, Any]] = []

        for item in sorted_items:
            match = None
            for existing in merged:
                same_line = abs(existing["y"] - item["y"]) <= self.merge_line_tolerance
                horizontal_gap = item["x"] - (existing["x"] + existing["width"])
                adjacent = -existing["width"] <= horizontal_gap <= self.merge_gap_tolerance
                if same_line and adjacent:
                    match = existing
                    break

            if match is None:
                merged.append(dict(item))
                continue

            match["value"] = f"{match['value']}{item['value']}"
            match["confidence"] = min(match["confidence"], item["confidence"])
            right = max(match["x"] + match["width"], item["x"] + item["width"])
            bottom = max(match["y"] + match["height"], item["y"] + item["height"])
            match["x"] = min(match["x"], item["x"])
            match["y"] = min(match["y"], item["y"])
            match["width"] = right - match["x"]
            match["height"] = bottom - match["y"]

        return merged

    @staticmethod
    def _normalize_number(text: str) -> str | None:
        match = _NUMBER_PATTERN.search(text.strip())
        if not match:
            return None
        cleaned = match.group(0)
        if cleaned in {".", "-", "-."}:
            return None
        return cleaned

    @staticmethod
    def _normalize_time(text: str) -> str | None:
        cleaned = (
            text.strip()
            .replace("O", "0")
            .replace("o", "0")
            .replace("l", "1")
            .replace("I", "1")
            .replace("|", "1")
        )
        match = _TIME_PATTERN.search(cleaned)
        if not match:
            return None
        minutes, seconds = match.groups()
        if int(seconds) >= 60:
            return None
        return f"{int(minutes):02d}:{int(seconds):02d}"
