"""Wall-clock schedule helpers for interval captures with a lead buffer."""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
from typing import Any
from zoneinfo import ZoneInfo


def resolve_timezone(name: str | None):
    if not name or str(name).lower() in {"local", "system"}:
        return datetime.now().astimezone().tzinfo or timezone.utc
    return ZoneInfo(str(name))


def parse_schedule_config(raw: dict[str, Any] | None) -> dict[str, Any]:
    source = raw if isinstance(raw, dict) else {}
    interval_minutes = max(1, int(source.get("interval_minutes", 15)))
    buffer_seconds = max(0, int(source.get("buffer_seconds", 10)))
    if buffer_seconds >= interval_minutes * 60:
        raise ValueError("schedule.buffer_seconds must be less than interval_minutes * 60")
    return {
        "enabled": bool(source.get("enabled", True)),
        "interval_minutes": interval_minutes,
        "buffer_seconds": buffer_seconds,
        "timezone": str(source.get("timezone", "local")),
        "retain_days": max(1, int(source.get("retain_days", 30))),
    }


def next_slot_after(moment: datetime, *, interval_minutes: int) -> datetime:
    """Return the next interval boundary strictly after `moment`."""
    midnight = moment.replace(hour=0, minute=0, second=0, microsecond=0)
    elapsed = (moment - midnight).total_seconds()
    step = interval_minutes * 60
    slots_passed = int(elapsed // step)
    candidate = midnight + timedelta(seconds=(slots_passed + 1) * step)
    if candidate <= moment:
        candidate += timedelta(seconds=step)
    return candidate


def slot_for_capture_time(
    capture_at: datetime,
    *,
    interval_minutes: int,
    buffer_seconds: int,
) -> datetime:
    """Map an actual capture timestamp to the interval mark it represents."""
    estimated = capture_at + timedelta(seconds=buffer_seconds)
    midnight = estimated.replace(hour=0, minute=0, second=0, microsecond=0)
    step = interval_minutes * 60
    elapsed = (estimated - midnight).total_seconds()
    slot_index = int(round(elapsed / step))
    return midnight + timedelta(seconds=slot_index * step)


def next_capture_at(
    now: datetime,
    *,
    interval_minutes: int,
    buffer_seconds: int,
) -> tuple[datetime, datetime]:
    """
    Return (capture_at, slot_at) for the next scheduled capture.

    Example: interval=15, buffer=10 → capture 07:59:50 for slot 08:00:00.
    """
    probe = now
    for _ in range(16):
        slot = next_slot_after(probe, interval_minutes=interval_minutes)
        capture = slot - timedelta(seconds=buffer_seconds)
        if capture > now:
            return capture, slot
        probe = slot
    slot = next_slot_after(now, interval_minutes=interval_minutes)
    return slot - timedelta(seconds=buffer_seconds), slot


def seconds_until(target: datetime, now: datetime) -> float:
    return max(0.05, (target - now).total_seconds())


def local_date_for_slot(slot_at: datetime) -> date:
    return slot_at.date()
