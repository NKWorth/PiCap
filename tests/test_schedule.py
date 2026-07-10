"""Unit tests for wall-clock schedule helpers."""

from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

from picap.schedule import next_capture_at, next_slot_after, slot_for_capture_time


TZ = ZoneInfo("America/Los_Angeles")


def _dt(year: int, month: int, day: int, hour: int, minute: int, second: int = 0) -> datetime:
    return datetime(year, month, day, hour, minute, second, tzinfo=TZ)


def test_next_slot_after_aligns_to_interval() -> None:
    assert next_slot_after(_dt(2026, 7, 10, 7, 59, 50), interval_minutes=15) == _dt(2026, 7, 10, 8, 0, 0)
    assert next_slot_after(_dt(2026, 7, 10, 8, 0, 0), interval_minutes=15) == _dt(2026, 7, 10, 8, 15, 0)
    assert next_slot_after(_dt(2026, 7, 10, 8, 14, 50), interval_minutes=15) == _dt(2026, 7, 10, 8, 15, 0)


def test_next_capture_with_buffer() -> None:
    capture, slot = next_capture_at(
        _dt(2026, 7, 10, 7, 50, 0),
        interval_minutes=15,
        buffer_seconds=10,
    )
    assert capture == _dt(2026, 7, 10, 7, 59, 50)
    assert slot == _dt(2026, 7, 10, 8, 0, 0)

    capture, slot = next_capture_at(
        _dt(2026, 7, 10, 8, 0, 0),
        interval_minutes=15,
        buffer_seconds=10,
    )
    assert capture == _dt(2026, 7, 10, 8, 14, 50)
    assert slot == _dt(2026, 7, 10, 8, 15, 0)

    capture, slot = next_capture_at(
        _dt(2026, 7, 10, 8, 14, 51),
        interval_minutes=15,
        buffer_seconds=10,
    )
    assert capture == _dt(2026, 7, 10, 8, 29, 50)
    assert slot == _dt(2026, 7, 10, 8, 30, 0)


def test_slot_for_capture_time() -> None:
    assert slot_for_capture_time(
        _dt(2026, 7, 10, 7, 59, 50),
        interval_minutes=15,
        buffer_seconds=10,
    ) == _dt(2026, 7, 10, 8, 0, 0)
    assert slot_for_capture_time(
        _dt(2026, 7, 10, 8, 14, 50),
        interval_minutes=15,
        buffer_seconds=10,
    ) == _dt(2026, 7, 10, 8, 15, 0)


if __name__ == "__main__":
    test_next_slot_after_aligns_to_interval()
    test_next_capture_with_buffer()
    test_slot_for_capture_time()
    print("ok")
