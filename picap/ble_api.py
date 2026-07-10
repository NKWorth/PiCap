"""BLE GATT API for phone access to PiCap data and configuration."""

from __future__ import annotations

import asyncio
import json
import logging
from datetime import date, datetime
from typing import Any, Awaitable, Callable
from uuid import UUID

from bless import (
    BlessGATTCharacteristic,
    BlessServer,
    GATTAttributePermissions,
    GATTCharacteristicProperties,
)

from picap.bluetooth_setup import enable_le_advertising, is_advertisement_error
from picap.ble_calibration_image import BLE_CHUNK_SIZE

logger = logging.getLogger(__name__)

CaptureHandler = Callable[[], Awaitable[dict[str, Any]]]
CalibrationImageHandler = Callable[[str], Awaitable[tuple[bytes, int, int, int, int]]]
ConfigReader = Callable[[], dict[str, Any]]
ConfigWriter = Callable[[dict[str, Any]], dict[str, Any]]
LatestReader = Callable[[], dict[str, Any] | None]
HistoryReader = Callable[[int, int], list[dict[str, Any]]]
StatusReader = Callable[[], dict[str, Any]]
DayReportReader = Callable[[date | None], dict[str, Any]]

_WRITABLE = (
    GATTAttributePermissions.writable
    if hasattr(GATTAttributePermissions, "writable")
    else GATTAttributePermissions.writeable
)


def _uuid(value: str) -> str:
    return str(UUID(value))


