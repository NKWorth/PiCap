"""Core capture service orchestrating camera, OCR, database, BLE, and HTTP."""

from __future__ import annotations

import asyncio
import logging
import time
from datetime import date, datetime
from pathlib import Path
from typing import Any

import cv2

from picap.auto_calibrate import AutoCalibrateError
from picap.auto_calibrate import auto_calibrate_regions as detect_otw_regions
from picap.ble_calibration_image import encode_calibration_jpeg
from picap.ble_api import BleApiServer
from picap.camera import CameraCapture
from picap.capture_retention import prune_captures, prune_scheduled_history
from picap.config_manager import ConfigManager
from picap.db import Database
from picap.http_api import HttpApiServer
from picap.models import CaptureResult, DeviceStatus
from picap.network_util import get_lan_ip
from picap.ocr import OcrEngine
from picap.schedule import (
    local_date_for_slot,
    next_capture_at,
    parse_schedule_config,
    resolve_timezone,
    seconds_until,
)
from picap.stored_capture import resolve_stored_image_path
from picap.v4l2_controls import build_controls_response

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
            read_calibration_image=self.get_calibration_jpeg_for_ble,
            read_config=self.get_config,
            write_config=self.update_config,
            read_latest=self.get_latest,
            read_history=self.get_history,
            read_status=self.get_status,
            read_day_report=self.get_day_report,
        )
        self.http = HttpApiServer(
            self.config_manager.get("http", default={}),
            on_capture=self.capture_and_store,
            read_config=self.get_config,
            write_config=self.update_config,
            read_latest=self.get_latest,
            read_history=self.get_history,
            read_status=self.get_status,
            read_preview=self.get_preview_jpeg,
            read_capture_image=self.get_capture_image,
            auto_calibrate_regions=self.auto_calibrate_regions_async,
            read_camera_controls=self.get_camera_controls,
            read_day_report=self.get_day_report,
        )
        self._last_capture_at: str | None = None
        self._last_error: str | None = None
        self._ble_active = False
        self._http_active = False
        self._camera_ready = False
        self._capture_lock = asyncio.Lock()
        self._schedule_wake = asyncio.Event()
        self._next_capture_at: str | None = None
        self._next_slot_at: str | None = None

    def open(self) -> None:
        try:
            self.camera.open()
            self._camera_ready = True
            self._seed_last_good_capture()
            self._prune_old_captures()
            logger.info("Camera opened (%s), OCR mode=%s", self.camera.source, self.ocr.mode)
        except Exception as exc:
            self._camera_ready = False
            self._last_error = str(exc)
            logger.warning("Camera not available at startup: %s", exc)

    def _resolve_stored_image_path(self, image_path_str: str) -> Path | None:
        return resolve_stored_image_path(image_path_str, self.camera.capture_dir)

    def _max_captures(self) -> int:
        return max(0, int(self.config_manager.get("camera", "max_captures", default=10)))

    def _schedule_config(self) -> dict[str, Any]:
        return parse_schedule_config(self.config_manager.get("schedule", default={}))

    def _prune_old_captures(self) -> None:
        prune_captures(
            self.camera.capture_dir,
            self.database,
            max_captures=self._max_captures(),
        )
        try:
            schedule = self._schedule_config()
            prune_scheduled_history(
                self.camera.capture_dir,
                self.database,
                retain_days=int(schedule["retain_days"]),
            )
        except Exception as exc:
            logger.warning("Scheduled retention skipped: %s", exc)

    def _seed_last_good_capture(self) -> None:
        latest = self.database.get_latest_reading()
        if not latest:
            return
        image_path = self._resolve_stored_image_path(str(latest["image_path"]))
        if image_path is None:
            logger.warning("Latest stored capture is missing: %s", latest.get("image_path"))
            return
        if self.camera.try_load_last_good(image_path):
            return
        logger.warning("Latest stored capture is blank or unreadable: %s", image_path)

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
                logger.exception("BLE server failed to start")

        if not self._http_active and not self._ble_active:
            raise RuntimeError("No API transport started; enable http and/or ble in config.yaml")

        schedule_task = asyncio.create_task(self._schedule_loop(), name="picap-schedule")
        try:
            while True:
                await asyncio.sleep(3600)
        finally:
            schedule_task.cancel()
            try:
                await schedule_task
            except asyncio.CancelledError:
                pass
            if self._ble_active:
                await self.ble.stop()
            if self._http_active:
                await self.http.stop()

    async def _schedule_loop(self) -> None:
        logger.info("Capture schedule loop started")
        while True:
            try:
                schedule = self._schedule_config()
                if not schedule["enabled"]:
                    self._next_capture_at = None
                    self._next_slot_at = None
                    await self._wait_or_wake(30.0)
                    continue

                tz = resolve_timezone(schedule["timezone"])
                now = datetime.now(tz)
                capture_at, slot_at = next_capture_at(
                    now,
                    interval_minutes=int(schedule["interval_minutes"]),
                    buffer_seconds=int(schedule["buffer_seconds"]),
                )
                self._next_capture_at = capture_at.isoformat()
                self._next_slot_at = slot_at.isoformat()
                wait_seconds = seconds_until(capture_at, now)
                logger.info(
                    "Next scheduled capture at %s for slot %s (wait %.1fs)",
                    capture_at.isoformat(),
                    slot_at.isoformat(),
                    wait_seconds,
                )
                woke_early = await self._wait_or_wake(wait_seconds)
                if woke_early:
                    continue

                schedule = self._schedule_config()
                if not schedule["enabled"]:
                    continue

                # If we overslept past the next slot, capture for the planned slot
                # only when it is still the imminent one; otherwise reschedule.
                tz = resolve_timezone(schedule["timezone"])
                now = datetime.now(tz)
                late_by = (now - capture_at).total_seconds()
                if late_by > max(30, int(schedule["buffer_seconds"])):
                    logger.warning(
                        "Missed scheduled capture for slot %s (late by %.1fs); rescheduling",
                        slot_at.isoformat(),
                        late_by,
                    )
                    continue

                if self.database.has_scheduled_slot(slot_at):
                    logger.info("Skipping duplicate scheduled slot %s", slot_at.isoformat())
                    await asyncio.sleep(1.0)
                    continue

                await self.capture_and_store(
                    source="scheduled",
                    slot_at=slot_at,
                    local_date=local_date_for_slot(slot_at),
                )
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self._last_error = str(exc)
                logger.exception("Scheduled capture failed")
                await asyncio.sleep(5.0)

    async def _wait_or_wake(self, seconds: float) -> bool:
        """Sleep up to `seconds`, or return True if schedule config changed."""
        self._schedule_wake.clear()
        try:
            await asyncio.wait_for(self._schedule_wake.wait(), timeout=max(0.05, seconds))
            return True
        except asyncio.TimeoutError:
            return False

    def extract_readings(self, frame: Any) -> list:
        regions = None
        if self.ocr.mode == "regions":
            height, width = frame.shape[:2]
            regions = self.config_manager.get_regions_for_image(width, height)
        return self.ocr.read_image(frame, regions)

    async def capture_and_store(
        self,
        *,
        source: str = "manual",
        slot_at: datetime | None = None,
        local_date: date | None = None,
    ) -> dict[str, Any]:
        async with self._capture_lock:
            return await asyncio.to_thread(
                self._capture_and_store_sync,
                source,
                slot_at,
                local_date,
            )

    def _capture_and_store_sync(
        self,
        source: str,
        slot_at: datetime | None,
        local_date: date | None,
    ) -> dict[str, Any]:
        self._last_error = None
        try:
            prefix = "capture"
            allow_fallback = True
            force_fresh = False
            if source == "scheduled" and slot_at is not None:
                prefix = f"scheduled_{slot_at.strftime('%Y%m%d_%H%M%S')}"
                # Never persist OCR from a previous image for interval reports.
                allow_fallback = False
                force_fresh = True

            output = None
            last_error: Exception | None = None
            attempts = 3 if source == "scheduled" else 1
            for attempt in range(attempts):
                try:
                    output = self.camera.capture(
                        filename_prefix=prefix,
                        allow_last_good_fallback=allow_fallback,
                        force_fresh=force_fresh or attempt > 0,
                    )
                    break
                except Exception as exc:
                    last_error = exc
                    logger.warning(
                        "Capture attempt %s/%s failed (%s): %s",
                        attempt + 1,
                        attempts,
                        source,
                        exc,
                    )
                    if attempt + 1 < attempts:
                        time.sleep(0.6)
            if output is None:
                assert last_error is not None
                raise last_error

            if output.reused_last_good and source == "scheduled":
                raise RuntimeError(
                    "Scheduled capture reused an old frame; refusing to store stale times"
                )

            frame = output.frame
            readings = self.extract_readings(frame)
            captured_at = datetime.now().astimezone()
            result = CaptureResult(
                captured_at=captured_at,
                image_path=str(output.image_path),
                readings=readings,
                source=source,
                slot_at=slot_at,
                local_date=local_date or (local_date_for_slot(slot_at) if slot_at else None),
            )
            row_id = self.database.save_reading(result)
            self._last_capture_at = result.captured_at.isoformat()
            self._camera_ready = True
            payload = result.to_dict()
            payload["id"] = row_id
            payload["ocr_mode"] = self.ocr.mode
            payload["image_width"] = int(frame.shape[1])
            payload["image_height"] = int(frame.shape[0])
            payload["image_reused"] = output.reused_last_good
            if output.reused_last_good:
                payload["camera_warning"] = (
                    "Camera returned a blank frame; used the last good capture."
                )
                self._last_error = payload["camera_warning"]
                logger.warning(payload["camera_warning"])
            logger.info(
                "Capture stored id=%s source=%s values=%s",
                row_id,
                source,
                result.values_dict(),
            )
            self._prune_old_captures()
            return payload
        except Exception as exc:
            self._last_error = str(exc)
            raise

    def get_config(self) -> dict[str, Any]:
        return self.config_manager.to_api_dict()

    def get_camera_controls(self) -> dict[str, Any]:
        return build_controls_response(self.config_manager.get("camera", default={}))

    def update_config(self, payload: dict[str, Any]) -> dict[str, Any]:
        updated = self.config_manager.update_from_api(payload)
        self._apply_runtime_config(payload)
        return updated

    def _apply_runtime_config(self, payload: dict[str, Any] | None = None) -> None:
        keys = set(payload or {}) - {"replace", "merge"}
        schedule_only = keys and keys <= {"schedule"}
        if "schedule" in keys:
            self._schedule_wake.set()
        if schedule_only:
            logger.info("Schedule configuration updated")
            return

        self.camera.close()
        self.camera = CameraCapture(self.config_manager.get("camera", default={}))
        self.ocr = OcrEngine(self.config_manager.get("ocr", default={}))
        try:
            self.camera.open()
            self._camera_ready = True
            self._seed_last_good_capture()
            self._prune_old_captures()
        except Exception as exc:
            self._camera_ready = False
            self._last_error = str(exc)
            logger.warning("Camera not available after config update: %s", exc)
        logger.info("Configuration applied, OCR mode=%s", self.ocr.mode)

    def get_latest(self) -> dict[str, Any] | None:
        return self.database.get_latest_reading()

    def get_history(self, limit: int = 20, offset: int = 0) -> list[dict[str, Any]]:
        return self.database.get_readings(limit=limit, offset=offset)

    def get_day_report(self, local_date: date | None = None) -> dict[str, Any]:
        target = local_date or date.today()
        return self.database.get_day_report(target)

    def get_preview_jpeg(self, max_width: int = 640, quality: int = 75) -> bytes:
        try:
            data = self.camera.preview_jpeg(max_width=max_width, quality=quality)
            self._camera_ready = True
            self._last_error = None
            return data
        except Exception as exc:
            self._camera_ready = False
            self._last_error = str(exc)
            raise

    def get_capture_image(self, filename: str) -> bytes | None:
        if "/" in filename or "\\" in filename or ".." in filename:
            return None
        image_path = (self.camera.capture_dir / filename).resolve()
        capture_root = self.camera.capture_dir.resolve()
        if image_path.parent != capture_root or not image_path.is_file():
            return None
        return image_path.read_bytes()

    async def get_calibration_jpeg_for_ble(self, source: str) -> tuple[bytes, int, int]:
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(None, lambda: self._get_calibration_jpeg_sync(source))

    def _get_calibration_jpeg_sync(self, source: str) -> tuple[bytes, int, int]:
        ble_cfg = self.config_manager.get("ble", default={})
        max_width = int(ble_cfg.get("calibration_max_width", 800))
        quality = int(ble_cfg.get("calibration_jpeg_quality", 75))
        normalized = (source or "fetch").strip().lower()

        if normalized == "capture":
            output = self.camera.capture()
            frame = output.frame
        else:
            frame = self._load_latest_calibration_frame()

        return encode_calibration_jpeg(frame, max_width=max_width, quality=quality)

    def _load_latest_calibration_frame(self) -> Any:
        latest = self.database.get_latest_reading()
        if latest:
            image_path = self._resolve_stored_image_path(str(latest["image_path"]))
            if image_path is not None:
                frame = cv2.imread(str(image_path))
                if frame is not None:
                    return frame
            raise RuntimeError(
                "Could not load the latest capture image. "
                f"Stored path: {latest.get('image_path')}"
            )

        raise RuntimeError("No stored capture available. Take a capture first.")

    async def auto_calibrate_regions_async(self, source: str = "latest") -> dict[str, Any]:
        frame, image_path = self._load_calibration_frame(source)
        loop = asyncio.get_running_loop()
        try:
            result = await loop.run_in_executor(None, lambda: detect_otw_regions(frame))
        except AutoCalibrateError:
            raise
        except Exception as exc:
            raise AutoCalibrateError(str(exc)) from exc
        payload = result.to_dict()
        payload["image_path"] = str(image_path)
        return payload

    def auto_calibrate_regions(self, source: str = "latest") -> dict[str, Any]:
        """Synchronous entry point for CLI/scripts."""
        frame, image_path = self._load_calibration_frame(source)
        try:
            result = detect_otw_regions(frame)
        except AutoCalibrateError:
            raise
        except Exception as exc:
            raise AutoCalibrateError(str(exc)) from exc
        payload = result.to_dict()
        payload["image_path"] = str(image_path)
        return payload

    def _load_calibration_frame(self, source: str) -> tuple[Any, Path]:
        normalized = (source or "latest").strip().lower()
        if normalized == "capture":
            output = self.camera.capture()
            return output.frame, output.image_path.resolve()

        latest = self.database.get_latest_reading()
        if latest:
            image_path = self._resolve_stored_image_path(str(latest["image_path"]))
            if image_path is not None:
                frame = cv2.imread(str(image_path))
                if frame is not None:
                    return frame, image_path
            raise AutoCalibrateError(
                "Could not load the latest capture image. "
                f"Stored path: {latest.get('image_path')}"
            )

        raise AutoCalibrateError("No stored capture available. Take a capture first.")

    def get_status(self) -> dict[str, Any]:
        http_cfg = self.config_manager.get("http", default={})
        http_port = int(http_cfg.get("port", 8080))
        http_url: str | None = None
        http_host: str | None = None
        if self._http_active:
            lan_ip = get_lan_ip()
            if lan_ip:
                http_host = f"{lan_ip}:{http_port}"
                http_url = f"http://{http_host}"

        schedule = {}
        try:
            schedule = self._schedule_config()
        except Exception:
            schedule = {"enabled": False}

        status = DeviceStatus(
            ready=True,
            last_capture_at=self._last_capture_at,
            last_error=self._last_error,
            camera_source=self.camera.source,
            ocr_mode=self.ocr.mode,
            ble_active=self._ble_active,
            http_active=self._http_active,
            http_port=http_port if self._http_active else None,
            http_url=http_url,
            http_host=http_host,
            camera_ready=self._camera_ready,
            schedule_enabled=bool(schedule.get("enabled", False)),
            next_capture_at=self._next_capture_at,
            next_slot_at=self._next_slot_at,
        )
        return status.to_dict()
