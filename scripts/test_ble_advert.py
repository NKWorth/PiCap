#!/usr/bin/env python3
"""Minimal BlueZ LE advertisement registration test."""

import asyncio

from dbus_next.aio import MessageBus
from dbus_next.constants import BusType
from dbus_next.service import ServiceInterface, dbus_property, method


class Adv(ServiceInterface):
    def __init__(self) -> None:
        super().__init__("org.bluez.LEAdvertisement1")

    @dbus_property()
    def Type(self) -> "s":
        return "peripheral"

    @dbus_property()
    def ServiceUUIDs(self) -> "as":
        return []

    @dbus_property()
    def ManufacturerData(self) -> "a{qv}":
        return {}

    @dbus_property()
    def ServiceData(self) -> "a{sv}":
        return {}

    @dbus_property()
    def LocalName(self) -> "s":
        return "PiCap"

    @LocalName.setter  # type: ignore
    def LocalName(self, name: str) -> None:
        pass

    @method()
    def Release(self) -> None:
        pass


async def main() -> None:
    bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    adv = Adv()
    bus.export("/org/test/adv0", adv)

    introspection = await bus.introspect("org.bluez", "/")
    manager = bus.get_proxy_object("org.bluez", "/", introspection)
    objects = await manager.get_interface("org.freedesktop.DBus.ObjectManager").call_get_managed_objects()

    adapter_path = next(
        path for path, ifaces in objects.items() if "org.bluez.Adapter1" in ifaces
    )
    adapter_intro = await bus.introspect("org.bluez", adapter_path)
    adapter = bus.get_proxy_object("org.bluez", adapter_path, adapter_intro)
    le = adapter.get_interface("org.bluez.LEAdvertisingManager1")
    await le.call_register_advertisement("/org/test/adv0", {})
    print("registered ok")


if __name__ == "__main__":
    asyncio.run(main())
