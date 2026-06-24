"""YAML configuration loading, validation, and API updates."""

from __future__ import annotations

import copy
from pathlib import Path
from typing import Any

import yaml

from picap.models import Region


class ConfigManager:
    ALLOWED_ROOTS = {"camera", "ocr", "regions", "database", "ble", "http"}

    def __init__(self, config_path: str | Path) -> None:
        self.config_path = Path(config_path)
        self._data: dict[str, Any] = {}
        self.reload()

    def reload(self) -> None:
        if not self.config_path.exists():
            raise FileNotFoundError(f"Config not found: {self.config_path}")
        with self.config_path.open("r", encoding="utf-8") as handle:
            self._data = yaml.safe_load(handle) or {}

    def save(self) -> None:
        self.config_path.parent.mkdir(parents=True, exist_ok=True)
        with self.config_path.open("w", encoding="utf-8") as handle:
            yaml.safe_dump(self._data, handle, sort_keys=False)

    @property
    def data(self) -> dict[str, Any]:
        return copy.deepcopy(self._data)

    def to_api_dict(self) -> dict[str, Any]:
        return self.data

    def update_from_api(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("Config payload must be a JSON object")

        replace = bool(payload.get("replace", False))
        updates = {key: value for key, value in payload.items() if key not in {"replace", "merge"}}

        unknown = set(updates) - self.ALLOWED_ROOTS
        if unknown:
            raise ValueError(f"Unsupported config section(s): {', '.join(sorted(unknown))}")

        if replace:
            for key, value in updates.items():
                self._data[key] = copy.deepcopy(value)
        else:
            for key, value in updates.items():
                if isinstance(value, dict) and isinstance(self._data.get(key), dict):
                    self._data[key] = self._deep_merge(self._data.get(key, {}), value)
                else:
                    self._data[key] = copy.deepcopy(value)

        self._validate()
        self.save()
        return self.to_api_dict()

    def get_ocr_mode(self) -> str:
        return str(self._data.get("ocr", {}).get("mode", "auto"))

    def get_regions(self) -> list[Region]:
        regions = self._data.get("regions", [])
        if not isinstance(regions, list):
            return []
        return [Region.from_dict(item) for item in regions]

    def get(self, *keys: str, default: Any = None) -> Any:
        node: Any = self._data
        for key in keys:
            if not isinstance(node, dict) or key not in node:
                return default
            node = node[key]
        return node

    def _validate(self) -> None:
        ocr = self._data.get("ocr", {})
        mode = ocr.get("mode", "auto")
        if mode not in {"auto", "regions"}:
            raise ValueError("ocr.mode must be 'auto' or 'regions'")

        if mode == "regions":
            regions = self._data.get("regions", [])
            if not isinstance(regions, list) or not regions:
                raise ValueError("regions mode requires at least one configured region")

        regions = self._data.get("regions", [])
        if regions:
            if not isinstance(regions, list):
                raise ValueError("regions must be a list")
            for item in regions:
                region = Region.from_dict(item)
                if region.format not in {"number", "time"}:
                    raise ValueError(f"region {region.name!r} has invalid format {region.format!r}")

        camera = self._data.get("camera", {})
        if camera and camera.get("source") not in {"opencv", "picamera2"}:
            raise ValueError("camera.source must be 'opencv' or 'picamera2'")

    @staticmethod
    def _deep_merge(base: dict[str, Any], patch: dict[str, Any]) -> dict[str, Any]:
        merged = copy.deepcopy(base)
        for key, value in patch.items():
            if isinstance(value, dict) and isinstance(merged.get(key), dict):
                merged[key] = ConfigManager._deep_merge(merged[key], value)
            else:
                merged[key] = copy.deepcopy(value)
        return merged
