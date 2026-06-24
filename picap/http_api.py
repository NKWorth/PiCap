"""HTTP REST API for PiCap (WiFi fallback when BLE is unavailable)."""

from __future__ import annotations

import json
import logging
from typing import Any, Awaitable, Callable

from aiohttp import web

logger = logging.getLogger(__name__)

CaptureHandler = Callable[[], Awaitable[dict[str, Any]]]
ConfigReader = Callable[[], dict[str, Any]]
ConfigWriter = Callable[[dict[str, Any]], dict[str, Any]]
LatestReader = Callable[[], dict[str, Any] | None]
HistoryReader = Callable[[int, int], list[dict[str, Any]]]
StatusReader = Callable[[], dict[str, Any]]


class HttpApiServer:
    def __init__(
        self,
        http_config: dict[str, Any],
        *,
        on_capture: CaptureHandler,
        read_config: ConfigReader,
        write_config: ConfigWriter,
        read_latest: LatestReader,
        read_history: HistoryReader,
        read_status: StatusReader,
    ) -> None:
        self.host = http_config.get("host", "0.0.0.0")
        self.port = int(http_config.get("port", 8080))
        self.api_key = http_config.get("api_key")
        self._on_capture = on_capture
        self._read_config = read_config
        self._write_config = write_config
        self._read_latest = read_latest
        self._read_history = read_history
        self._read_status = read_status
        self._runner: web.AppRunner | None = None

    async def start(self) -> None:
        app = web.Application(middlewares=[self._auth_middleware])
        app.router.add_get("/api/status", self._handle_status)
        app.router.add_get("/api/config", self._handle_config_get)
        app.router.add_route("PATCH", "/api/config", self._handle_config_patch)
        app.router.add_put("/api/config", self._handle_config_patch)
        app.router.add_get("/api/latest", self._handle_latest)
        app.router.add_get("/api/history", self._handle_history)
        app.router.add_post("/api/capture", self._handle_capture)

        self._runner = web.AppRunner(app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, self.host, self.port)
        await site.start()
        logger.info("HTTP API listening on http://%s:%s", self.host, self.port)

    async def stop(self) -> None:
        if self._runner is not None:
            await self._runner.cleanup()

    @web.middleware
    async def _auth_middleware(self, request: web.Request, handler: Any) -> web.StreamResponse:
        if self.api_key:
            provided = request.headers.get("X-API-Key")
            if provided != self.api_key:
                return web.json_response({"error": "Unauthorized"}, status=401)
        return await handler(request)

    async def _handle_status(self, _request: web.Request) -> web.Response:
        return web.json_response(self._read_status())

    async def _handle_config_get(self, _request: web.Request) -> web.Response:
        return web.json_response(self._read_config())

    async def _handle_config_patch(self, request: web.Request) -> web.Response:
        try:
            payload = await request.json()
        except json.JSONDecodeError:
            return web.json_response({"error": "Invalid JSON"}, status=400)
        if not isinstance(payload, dict):
            return web.json_response({"error": "JSON object required"}, status=400)
        try:
            updated = self._write_config(payload)
        except Exception as exc:
            return web.json_response({"error": str(exc)}, status=400)
        return web.json_response(updated)

    async def _handle_latest(self, _request: web.Request) -> web.Response:
        return web.json_response(self._read_latest() or {})

    async def _handle_history(self, request: web.Request) -> web.Response:
        try:
            limit = int(request.query.get("limit", 20))
            offset = int(request.query.get("offset", 0))
        except ValueError:
            return web.json_response({"error": "limit and offset must be integers"}, status=400)
        return web.json_response(self._read_history(limit=limit, offset=offset))

    async def _handle_capture(self, _request: web.Request) -> web.Response:
        try:
            result = await self._on_capture()
        except Exception as exc:
            logger.exception("Capture failed")
            return web.json_response({"error": str(exc)}, status=500)
        return web.json_response(result)
