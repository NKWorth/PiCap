"""BLE GATT API for phone access to PiCap data and configuration."""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, Awaitable, Callable
from uuid import UUID

from bless import (
    BlessGATTCharacteristic,
    BlessServer,
    GATTAttributePermissions,
    GATTCharacteristicProperties,
)

from picap.bluetooth_setup import enable_le_advertising, is_advertisement_error

logger = logging.getLogger(__name__)

CaptureHandler = Callable[[], Awaitable[dict[str, Any]]]
ConfigReader = Callable[[], dict[str, Any]]
ConfigWriter = Callable[[dict[str, Any]], dict[str, Any]]
LatestReader = Callable[[], dict[str, Any] | None]
HistoryReader = Callable[[int, int], list[dict[str, Any]]]
StatusReader = Callable[[], dict[str, Any]]

_WRITABLE = (
    GATTAttributePermissions.writable
    if hasattr(GATTAttributePermissions, "writable")
    else GATTAttributePermissions.writeable
)


def _uuid(value: str) -> str:
    return str(UUID(value))


class BleApiServer:
    BLE_HISTORY_LIMIT = 5

    def __init__(
        self,
        ble_config: dict[str, Any],
        *,
        on_capture: CaptureHandler,
        read_config: ConfigReader,
        write_config: ConfigWriter,
        read_latest: LatestReader,
        read_history: HistoryReader,
        read_status: StatusReader,
    ) -> None:
        self.device_name = ble_config.get("device_name", "PiCap")
        self.service_uuid = _uuid(ble_config["service_uuid"])
        self.char_config_uuid = _uuid(ble_config["char_config_uuid"])
        self.char_capture_uuid = _uuid(ble_config["char_capture_uuid"])
        self.char_latest_uuid = _uuid(ble_config["char_latest_uuid"])
        self.char_history_uuid = _uuid(ble_config["char_history_uuid"])
        self.char_status_uuid = _uuid(ble_config["char_status_uuid"])

        self._on_capture = on_capture
        self._read_config = read_config
        self._write_config = write_config
        self._read_latest = read_latest
        self._read_history = read_history
        self._read_status = read_status

        self._server: BlessServer | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._capture_lock = asyncio.Lock()

    async def start(self) -> None:
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
            await self._server.stop()

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
        return bytearray(json.dumps(payload).encode("utf-8"))

    @staticmethod
    def _compact_config_for_ble(full: dict[str, Any]) -> dict[str, Any]:
        """Return only fields the phone needs; full config exceeds BLE MTU."""
        camera = full.get("camera") if isinstance(full.get("camera"), dict) else {}
        resolution = camera.get("resolution")
        compact: dict[str, Any] = {
            "ocr": full.get("ocr", {}),
            "regions": full.get("regions", []),
        }
        if resolution:
            compact["camera"] = {"resolution": resolution}
        return compact

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
        return compact

    @staticmethod
    def _compact_history_for_ble(history: list[dict[str, Any]]) -> list[dict[str, Any]]:
        return [
            BleApiServer._compact_reading_for_ble(item)
            for item in history
            if isinstance(item, dict)
        ]

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
