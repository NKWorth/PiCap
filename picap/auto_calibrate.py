"""Auto-detect OTW monitor regions from '15 Mins AVG' labels and times below them."""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from typing import Any

import cv2
import numpy as np
import pytesseract
from PIL import Image

from picap.models import Region

logger = logging.getLogger(__name__)

ORDER_POINT_15MIN_AVG = "order_point_15min_avg"
CURRENT_OTW_15MIN_AVG = "current_otw_15min_avg"

_HEADER_PATTERN = re.compile(r"15\s*mins?\s*a\s*v\s*g", re.IGNORECASE)
_TIME_PATTERN = re.compile(r"(\d{1,2})\s*[:.]\s*(\d{2})")
_MIN_HEADER_WORDS = re.compile(r"^(15|mins?|avg|a|v|g)$", re.IGNORECASE)


class AutoCalibrateError(RuntimeError):
    """Raised when region auto-calibration cannot complete."""


@dataclass(frozen=True)
class TextBox:
    text: str
    x: int
    y: int
    width: int
    height: int
    confidence: float

    @property
    def right(self) -> int:
        return self.x + self.width

    @property
    def bottom(self) -> int:
        return self.y + self.height

    @property
    def center_x(self) -> float:
        return self.x + self.width / 2.0

    @property
    def center_y(self) -> float:
        return self.y + self.height / 2.0


@dataclass(frozen=True)
class AutoCalibrateResult:
    regions: list[Region]
    image_width: int
    image_height: int
    headers: list[dict[str, Any]]

    def to_dict(self) -> dict[str, Any]:
        return {
            "regions": [region.to_dict() for region in self.regions],
            "image_width": self.image_width,
            "image_height": self.image_height,
            "headers": self.headers,
        }


def auto_calibrate_regions(
    image: np.ndarray,
    *,
    min_label_confidence: float = 25.0,
    min_time_confidence: float = 20.0,
    upscale_factor: float = 2.0,
) -> AutoCalibrateResult:
    height, width = image.shape[:2]
    if height <= 0 or width <= 0:
        raise AutoCalibrateError("Image is empty")

    label_boxes = _ocr_text_boxes(image, upscale_factor=upscale_factor, min_confidence=min_label_confidence)
    headers = _find_avg_headers(label_boxes, image_width=width, image_height=height)
    if len(headers) < 2:
        raise AutoCalibrateError(
            f"Could not find two '15 Mins AVG' labels (found {len(headers)})"
        )

    headers = sorted(headers, key=lambda item: item["y"])[:2]
    time_boxes = _ocr_time_boxes(image, upscale_factor=upscale_factor, min_confidence=min_time_confidence)

    regions = [
        _region_below_header(
            name=ORDER_POINT_15MIN_AVG,
            header=headers[0],
            time_boxes=time_boxes,
            image_width=width,
            image_height=height,
        ),
        _region_below_header(
            name=CURRENT_OTW_15MIN_AVG,
            header=headers[1],
            time_boxes=time_boxes,
            image_width=width,
            image_height=height,
        ),
    ]
    logger.info(
        "Auto-calibrated regions: order=%s current_otw=%s",
        regions[0],
        regions[1],
    )
    return AutoCalibrateResult(
        regions=regions,
        image_width=width,
        image_height=height,
        headers=headers,
    )


def _ocr_text_boxes(
    image: np.ndarray,
    *,
    upscale_factor: float,
    min_confidence: float,
) -> list[TextBox]:
    processed, scale = _prepare_for_ocr(image, upscale_factor)
    data = pytesseract.image_to_data(
        Image.fromarray(processed),
        config="--psm 11",
        output_type=pytesseract.Output.DICT,
    )
    return _parse_ocr_boxes(data, scale=scale, min_confidence=min_confidence)


def _ocr_time_boxes(
    image: np.ndarray,
    *,
    upscale_factor: float,
    min_confidence: float,
) -> list[TextBox]:
    processed, scale = _prepare_for_ocr(image, upscale_factor)
    data = pytesseract.image_to_data(
        Image.fromarray(processed),
        config="--psm 11 -c tessedit_char_whitelist=0123456789:.",
        output_type=pytesseract.Output.DICT,
    )
    boxes: list[TextBox] = []
    for item in _parse_ocr_boxes(data, scale=scale, min_confidence=min_confidence):
        if _normalize_time(item.text) is None:
            continue
        boxes.append(item)
    return boxes


def _parse_ocr_boxes(
    data: dict[str, list[Any]],
    *,
    scale: float,
    min_confidence: float,
) -> list[TextBox]:
    boxes: list[TextBox] = []
    for index, text in enumerate(data["text"]):
        cleaned = text.strip()
        if not cleaned or data["conf"][index] == "-1":
            continue
        confidence = float(data["conf"][index])
        if confidence < min_confidence:
            continue
        boxes.append(
            TextBox(
                text=cleaned,
                x=int(float(data["left"][index]) / scale),
                y=int(float(data["top"][index]) / scale),
                width=max(1, int(float(data["width"][index]) / scale)),
                height=max(1, int(float(data["height"][index]) / scale)),
                confidence=confidence,
            )
        )
    return boxes


