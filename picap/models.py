"""Data models for PiCap."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime
from typing import Any


@dataclass
class Region:
    name: str
    x: int
    y: int
    width: int
    height: int
    # "number" (default) or "time" for MM:SS values such as 05:30
    format: str = "number"

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> Region:
        region_format = str(data.get("format", "number"))
        if region_format not in {"number", "time"}:
            raise ValueError(f"region format must be 'number' or 'time', got {region_format!r}")
        return cls(
            name=str(data["name"]),
            x=int(data["x"]),
            y=int(data["y"]),
            width=int(data["width"]),
            height=int(data["height"]),
            format=region_format,
        )


@dataclass
class RegionReading:
    name: str
    value: str | None
    confidence: float
    x: int | None = None
    y: int | None = None
    width: int | None = None
    height: int | None = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class CaptureResult:
    captured_at: datetime
    image_path: str
    readings: list[RegionReading] = field(default_factory=list)

    def values_dict(self) -> dict[str, str | None]:
        return {r.name: r.value for r in self.readings}

    def to_dict(self) -> dict[str, Any]:
        return {
            "captured_at": self.captured_at.isoformat(),
            "image_path": self.image_path,
            "readings": [r.to_dict() for r in self.readings],
            "values": self.values_dict(),
        }


@dataclass
class DeviceStatus:
    ready: bool
    last_capture_at: str | None
    last_error: str | None
    camera_source: str
    ocr_mode: str
    ble_active: bool = False
    http_active: bool = False
    http_port: int | None = None
    http_url: str | None = None
    http_host: str | None = None
    camera_ready: bool = False

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)
