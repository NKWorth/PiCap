"""Core capture service orchestrating camera, OCR, database, and BLE."""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from picap.ble_api import BleApiServer
from picap.camera import CameraCapture
from picap.config_manager import ConfigManager
from picap.db import Database
from picap.models import CaptureResult, DeviceStatus
from picap.ocr import OcrEngine

logger = logging.getLogger(__name__)


class PiCapService:
    def __init__(self, config_path: str | Path) -> None:
        self.config_manager = ConfigManager(config_path)
        self.database = Database(self.config_manager.get("database", "path", default="data/picap.db"))
        self.camera = CameraCapture(self.config_manager.get("camera", default={}))
        self.ocr = OcrEngine(self.config_manager.get("ocr", default={}))
        self.ble = BleApiServer(
            self.config_manager.get("ble", default={}),
            on_capture=self.capture_and_store,
            read_config=self.get_config,
            write_config=self.update_config,
            read_latest=self.get_latest,
            read_history=self.get_history,
            read_status=self.get_status,
        )
        self._last_capture_at: str | None = None
        self._last_error: str | None = None

    def open(self) -> None:
        self.camera.open()
        logger.info("Camera opened (%s), OCR mode=%s", self.camera.source, self.ocr.mode)

    def close(self) -> None:
        self.camera.close()

    async def run(self) -> None:
        await self.ble.start()
        await self.ble.notify_status(self.get_status())
        try:
            while True:
                await asyncio.sleep(3600)
        finally:
            await self.ble.stop()

    def extract_readings(self, frame: Any) -> list:
        regions = self.config_manager.get_regions() if self.ocr.mode == "regions" else None
        return self.ocr.read_image(frame, regions)

    async def capture_and_store(self) -> dict[str, Any]:
        self._last_error = None
        try:
            frame, image_path = self.camera.capture()
            readings = self.extract_readings(frame)
            result = CaptureResult(
                captured_at=datetime.now(timezone.utc),
                image_path=str(image_path),
                readings=readings,
            )
            row_id = self.database.save_reading(result)
            self._last_capture_at = result.captured_at.isoformat()
            payload = result.to_dict()
            payload["id"] = row_id
            payload["ocr_mode"] = self.ocr.mode
            logger.info("Capture stored with id=%s values=%s", row_id, result.values_dict())
            return payload
        except Exception as exc:
            self._last_error = str(exc)
            raise

    def get_config(self) -> dict[str, Any]:
        return self.config_manager.to_api_dict()

    def update_config(self, payload: dict[str, Any]) -> dict[str, Any]:
        updated = self.config_manager.update_from_api(payload)
        self._apply_runtime_config()
        return updated

    def _apply_runtime_config(self) -> None:
        self.camera.close()
        self.camera = CameraCapture(self.config_manager.get("camera", default={}))
        self.ocr = OcrEngine(self.config_manager.get("ocr", default={}))
        self.camera.open()
        logger.info("Configuration applied, OCR mode=%s", self.ocr.mode)

    def get_latest(self) -> dict[str, Any] | None:
        return self.database.get_latest_reading()

    def get_history(self, limit: int = 20, offset: int = 0) -> list[dict[str, Any]]:
        return self.database.get_readings(limit=limit, offset=offset)

    def get_status(self) -> dict[str, Any]:
        status = DeviceStatus(
            ready=True,
            last_capture_at=self._last_capture_at,
            last_error=self._last_error,
            camera_source=self.camera.source,
            ocr_mode=self.ocr.mode,
        )
        return status.to_dict()
