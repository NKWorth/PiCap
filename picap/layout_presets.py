"""Built-in layout presets merged into config when layout is set."""

from __future__ import annotations

from typing import Any

OTW_MONITOR_REGIONS: list[dict[str, Any]] = [
    {
        "name": "order_point_15min_avg",
        "format": "time",
        "x": 120,
        "y": 310,
        "width": 140,
        "height": 50,
    },
    {
        "name": "current_otw_15min_avg",
        "format": "time",
        "x": 860,
        "y": 520,
        "width": 120,
        "height": 45,
    },
]

LAYOUT_PRESETS: dict[str, dict[str, Any]] = {
    "otw-monitor": {
        "ocr": {
            "mode": "regions",
            "min_confidence": 45,
            "min_digits": 1,
            "upscale_factor": 4.0,
            "sharpen": 1.2,
            "contrast": 2.5,
            "threshold": "otsu",
            "denoise": True,
            "time_multi_pass": True,
            "tesseract_config": "--psm 7 -c tessedit_char_whitelist=0123456789.-",
            "time_tesseract_config": "--psm 7 -c tessedit_char_whitelist=0123456789:",
        },
        "regions": OTW_MONITOR_REGIONS,
        "regions_ref": [1920, 1080],
        "forced_ocr_keys": ("mode", "tesseract_config", "time_tesseract_config"),
    },
}
