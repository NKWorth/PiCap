#!/usr/bin/env python3
"""Quick test that bless can register a LE advertisement."""

import asyncio

from bless import BlessServer


async def main() -> None:
    server = BlessServer(name="PiCap")
    await server.start()
    print("BLE started OK")
    await asyncio.sleep(3)
    await server.stop()
    print("BLE stopped")


if __name__ == "__main__":
    asyncio.run(main())
