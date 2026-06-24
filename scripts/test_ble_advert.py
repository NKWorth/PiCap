#!/usr/bin/env python3
"""Minimal BlueZ LE advertisement registration test."""

import asyncio

from dbus_next.aio import MessageBus
from dbus_next.constants import BusType
from dbus_next.service import ServiceInterface, dbus_property, method


class Adv(ServiceInterface):
    def __init__(self, local_name: str = "PiCap") -> None:
        super().__init__("org.bluez.LEAdvertisement1")
        self._type = "peripheral"
        self._service_uuids: list[str] = []
        self._manufacturer_data: dict = {}
        self._service_data: dict = {}
        self._local_name = local_name

    @method()
    def Release(self) -> None:
        pass

    @dbus_property()
    def Type(self) -> "s":
        return self._type

    @Type.setter
    def Type(self, value: "s") -> None:
        self._type = value

    @dbus_property()
    def ServiceUUIDs(self) -> "as":
        return self._service_uuids

    @ServiceUUIDs.setter
    def ServiceUUIDs(self, value: "as") -> None:
        self._service_uuids = value

    @dbus_property()
    def ManufacturerData(self) -> "a{qv}":
        return self._manufacturer_data

    @ManufacturerData.setter
    def ManufacturerData(self, value: "a{qv}") -> None:
        self._manufacturer_data = value

    @dbus_property()
    def ServiceData(self) -> "a{sv}":
        return self._service_data

    @ServiceData.setter
    def ServiceData(self, value: "a{sv}") -> None:
        self._service_data = value

    @dbus_property()
    def LocalName(self) -> "s":
        return self._local_name

    @LocalName.setter
    def LocalName(self, value: "s") -> None:
        self._local_name = value


async def register(local_name: str) -> None:
    bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    adv = Adv(local_name=local_name)
    path = "/org/picap/test/adv0"
    bus.export(path, adv)

    introspection = await bus.introspect("org.bluez", "/")
    manager = bus.get_proxy_object("org.bluez", "/", introspection)
    objects = await manager.get_interface("org.freedesktop.DBus.ObjectManager").call_get_managed_objects()

    adapter_path = next(
        path for path, ifaces in objects.items() if "org.bluez.Adapter1" in ifaces
    )
    adapter_intro = await bus.introspect("org.bluez", adapter_path)
    adapter = bus.get_proxy_object("org.bluez", adapter_path, adapter_intro)
    le = adapter.get_interface("org.bluez.LEAdvertisingManager1")
    await le.call_register_advertisement(path, {})
    print(f"registered ok (LocalName={local_name!r})")


async def main() -> None:
    for name in ("", "PiCap"):
        try:
            await register(name)
            print(f"success with LocalName={name!r}")
            return
        except Exception as exc:
            print(f"failed with LocalName={name!r}: {exc}")


if __name__ == "__main__":
    asyncio.run(main())
