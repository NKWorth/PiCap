"""Apply and query V4L2 camera controls for USB webcams on Linux."""

from __future__ import annotations

import logging
import re
import shutil
import subprocess
from pathlib import Path
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


def resolve_device_index(camera_config: dict[str, Any]) -> int:
    explicit = camera_config.get("v4l2_device")
    if explicit:
        match = re.search(r"video(\d+)$", str(explicit))
        if match:
            return int(match.group(1))
    return int(camera_config.get("device_index", 0))


def v4l2_available() -> bool:
    return shutil.which("v4l2-ctl") is not None


def list_video_devices() -> list[dict[str, Any]]:
    """Return capture-capable V4L2 devices with friendly names."""
    devices: list[dict[str, Any]] = []
    if v4l2_available():
        devices = _list_devices_via_v4l2()
    if not devices:
        devices = _list_devices_via_filesystem()

    capture_devices: list[dict[str, Any]] = []
    for device in devices:
        path = str(device["path"])
        if not Path(path).exists():
            continue
        if v4l2_available() and not _device_supports_capture(path):
            continue
        capture_devices.append(device)
    return capture_devices


def _list_devices_via_v4l2() -> list[dict[str, Any]]:
    result = subprocess.run(
        ["v4l2-ctl", "--list-devices"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return []

    devices: list[dict[str, Any]] = []
    current_name = "Camera"
    for raw_line in result.stdout.splitlines():
        line = raw_line.rstrip()
        if not line:
            continue
        if not line.startswith("\t") and not line.startswith(" "):
            current_name = line.rstrip(":").strip() or "Camera"
            continue
        path = line.strip()
        if not path.startswith("/dev/video"):
            continue
        match = re.search(r"video(\d+)$", path)
        if not match:
            continue
        devices.append(
            {
                "path": path,
                "index": int(match.group(1)),
                "name": current_name,
            }
        )
    return devices


def _list_devices_via_filesystem() -> list[dict[str, Any]]:
    devices: list[dict[str, Any]] = []
    for path in sorted(Path("/dev").glob("video*")):
        match = re.search(r"video(\d+)$", path.name)
        if not match:
            continue
        devices.append(
            {
                "path": str(path),
                "index": int(match.group(1)),
                "name": f"Video device {match.group(1)}",
            }
        )
    return devices


def _device_supports_capture(path: str) -> bool:
    result = subprocess.run(
        ["v4l2-ctl", "-d", path, "--list-formats-ext"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return False
    text = f"{result.stdout}\n{result.stderr}".lower()
    # Metadata-only nodes usually have no pixel formats listed.
    return "pixel format" in text or "size:" in text or "width/" in text


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
    devices = list_video_devices() if source == "opencv" else []
    selected_path = resolve_device_path(camera_config) if source == "opencv" else None
    selected_index = resolve_device_index(camera_config) if source == "opencv" else None

    if source != "opencv":
        return {
            "supported": False,
            "reason": "V4L2 controls are only available when camera.source is opencv",
            "device": None,
            "device_index": None,
            "devices": [],
            "controls": [],
            "configured": {},
        }

    device = selected_path
    configured_raw = camera_config.get("v4l2_controls", {})
    configured = (
        {str(key): _coerce_control_value(value) for key, value in configured_raw.items()}
        if isinstance(configured_raw, dict)
        else {}
    )

    base = {
        "device": device,
        "device_index": selected_index,
        "devices": devices,
        "configured": configured,
        "pixel_format": camera_config.get("pixel_format"),
    }

    if not v4l2_available():
        return {
            **base,
            "supported": False,
            "reason": "v4l2-ctl is not installed on the Pi",
            "controls": [],
        }

    try:
        controls = list_controls(device)
    except Exception as exc:
        return {
            **base,
            "supported": False,
            "reason": str(exc),
            "controls": [],
        }

    for control in controls:
        name = control["name"]
        if name in configured:
            control["configured_value"] = configured[name]

    return {
        **base,
        "supported": True,
        "controls": controls,
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
