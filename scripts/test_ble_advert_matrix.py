#!/usr/bin/env python3
"""Try several LE advertisement payloads against BlueZ."""

import asyncio

from dbus_next.aio import MessageBus
from dbus_next.constants import BusType
from dbus_next.service import ServiceInterface, dbus_property, method


class Adv(ServiceInterface):
    def __init__(self, **props: object) -> None:
        super().__init__("org.bluez.LEAdvertisement1")
        self._type = str(props.get("type", "peripheral"))
        self._service_uuids = list(props.get("uuids", []))
        self._manufacturer_data = dict(props.get("mfg", {}))
        self._service_data = dict(props.get("svcdata", {}))
        self._local_name = str(props.get("name", ""))
        self._includes_tx = bool(props.get("tx", False))

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

    @dbus_property()
    def IncludeTxPower(self) -> "b":
        return self._includes_tx

    @IncludeTxPower.setter
    def IncludeTxPower(self, value: "b") -> None:
        self._includes_tx = value


async def try_case(label: str, **props: object) -> None:
    bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    path = "/org/picap/matrix/" + label.replace(" ", "_")
    bus.export(path, Adv(**props))
    intro = await bus.introspect("org.bluez", "/")
    mgr = bus.get_proxy_object("org.bluez", "/", intro)
    objs = await mgr.get_interface("org.freedesktop.DBus.ObjectManager").call_get_managed_objects()
    adapter_path = next(path for path, ifaces in objs.items() if "org.bluez.Adapter1" in ifaces)
    adapter = bus.get_proxy_object(
        "org.bluez",
        adapter_path,
        await bus.introspect("org.bluez", adapter_path),
    )
    le = adapter.get_interface("org.bluez.LEAdvertisingManager1")
    try:
        await le.call_register_advertisement(path, {})
        print(f"OK  {label}")
    except Exception as exc:
        print(f"FAIL {label}: {exc}")


async def main() -> None:
    cases = [
        ("name only", {"name": "PiCap"}),
        ("uuid16", {"uuids": ["1800"]}),
        ("mfg", {"mfg": {0xFFFF: bytes([1, 2, 3])}}),
        ("name+uuid16", {"name": "PiCap", "uuids": ["1800"]}),
        ("name+tx", {"name": "PiCap", "tx": True}),
    ]
    for label, props in cases:
        await try_case(label, **props)


if __name__ == "__main__":
    asyncio.run(main())
