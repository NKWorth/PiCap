"""Core capture service orchestrating camera, OCR, database, BLE, and HTTP."""

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
from picap.http_api import HttpApiServer
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
        self.http = HttpApiServer(
            self.config_manager.get("http", default={}),
            on_capture=self.capture_and_store,
            read_config=self.get_config,
            write_config=self.update_config,
            read_latest=self.get_latest,
            read_history=self.get_history,
            read_status=self.get_status,
        )
        self._last_capture_at: str | None = None
        self._last_error: str | None = None
        self._ble_active = False
        self._http_active = False
        self._camera_ready = False

    def open(self) -> None:
        try:
            self.camera.open()
            self._camera_ready = True
            logger.info("Camera opened (%s), OCR mode=%s", self.camera.source, self.ocr.mode)
        except Exception as exc:
            self._camera_ready = False
            self._last_error = str(exc)
            logger.warning("Camera not available at startup: %s", exc)

    def close(self) -> None:
        self.camera.close()

    async def run(
        self,
        *,
        ble_enabled: bool | None = None,
        http_enabled: bool | None = None,
    ) -> None:
        http_on = (
            http_enabled
            if http_enabled is not None
            else bool(self.config_manager.get("http", "enabled", default=True))
        )
        ble_on = (
            ble_enabled
            if ble_enabled is not None
            else bool(self.config_manager.get("ble", "enabled", default=True))
        )

        if http_on:
            await self.http.start()
            self._http_active = True

        if ble_on:
            try:
                await self.ble.start()
                self._ble_active = True
                await self.ble.notify_status(self.get_status())
            except Exception as exc:
                self._ble_active = False
                self._last_error = str(exc)
                logger.exception("BLE server failed to start; HTTP API remains available")

        if not self._http_active and not self._ble_active:
            raise RuntimeError("No API transport started; enable http and/or ble in config.yaml")

        try:
            while True:
                await asyncio.sleep(3600)
        finally:
            if self._ble_active:
                await self.ble.stop()
            if self._http_active:
                await self.http.stop()

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
            self._camera_ready = True
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
        try:
            self.camera.open()
            self._camera_ready = True
        except Exception as exc:
            self._camera_ready = False
            self._last_error = str(exc)
            logger.warning("Camera not available after config update: %s", exc)
        logger.info("Configuration applied, OCR mode=%s", self.ocr.mode)

    def get_latest(self) -> dict[str, Any] | None:
        return self.database.get_latest_reading()

    def get_history(self, limit: int = 20, offset: int = 0) -> list[dict[str, Any]]:
        return self.database.get_readings(limit=limit, offset=offset)

    def get_status(self) -> dict[str, Any]:
        http_cfg = self.config_manager.get("http", default={})
        status = DeviceStatus(
            ready=True,
            last_capture_at=self._last_capture_at,
            last_error=self._last_error,
            camera_source=self.camera.source,
            ocr_mode=self.ocr.mode,
            ble_active=self._ble_active,
            http_active=self._http_active,
            http_port=int(http_cfg.get("port", 8080)) if self._http_active else None,
            camera_ready=self._camera_ready,
        )
        return status.to_dict()