def _prepare_for_ocr(image: np.ndarray, upscale_factor: float) -> tuple[np.ndarray, float]:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    scale = max(1.0, upscale_factor)
    if scale != 1.0:
        gray = cv2.resize(gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
    blurred = cv2.GaussianBlur(gray, (3, 3), 0)
    return blurred, scale


def _find_avg_headers(
    boxes: list[TextBox],
    *,
    image_width: int,
    image_height: int,
) -> list[dict[str, Any]]:
    headers: list[dict[str, Any]] = []
    y_tolerance = max(12, int(image_height * 0.012))

    for line in _group_into_lines(boxes, y_tolerance=y_tolerance):
        line_text = " ".join(word.text for word in line)
        if not _HEADER_PATTERN.search(_normalize_label(line_text)):
            continue
        headers.append(_line_to_header(line))

    if len(headers) >= 2:
        return _dedupe_headers(headers, y_tolerance=y_tolerance)

    anchor_headers = _find_header_clusters(boxes, image_width=image_width, y_tolerance=y_tolerance)
    return _dedupe_headers(anchor_headers, y_tolerance=y_tolerance)


def _find_header_clusters(
    boxes: list[TextBox],
    *,
    image_width: int,
    y_tolerance: int,
) -> list[dict[str, Any]]:
    headers: list[dict[str, Any]] = []
    anchor_words = [box for box in boxes if re.fullmatch(r"15", box.text.strip())]

    for anchor in anchor_words:
        cluster = [anchor]
        for other in boxes:
            if other is anchor:
                continue
            if abs(other.center_y - anchor.center_y) > y_tolerance:
                continue
            if other.x < anchor.x - 10:
                continue
            if other.x > anchor.x + max(260, int(image_width * 0.18)):
                continue
            if not _MIN_HEADER_WORDS.match(other.text.strip()):
                continue
            cluster.append(other)

        has_mins = any(re.fullmatch(r"mins?", text) for text in texts)
        cluster_text = " ".join(word.text for word in cluster)
        if has_mins and _HEADER_PATTERN.search(_normalize_label(cluster_text)):
            headers.append(_line_to_header(cluster))

    return headers


def _group_into_lines(boxes: list[TextBox], *, y_tolerance: int) -> list[list[TextBox]]:
    if not boxes:
        return []
    sorted_boxes = sorted(boxes, key=lambda item: (item.y, item.x))
    lines: list[list[TextBox]] = []
    current_line: list[TextBox] = [sorted_boxes[0]]
    current_y = sorted_boxes[0].center_y

    for box in sorted_boxes[1:]:
        if abs(box.center_y - current_y) <= y_tolerance:
            current_line.append(box)
            current_y = sum(word.center_y for word in current_line) / len(current_line)
            continue
        lines.append(sorted(current_line, key=lambda item: item.x))
        current_line = [box]
        current_y = box.center_y

    lines.append(sorted(current_line, key=lambda item: item.x))
    return lines


def _line_to_header(line: list[TextBox]) -> dict[str, Any]:
    x1 = min(word.x for word in line)
    y1 = min(word.y for word in line)
    x2 = max(word.right for word in line)
    y2 = max(word.bottom for word in line)
    return {
        "x": x1,
        "y": y1,
        "width": max(1, x2 - x1),
        "height": max(1, y2 - y1),
        "text": " ".join(word.text for word in line),
    }


def _dedupe_headers(headers: list[dict[str, Any]], *, y_tolerance: int) -> list[dict[str, Any]]:
    if not headers:
        return []
    unique: list[dict[str, Any]] = []
    for header in sorted(headers, key=lambda item: (item["y"], item["x"])):
        if any(abs(existing["y"] - header["y"]) <= y_tolerance for existing in unique):
            continue
        unique.append(header)
    return unique


def _region_below_header(
    *,
    name: str,
    header: dict[str, Any],
    time_boxes: list[TextBox],
    image_width: int,
    image_height: int,
) -> Region:
    header_bottom = header["y"] + header["height"]
    search_bottom = header_bottom + max(80, int(image_height * 0.14))
    header_center_x = header["x"] + header["width"] / 2.0
    horizontal_slack = max(header["width"] * 0.8, image_width * 0.04)

    candidates = [
        box
        for box in time_boxes
        if header_bottom - 4 <= box.y <= search_bottom
        and abs(box.center_x - header_center_x) <= horizontal_slack
    ]
    if candidates:
        time_box = min(candidates, key=lambda box: box.y)
        padding_x = max(4, int(time_box.width * 0.15))
        padding_y = max(3, int(time_box.height * 0.2))
        x = max(0, time_box.x - padding_x)
        y = max(0, time_box.y - padding_y)
        width = min(image_width - x, time_box.width + padding_x * 2)
        height = min(image_height - y, time_box.height + padding_y * 2)
        return Region(name=name, x=x, y=y, width=max(20, width), height=max(14, height), format="time")

    width = max(70, int(header["width"] * 0.85))
    height = max(32, int(image_height * 0.045))
    x = int(header_center_x - width / 2)
    y = header_bottom + max(4, int(image_height * 0.008))
    x = max(0, min(x, image_width - width))
    y = max(0, min(y, image_height - height))
    logger.warning("No MM:SS found below header %r; using default box for %s", header.get("text"), name)
    return Region(name=name, x=x, y=y, width=width, height=height, format="time")


def _normalize_label(text: str) -> str:
    return re.sub(r"\s+", " ", text.strip().lower())


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