class BleApiServer:
    BLE_HISTORY_LIMIT = 5
    BLE_DAY_REPORT_PAGE_SIZE = 12

    def __init__(
        self,
        ble_config: dict[str, Any],
        *,
        on_capture: CaptureHandler,
        read_calibration_image: CalibrationImageHandler,
        read_config: ConfigReader,
        write_config: ConfigWriter,
        read_latest: LatestReader,
        read_history: HistoryReader,
        read_status: StatusReader,
        read_day_report: DayReportReader,
    ) -> None:
        self.device_name = ble_config.get("device_name", "PiCap")
        self.service_uuid = _uuid(ble_config["service_uuid"])
        self.char_config_uuid = _uuid(ble_config["char_config_uuid"])
        self.char_capture_uuid = _uuid(ble_config["char_capture_uuid"])
        self.char_latest_uuid = _uuid(ble_config["char_latest_uuid"])
        self.char_history_uuid = _uuid(ble_config["char_history_uuid"])
        self.char_status_uuid = _uuid(ble_config["char_status_uuid"])
        self.char_calibration_image_uuid = _uuid(
            ble_config.get("char_calibration_image_uuid", "a1b2c3d4-e5f6-7890-abcd-ef1234567896")
        )
        self.char_day_report_uuid = _uuid(
            ble_config.get("char_day_report_uuid", "a1b2c3d4-e5f6-7890-abcd-ef1234567897")
        )

        self._on_capture = on_capture
        self._read_calibration_image = read_calibration_image
        self._read_config = read_config
        self._write_config = write_config
        self._read_latest = read_latest
        self._read_history = read_history
        self._read_status = read_status
        self._read_day_report = read_day_report

        self._server: BlessServer | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._capture_lock = asyncio.Lock()
        self._calibration_lock = asyncio.Lock()
        self._calibration_cancel = False

    async def start(self) -> None:
        if self._server is not None:
            await self.stop()

        self._loop = asyncio.get_running_loop()
        self._server = BlessServer(name=self.device_name, loop=self._loop)
        self._server.read_request_func = self._on_read
        self._server.write_request_func = self._on_write

        await self._server.add_new_service(self.service_uuid)
        await self._add_characteristic(
            self.char_config_uuid,
            GATTCharacteristicProperties.read | GATTCharacteristicProperties.write,
            GATTAttributePermissions.readable | _WRITABLE,
            self._encode_json(self._compact_config_for_ble(self._read_config())),
        )
        await self._add_characteristic(
            self.char_capture_uuid,
            GATTCharacteristicProperties.read
            | GATTCharacteristicProperties.write
            | GATTCharacteristicProperties.notify,
            GATTAttributePermissions.readable | _WRITABLE,
            self._encode_json({"status": "idle"}),
        )
        await self._add_characteristic(
            self.char_latest_uuid,
            GATTCharacteristicProperties.read | GATTCharacteristicProperties.notify,
            GATTAttributePermissions.readable,
            self._encode_json(self._compact_reading_for_ble(self._read_latest() or {})),
        )
        await self._add_characteristic(
            self.char_history_uuid,
            GATTCharacteristicProperties.read | GATTCharacteristicProperties.write,
            GATTAttributePermissions.readable | _WRITABLE,
            self._encode_json([]),
        )
        await self._add_characteristic(
            self.char_status_uuid,
            GATTCharacteristicProperties.read | GATTCharacteristicProperties.notify,
            GATTAttributePermissions.readable,
            self._encode_json(self._read_status()),
        )
        await self._add_characteristic(
            self.char_calibration_image_uuid,
            GATTCharacteristicProperties.read
            | GATTCharacteristicProperties.write
            | GATTCharacteristicProperties.notify,
            GATTAttributePermissions.readable | _WRITABLE,
            self._encode_json({"status": "idle"}),
        )
        await self._add_characteristic(
            self.char_day_report_uuid,
            GATTCharacteristicProperties.read | GATTCharacteristicProperties.write,
            GATTAttributePermissions.readable | _WRITABLE,
            self._encode_json({"date": None, "slot_count": 0, "slots": []}),
        )

        try:
            await self._server.start()
        except Exception as exc:
            if not is_advertisement_error(exc):
                raise
            # bless registers GATT before dbus advertisement; adapter-level
            # advertising is enough for phones to discover PiCap by name.
            enable_le_advertising(self.device_name)
            logger.warning(
                "BLE dbus advertisement unavailable; using adapter advertising: %s",
                exc,
            )

        logger.info("BLE GATT server started as %s", self.device_name)

    async def stop(self) -> None:
        if self._server is not None:
            try:
                await self._server.stop()
            except Exception:
                logger.debug("BLE server stop failed", exc_info=True)
            self._server = None
            self._loop = None

    async def notify_latest(self, payload: dict[str, Any]) -> None:
        await self._update_characteristic(
            self.char_latest_uuid,
            self._encode_json(self._compact_reading_for_ble(payload)),
        )

    async def notify_status(self, payload: dict[str, Any]) -> None:
        await self._update_characteristic(self.char_status_uuid, self._encode_json(payload))

    async def _add_characteristic(
        self,
        char_uuid: str,
        properties: GATTCharacteristicProperties,
        permissions: GATTAttributePermissions,
        value: bytearray,
    ) -> None:
        if self._server is None:
            raise RuntimeError("BLE server is not initialized")
        await self._server.add_new_characteristic(
            self.service_uuid,
            char_uuid,
            properties,
            value,
            permissions,
        )

    def _on_read(self, characteristic: BlessGATTCharacteristic, **_kwargs: Any) -> bytearray:
        char_uuid = _uuid(characteristic.uuid)
        if char_uuid == self.char_config_uuid:
            return self._encode_json(self._compact_config_for_ble(self._read_config()))
        if char_uuid == self.char_latest_uuid:
            return self._encode_json(self._compact_reading_for_ble(self._read_latest() or {}))
        if char_uuid == self.char_status_uuid:
            return self._encode_json(self._read_status())
        return characteristic.value or bytearray()

    def _on_write(self, characteristic: BlessGATTCharacteristic, value: Any, **_kwargs: Any) -> None:
        characteristic.value = value
        if self._loop is None:
            return

        char_uuid = _uuid(characteristic.uuid)
        if char_uuid == self.char_config_uuid:
            asyncio.run_coroutine_threadsafe(self._handle_config_write(value), self._loop)
        elif char_uuid == self.char_capture_uuid:
            asyncio.run_coroutine_threadsafe(self._handle_capture_write(value), self._loop)
        elif char_uuid == self.char_history_uuid:
            asyncio.run_coroutine_threadsafe(self._handle_history_write(value), self._loop)
        elif char_uuid == self.char_day_report_uuid:
            asyncio.run_coroutine_threadsafe(self._handle_day_report_write(value), self._loop)
        elif char_uuid == self.char_calibration_image_uuid:
            asyncio.run_coroutine_threadsafe(self._handle_calibration_image_write(value), self._loop)

    async def _handle_config_write(self, value: bytearray) -> None:
        try:
            payload = json.loads(value.decode("utf-8"))
            if not isinstance(payload, dict):
                raise ValueError("Config payload must be a JSON object")
            updated = self._write_config(payload)
            encoded = self._encode_json(self._compact_config_for_ble(updated))
            await self._update_characteristic(self.char_config_uuid, encoded)
            await self.notify_status(self._read_status())
        except Exception as exc:
            encoded = self._encode_json({"error": str(exc)})
            await self._update_characteristic(self.char_config_uuid, encoded)

    async def _handle_capture_write(self, value: bytearray) -> None:
        command = value.decode("utf-8").strip().lower()
        is_capture = command == "capture"
        if not is_capture:
            try:
                payload = json.loads(command)
                is_capture = payload.get("action") == "capture"
            except json.JSONDecodeError:
                is_capture = False

        if not is_capture:
            await self._update_characteristic(
                self.char_capture_uuid,
                self._encode_json({"status": "error", "message": "Unknown command"}),
            )
            return

        async with self._capture_lock:
            await self._update_characteristic(
                self.char_capture_uuid,
                self._encode_json(self._compact_capture_state_for_ble({"status": "capturing"})),
            )
            try:
                result = await self._on_capture()
                await self._update_characteristic(
                    self.char_capture_uuid,
                    self._encode_json(
                        self._compact_capture_state_for_ble(
                            {"status": "complete", "result": result},
                        ),
                    ),
                )
                await self.notify_latest(result)
                await self.notify_status(self._read_status())
            except Exception as exc:
                logger.exception("Capture failed")
                await self._update_characteristic(
                    self.char_capture_uuid,
                    self._encode_json(
                        self._compact_capture_state_for_ble(
                            {"status": "error", "message": str(exc)},
                        ),
                    ),
                )
                await self.notify_status(self._read_status())

    async def _handle_history_write(self, value: bytearray) -> None:
        try:
            payload = json.loads(value.decode("utf-8"))
            limit = min(int(payload.get("limit", 20)), self.BLE_HISTORY_LIMIT)
            offset = int(payload.get("offset", 0))
            history = self._read_history(limit=limit, offset=offset)
            encoded = self._encode_json(self._compact_history_for_ble(history))
        except Exception as exc:
            encoded = self._encode_json({"error": str(exc)})
        await self._update_characteristic(self.char_history_uuid, encoded)

    async def _handle_day_report_write(self, value: bytearray) -> None:
        try:
            payload = json.loads(value.decode("utf-8")) if value else {}
            if not isinstance(payload, dict):
                raise ValueError("Day report request must be a JSON object")
            raw_date = payload.get("date")
            target: date | None = None
            if raw_date:
                target = date.fromisoformat(str(raw_date))
            offset = max(0, int(payload.get("offset", 0)))
            limit = min(
                max(1, int(payload.get("limit", self.BLE_DAY_REPORT_PAGE_SIZE))),
                self.BLE_DAY_REPORT_PAGE_SIZE,
            )
            report = self._read_day_report(target)
            encoded = self._encode_json(
                self._compact_day_report_for_ble(report, offset=offset, limit=limit)
            )
        except Exception as exc:
            encoded = self._encode_json({"error": str(exc)})
        await self._update_characteristic(self.char_day_report_uuid, encoded)

    async def _handle_calibration_image_write(self, value: bytearray) -> None:
        action = self._parse_calibration_action(value)
        if action == "cancel":
            self._calibration_cancel = True
            await self._notify_calibration_json({"status": "cancelled"})
            return

        if action not in {"fetch", "capture"}:
            await self._notify_calibration_json(
                {"status": "error", "message": "Unknown calibration image action"},
            )
            return

        async with self._calibration_lock:
            self._calibration_cancel = False
            await self._notify_calibration_json({"status": "loading", "action": action})
            try:
                jpeg, width, height, source_width, source_height = await self._read_calibration_image(
                    action
                )
                await self._stream_calibration_image(
                    jpeg,
                    width,
                    height,
                    source_width=source_width,
                    source_height=source_height,
                    action=action,
                )
            except Exception as exc:
                logger.exception("BLE calibration image failed")
                await self._notify_calibration_json({"status": "error", "message": str(exc)})

    async def _stream_calibration_image(
        self,
        jpeg: bytes,
        width: int,
        height: int,
        *,
        source_width: int,
        source_height: int,
        action: str,
    ) -> None:
        chunk_size = BLE_CHUNK_SIZE
        total_chunks = (len(jpeg) + chunk_size - 1) // chunk_size
        await self._notify_calibration_json(
            {
                "status": "transferring",
                "action": action,
                "image_width": width,
                "image_height": height,
                "source_width": source_width,
                "source_height": source_height,
                "byte_size": len(jpeg),
                "chunk_size": chunk_size,
                "total_chunks": total_chunks,
            },
        )
        for offset in range(0, len(jpeg), chunk_size):
            if self._calibration_cancel:
                await self._notify_calibration_json({"status": "cancelled"})
                return
            await self._notify_calibration_bytes(jpeg[offset : offset + chunk_size])
            await asyncio.sleep(0.025)
        await self._notify_calibration_json({"status": "complete"})

    async def _notify_calibration_json(self, payload: dict[str, Any]) -> None:
        await self._update_characteristic(
            self.char_calibration_image_uuid,
            self._encode_json(payload),
        )

    async def _notify_calibration_bytes(self, payload: bytes) -> None:
        await self._update_characteristic(
            self.char_calibration_image_uuid,
            bytearray(payload),
        )

    @staticmethod
    def _parse_calibration_action(value: bytearray) -> str:
        text = value.decode("utf-8").strip().lower()
        if text in {"fetch", "capture", "cancel"}:
            return text
        try:
            payload = json.loads(text)
            if isinstance(payload, dict):
                return str(payload.get("action", "")).strip().lower()
        except json.JSONDecodeError:
            pass
        return ""

    async def _update_characteristic(self, char_uuid: str, value: bytearray) -> None:
        if self._server is None:
            return
        characteristic = self._server.get_characteristic(char_uuid)
        if characteristic is None:
            logger.warning("Characteristic not found for update: %s", char_uuid)
            return
        characteristic.value = value
        self._server.update_value(self.service_uuid, char_uuid)

    @staticmethod
    def _encode_json(payload: Any) -> bytearray:
        return bytearray(json.dumps(payload, separators=(",", ":")).encode("utf-8"))

    @staticmethod
    def _compact_config_for_ble(full: dict[str, Any]) -> dict[str, Any]:
        """Return only fields the phone needs; full config exceeds BLE MTU."""
        camera = full.get("camera") if isinstance(full.get("camera"), dict) else {}
        resolution = camera.get("resolution")
        ocr = full.get("ocr") if isinstance(full.get("ocr"), dict) else {}
        compact_ocr = {
            key: ocr[key]
            for key in (
                "mode",
                "min_confidence",
                "min_digits",
                "upscale_factor",
                "sharpen",
                "contrast",
                "threshold",
                "auto_psm",
            )
            if key in ocr
        }
        compact: dict[str, Any] = {
            "ocr": compact_ocr,
            "regions": full.get("regions", []),
        }
        if full.get("regions_ref"):
            compact["regions_ref"] = full.get("regions_ref")

        schedule = full.get("schedule") if isinstance(full.get("schedule"), dict) else {}
        if schedule:
            compact["schedule"] = {
                key: schedule[key]
                for key in (
                    "enabled",
                    "interval_minutes",
                    "buffer_seconds",
                    "timezone",
                    "retain_days",
                )
                if key in schedule
            }

        camera_compact: dict[str, Any] = {}
        if resolution:
            camera_compact["resolution"] = resolution
        if camera.get("source"):
            camera_compact["source"] = camera.get("source")
        if camera.get("pixel_format"):
            camera_compact["pixel_format"] = camera.get("pixel_format")
        if camera.get("v4l2_device"):
            camera_compact["v4l2_device"] = camera.get("v4l2_device")
        if camera.get("device_index") is not None:
            camera_compact["device_index"] = int(camera.get("device_index"))
        camera_controls = camera.get("v4l2_controls")
        if isinstance(camera_controls, dict) and camera_controls:
            # Keep values only; names/ranges come from HTTP /api/camera/controls when linked.
            camera_compact["v4l2_controls"] = {
                str(key): int(value) for key, value in camera_controls.items()
            }
        if camera_compact:
            compact["camera"] = camera_compact

        encoded = BleApiServer._encode_json(compact)
        # ATT payload limit is roughly MTU-3; keep under common 514-byte ceiling.
        if len(encoded) <= 500:
            return compact

        # Drop camera controls first if still too large; Camera tab can reload over WiFi.
        if "camera" in compact and "v4l2_controls" in compact["camera"]:
            camera_without = dict(compact["camera"])
            camera_without.pop("v4l2_controls", None)
            compact["camera"] = camera_without
            encoded = BleApiServer._encode_json(compact)
            if len(encoded) <= 500:
                return compact

        # Last resort: regions + OCR mode + schedule (schedule is tiny and must persist in the app).
        minimal: dict[str, Any] = {
            "ocr": {"mode": compact_ocr.get("mode", "auto")},
            "regions": compact.get("regions", []),
        }
        if compact.get("regions_ref"):
            minimal["regions_ref"] = compact["regions_ref"]
        if compact.get("schedule"):
            minimal["schedule"] = compact["schedule"]
        camera_min = {
            key: value
            for key, value in (compact.get("camera") or {}).items()
            if key in {"resolution", "source"}
        }
        if camera_min:
            minimal["camera"] = camera_min
        return minimal

    @staticmethod
    def _compact_reading_for_ble(reading: dict[str, Any]) -> dict[str, Any]:
        """Trim capture payloads so reads/notifications fit in one BLE packet."""
        if not reading:
            return {}
        image_path = str(reading.get("image_path", ""))
        basename = image_path.replace("\\", "/").rsplit("/", 1)[-1] if image_path else ""
        compact: dict[str, Any] = {
            "captured_at": reading.get("captured_at"),
            "image_path": basename or image_path,
            "values": reading.get("values", {}),
        }
        reading_id = reading.get("id")
        if reading_id is not None:
            compact["id"] = reading_id
        if reading.get("image_reused"):
            compact["image_reused"] = True
        warning = reading.get("camera_warning")
        if warning:
            compact["camera_warning"] = str(warning)
        return compact

    @staticmethod
    def _compact_history_for_ble(history: list[dict[str, Any]]) -> list[dict[str, Any]]:
        return [
            BleApiServer._compact_reading_for_ble(item)
            for item in history
            if isinstance(item, dict)
        ]

    @staticmethod
    def _compact_day_report_for_ble(
        report: dict[str, Any],
        *,
        offset: int,
        limit: int,
    ) -> dict[str, Any]:
        """Compact paged day report so each BLE response fits under typical MTU."""
        slots = report.get("slots") if isinstance(report.get("slots"), list) else []
        page = slots[offset : offset + limit]
        compact_slots: list[list[Any]] = []
        for item in page:
            if not isinstance(item, dict):
                continue
            values = item.get("values") if isinstance(item.get("values"), dict) else {}
            compact_slots.append(
                [
                    BleApiServer._slot_hhmm(item.get("slot_at") or item.get("captured_at")),
                    values.get("order_point_15min_avg"),
                    values.get("current_otw_15min_avg"),
                ]
            )
        total = int(report.get("slot_count", len(slots)))
        return {
            "date": report.get("date"),
            "slot_count": total,
            "offset": offset,
            "limit": limit,
            "has_more": (offset + limit) < len(slots),
            "slots": compact_slots,
        }

    @staticmethod
    def _slot_hhmm(raw: Any) -> str:
        text = str(raw or "").strip()
        if not text:
            return ""
        try:
            parsed = datetime.fromisoformat(text)
            return parsed.strftime("%H:%M")
        except ValueError:
            pass
        if "T" in text:
            time_part = text.split("T", 1)[1]
            return time_part[:5]
        return text[:5]

    @staticmethod
    def _compact_capture_state_for_ble(state: dict[str, Any]) -> dict[str, Any]:
        compact: dict[str, Any] = {"status": state.get("status", "idle")}
        message = state.get("message")
        if message:
            compact["message"] = message
        result = state.get("result")
        if isinstance(result, dict):
            compact["result"] = BleApiServer._compact_reading_for_ble(result)
        return compact
