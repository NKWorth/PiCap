"""Image preprocessing helpers for OCR."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import cv2
import numpy as np


@dataclass(frozen=True)
class PreprocessOptions:
    upscale_factor: float = 2.0
    sharpen: float = 1.0
    contrast: float = 2.0
    threshold: str = "otsu"
    invert: str = "auto"
    denoise: bool = True
    border_pad_ratio: float = 0.2

    @classmethod
    def from_ocr_config(cls, ocr_config: dict[str, Any]) -> PreprocessOptions:
        nested = ocr_config.get("preprocess")
        source = nested if isinstance(nested, dict) else ocr_config
        threshold = str(source.get("threshold", "otsu")).strip().lower()
        if threshold not in {"otsu", "adaptive", "none"}:
            threshold = "otsu"
        invert = str(source.get("invert", "auto")).strip().lower()
        if invert in {"true", "yes", "on", "1"}:
            invert = "on"
        elif invert in {"false", "no", "off", "0"}:
            invert = "off"
        else:
            invert = "auto"
        return cls(
            upscale_factor=float(source.get("upscale_factor", ocr_config.get("upscale_factor", 2.0))),
            sharpen=float(source.get("sharpen", 1.0)),
            contrast=float(source.get("contrast", 2.0)),
            threshold=threshold,
            invert=invert,
            denoise=bool(source.get("denoise", True)),
            border_pad_ratio=float(source.get("border_pad_ratio", 0.2)),
        )

    def with_overrides(self, **kwargs: Any) -> PreprocessOptions:
        data = {
            "upscale_factor": self.upscale_factor,
            "sharpen": self.sharpen,
            "contrast": self.contrast,
            "threshold": self.threshold,
            "invert": self.invert,
            "denoise": self.denoise,
            "border_pad_ratio": self.border_pad_ratio,
        }
        data.update(kwargs)
        return PreprocessOptions(**data)


def pad_crop(image: np.ndarray, border_pad_ratio: float) -> np.ndarray:
    if border_pad_ratio <= 0:
        return image
    height, width = image.shape[:2]
    pad_x = max(2, int(width * border_pad_ratio))
    pad_y = max(2, int(height * border_pad_ratio))
    if image.ndim == 3:
        value = image[0, 0].tolist()
    else:
        value = int(image[0, 0])
    return cv2.copyMakeBorder(
        image,
        pad_y,
        pad_y,
        pad_x,
        pad_x,
        borderType=cv2.BORDER_CONSTANT,
        value=value,
    )


def preprocess_for_ocr(image: np.ndarray, options: PreprocessOptions) -> np.ndarray:
    working = image
    if options.border_pad_ratio > 0:
        working = pad_crop(working, options.border_pad_ratio)

    gray = cv2.cvtColor(working, cv2.COLOR_BGR2GRAY) if working.ndim == 3 else working.copy()
    scale = max(1.0, options.upscale_factor)
    if scale != 1.0:
        gray = cv2.resize(gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)

    if options.denoise:
        gray = cv2.bilateralFilter(gray, d=5, sigmaColor=40, sigmaSpace=40)

    clip_limit = max(1.0, options.contrast)
    clahe = cv2.createCLAHE(clipLimit=clip_limit, tileGridSize=(8, 8))
    gray = clahe.apply(gray)

    if options.sharpen > 0:
        blurred = cv2.GaussianBlur(gray, (0, 0), sigmaX=1.2)
        gray = cv2.addWeighted(gray, 1.0 + options.sharpen, blurred, -options.sharpen, 0)

    gray = _apply_invert(gray, options.invert)

    if options.threshold == "none":
        return gray

    if options.threshold == "adaptive":
        block = max(3, int(min(gray.shape[:2]) / 12) | 1)
        return cv2.adaptiveThreshold(
            gray,
            255,
            cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
            cv2.THRESH_BINARY,
            block,
            4,
        )

    blurred = cv2.GaussianBlur(gray, (3, 3), 0)
    _, thresh = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    return thresh


def preprocess_variants(image: np.ndarray, options: PreprocessOptions) -> list[np.ndarray]:
    primary = preprocess_for_ocr(image, options)
    variants = [primary]
    if options.threshold != "adaptive":
        variants.append(
            preprocess_for_ocr(
                image,
                options.with_overrides(threshold="adaptive"),
            )
        )
    if options.upscale_factor < 4.0:
        variants.append(
            preprocess_for_ocr(
                image,
                options.with_overrides(upscale_factor=max(options.upscale_factor * 1.5, 3.0)),
            )
        )
    return _dedupe_arrays(variants)


def _apply_invert(gray: np.ndarray, invert: str) -> np.ndarray:
    if invert == "on":
        return cv2.bitwise_not(gray)
    if invert == "off":
        return gray
    # Tesseract is most reliable with dark text on a light background.
    if float(gray.mean()) < 127.0:
        return cv2.bitwise_not(gray)
    return gray


def _dedupe_arrays(arrays: list[np.ndarray]) -> list[np.ndarray]:
    unique: list[np.ndarray] = []
    seen: set[tuple[int, ...]] = set()
    for array in arrays:
        digest = (array.shape[0], array.shape[1], int(array.sum()))
        if digest in seen:
            continue
        seen.add(digest)
        unique.append(array)
    return unique
