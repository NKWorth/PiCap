#!/usr/bin/env python3
"""Headless BlueZ pairing agent that auto-accepts phone connections."""

from __future__ import annotations

import asyncio
import logging
import sys

from dbus_next.aio import MessageBus
from dbus_next.constants import BusType
from dbus_next.service import ServiceInterface, method

logger = logging.getLogger(__name__)
AGENT_PATH = "/org/picap/agent"


class PairingAgent(ServiceInterface):
    def __init__(self) -> None:
        super().__init__("org.bluez.Agent1")

    @method()
    def Release(self) -> "":  # noqa: N802
        logger.info("Agent released")

    @method()
    def RequestPinCode(self, device: "o") -> "s":  # noqa: N802
        logger.info("Auto PIN for %s", device)
        return "0000"

    @method()
    def DisplayPinCode(self, device: "o", pincode: "s") -> "":  # noqa: N802
        logger.info("Display PIN %s for %s", pincode, device)

    @method()
    def RequestPasskey(self, device: "o") -> "u":  # noqa: N802
        logger.info("Auto passkey for %s", device)
        return 0

    @method()
    def DisplayPasskey(self, device: "o", passkey: "u", entered: "q") -> "":  # noqa: N802
        logger.info("Display passkey %06d for %s", passkey, device)

    @method()
    def RequestConfirmation(self, device: "o", passkey: "u") -> "":  # noqa: N802
        logger.info("Auto-confirm pairing for %s (passkey %06d)", device, passkey)

    @method()
    def RequestAuthorization(self, device: "o") -> "":  # noqa: N802
        logger.info("Auto-authorize %s", device)

    @method()
    def AuthorizeService(self, device: "o", uuid: "s") -> "":  # noqa: N802
        logger.info("Authorize service %s for %s", uuid, device)

    @method()
    def Cancel(self) -> "":  # noqa: N802
        logger.info("Pairing cancelled")


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    agent = PairingAgent()
    bus.export(AGENT_PATH, agent)

    introspection = await bus.introspect("org.bluez", "/org/bluez")
    bluez_obj = bus.get_proxy_object("org.bluez", "/org/bluez", introspection)
    manager = bluez_obj.get_interface("org.bluez.AgentManager1")
    await manager.call_register_agent(AGENT_PATH, "NoInputNoOutput")  # type: ignore[attr-defined]
    await manager.call_request_default_agent(AGENT_PATH)  # type: ignore[attr-defined]
    logger.info("PiCap Bluetooth pairing agent active (NoInputNoOutput)")

    await asyncio.Event().wait()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
