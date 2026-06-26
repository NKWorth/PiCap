"""Apply and query V4L2 camera controls for USB webcams on Linux."""

from __future__ import annotations

import logging
import re
import shutil
import subprocess
from typing import Any

logger = logging.getLogger(__name__)

_LINE_PATTERN = re.compile(
    r"^(\w+)\s+0x[0-9a-fA-F]+\s+\((\w+)\)\s*:\s*(.+)$",
)

_APPLY_ORDER = (
    "power_line_frequency",
    "white_balance_temperature_auto",
    "white_balance_temperature",
    "exposure_auto",
    "exposure_absolute",
    "focus_auto",
    "focus_absolute",
    "brightness",
    "contrast",
    "saturation",
    "sharpness",
    "gain",
    "backlight_compensation",
)


def resolve_device_path(camera_config: dict[str, Any]) -> str:
    explicit = camera_config.get("v4l2_device")
    if explicit:
        return str(explicit)
    device_index = int(camera_config.get("device_index", 0))
    return f"/dev/video{device_index}"


def v4l2_available() -> bool:
    return shutil.which("v4l2-ctl") is not None


def list_controls(device: str) -> list[dict[str, Any]]:
    if not v4l2_available():
        return []
    result = subprocess.run(
        ["v4l2-ctl", "-d", device, "--list-ctrls"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        stderr = (result.stderr or result.stdout or "").strip()
        raise RuntimeError(stderr or f"v4l2-ctl failed for {device}")
    return _parse_list_ctrls(result.stdout)


def apply_controls(device: str, controls: dict[str, Any]) -> None:
    if not controls:
        return
    if not v4l2_available():
        raise RuntimeError("v4l2-ctl is not installed. Run: sudo apt install -y v4l-utils")

    ordered = _ordered_controls(controls)
    assignment = ",".join(f"{name}={value}" for name, value in ordered.items())
    result = subprocess.run(
        ["v4l2-ctl", "-d", device, f"--set-ctrl={assignment}"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        stderr = (result.stderr or result.stdout or "").strip()
        raise RuntimeError(stderr or "Failed to apply V4L2 controls")


def apply_pixel_format(
    device: str,
    *,
    width: int,
    height: int,
    pixel_format: str,
) -> None:
    if not pixel_format:
        return
    if not v4l2_available():
        raise RuntimeError("v4l2-ctl is not installed. Run: sudo apt install -y v4l-utils")
    result = subprocess.run(
        [
            "v4l2-ctl",
            "-d",
            device,
            f"--set-fmt-video=width={width},height={height},pixelformat={pixel_format}",
        ],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        stderr = (result.stderr or result.stdout or "").strip()
        raise RuntimeError(stderr or f"Failed to set pixel format {pixel_format}")


def apply_configured_controls(camera_config: dict[str, Any]) -> None:
    if camera_config.get("source") != "opencv":
        return
    if not v4l2_available():
        logger.warning("v4l2-ctl not found; skipping camera control setup")
        return

    device = resolve_device_path(camera_config)
    resolution = camera_config.get("resolution", [1920, 1080])
    width = int(resolution[0]) if isinstance(resolution, (list, tuple)) and resolution else 1920
    height = int(resolution[1]) if isinstance(resolution, (list, tuple)) and len(resolution) > 1 else 1080
    pixel_format = str(camera_config.get("pixel_format", "")).strip()
    if pixel_format:
        try:
            apply_pixel_format(device, width=width, height=height, pixel_format=pixel_format)
        except Exception as exc:
            logger.warning("Could not set pixel format %s: %s", pixel_format, exc)

    controls = camera_config.get("v4l2_controls")
    if not isinstance(controls, dict) or not controls:
        return
    normalized = {str(key): _coerce_control_value(value) for key, value in controls.items()}
    apply_controls(device, normalized)
    logger.info("Applied %s V4L2 control(s) on %s", len(normalized), device)


def build_controls_response(camera_config: dict[str, Any]) -> dict[str, Any]:
    source = camera_config.get("source", "opencv")
    if source != "opencv":
        return {
            "supported": False,
            "reason": "V4L2 controls are only available when camera.source is opencv",
            "device": None,
            "controls": [],
            "configured": {},
        }

    device = resolve_device_path(camera_config)
    configured_raw = camera_config.get("v4l2_controls", {})
    configured = (
        {str(key): _coerce_control_value(value) for key, value in configured_raw.items()}
        if isinstance(configured_raw, dict)
        else {}
    )

    if not v4l2_available():
        return {
            "supported": False,
            "reason": "v4l2-ctl is not installed on the Pi",
            "device": device,
            "controls": [],
            "configured": configured,
        }

    try:
        controls = list_controls(device)
    except Exception as exc:
        return {
            "supported": False,
            "reason": str(exc),
            "device": device,
            "controls": [],
            "configured": configured,
        }

    for control in controls:
        name = control["name"]
        if name in configured:
            control["configured_value"] = configured[name]

    return {
        "supported": True,
        "device": device,
        "controls": controls,
        "configured": configured,
        "pixel_format": camera_config.get("pixel_format"),
    }


def _parse_list_ctrls(text: str) -> list[dict[str, Any]]:
    controls: list[dict[str, Any]] = []
    for line in text.splitlines():
        match = _LINE_PATTERN.match(line.strip())
        if not match:
            continue
        name, control_type, tail = match.groups()
        fields = dict(_FIELD_PATTERN.findall(tail))
        if "min" not in fields or "max" not in fields:
            continue
        controls.append(
            {
                "name": name,
                "type": control_type,
                "min": _parse_field_value(fields["min"]),
                "max": _parse_field_value(fields["max"]),
                "step": _parse_field_value(fields.get("step", "1")),
                "default": _parse_field_value(fields.get("default", fields.get("value", "0"))),
                "value": _parse_field_value(fields.get("value", fields.get("default", "0"))),
            }
        )
    return controls


_FIELD_PATTERN = re.compile(r"(\w+)=([^\s]+)")


def _parse_field_value(raw: str) -> int:
    if raw.lower() == "true":
        return 1
    if raw.lower() == "false":
        return 0
    return int(raw)


def _coerce_control_value(value: Any) -> int:
    if isinstance(value, bool):
        return 1 if value else 0
    return int(value)


def _ordered_controls(controls: dict[str, Any]) -> dict[str, int]:
    names = list(controls.keys())

    def sort_key(name: str) -> tuple[int, str]:
        if name in _APPLY_ORDER:
            return (_APPLY_ORDER.index(name), name)
        return (len(_APPLY_ORDER), name)

    ordered_names = sorted(names, key=sort_key)
    return {name: _coerce_control_value(controls[name]) for name in ordered_names}
