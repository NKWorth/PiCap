"""SQLite persistence for capture readings and runtime config."""

from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from datetime import date, datetime
from pathlib import Path
from typing import Any, Iterator

from picap.models import CaptureResult, RegionReading


class Database:
    def __init__(self, db_path: str | Path) -> None:
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._init_schema()

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def _init_schema(self) -> None:
        with self._connect() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS readings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    captured_at TEXT NOT NULL,
                    image_path TEXT NOT NULL,
                    values_json TEXT NOT NULL,
                    readings_json TEXT NOT NULL,
                    source TEXT NOT NULL DEFAULT 'manual',
                    slot_at TEXT,
                    local_date TEXT
                );

                CREATE TABLE IF NOT EXISTS app_config (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                );
                """
            )
            # Existing DBs may predate schedule columns; add them before indexes.
            self._ensure_column(conn, "readings", "source", "TEXT NOT NULL DEFAULT 'manual'")
            self._ensure_column(conn, "readings", "slot_at", "TEXT")
            self._ensure_column(conn, "readings", "local_date", "TEXT")
            conn.executescript(
                """
                CREATE INDEX IF NOT EXISTS idx_readings_captured_at
                    ON readings(captured_at DESC);

                CREATE INDEX IF NOT EXISTS idx_readings_local_date
                    ON readings(local_date);

                CREATE INDEX IF NOT EXISTS idx_readings_slot_at
                    ON readings(slot_at);
                """
            )

    @staticmethod
    def _ensure_column(conn: sqlite3.Connection, table: str, column: str, definition: str) -> None:
        existing = {
            str(row["name"])
            for row in conn.execute(f"PRAGMA table_info({table})").fetchall()
        }
        if column not in existing:
            conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")

    def save_reading(self, result: CaptureResult) -> int:
        values = result.values_dict()
        readings = [r.to_dict() for r in result.readings]
        with self._connect() as conn:
            cursor = conn.execute(
                """
                INSERT INTO readings (
                    captured_at, image_path, values_json, readings_json,
                    source, slot_at, local_date
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    result.captured_at.isoformat(),
                    result.image_path,
                    json.dumps(values),
                    json.dumps(readings),
                    result.source,
                    result.slot_at.isoformat() if result.slot_at else None,
                    result.local_date.isoformat() if result.local_date else None,
                ),
            )
            return int(cursor.lastrowid)

    def prune_manual_readings_except_filenames(self, kept_filenames: set[str]) -> int:
        """Delete manual readings whose image files were pruned. Keep scheduled rows."""
        if not kept_filenames:
            return 0
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT id, image_path, source FROM readings"
            ).fetchall()
            delete_ids = [
                int(row["id"])
                for row in rows
                if str(row["source"] or "manual") != "scheduled"
                and Path(str(row["image_path"])).name not in kept_filenames
            ]
            if not delete_ids:
                return 0
            placeholders = ",".join("?" for _ in delete_ids)
            conn.execute(f"DELETE FROM readings WHERE id IN ({placeholders})", delete_ids)
            return len(delete_ids)

    def prune_readings_except_filenames(self, kept_filenames: set[str]) -> int:
        # Back-compat alias used by older callers.
        return self.prune_manual_readings_except_filenames(kept_filenames)

    def prune_scheduled_older_than(self, cutoff_date: date) -> list[str]:
        """Delete scheduled readings older than cutoff; return their image paths."""
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT id, image_path FROM readings
                WHERE source = 'scheduled'
                  AND local_date IS NOT NULL
                  AND local_date < ?
                """,
                (cutoff_date.isoformat(),),
            ).fetchall()
            if not rows:
                return []
            paths = [str(row["image_path"]) for row in rows]
            ids = [int(row["id"]) for row in rows]
            placeholders = ",".join("?" for _ in ids)
            conn.execute(f"DELETE FROM readings WHERE id IN ({placeholders})", ids)
            return paths

    def get_latest_reading(self) -> dict[str, Any] | None:
        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT id, captured_at, image_path, values_json, readings_json,
                       source, slot_at, local_date
                FROM readings
                ORDER BY captured_at DESC
                LIMIT 1
                """
            ).fetchone()
        if row is None:
            return None
        return self._row_to_dict(row)

    def get_readings(self, limit: int = 20, offset: int = 0) -> list[dict[str, Any]]:
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT id, captured_at, image_path, values_json, readings_json,
                       source, slot_at, local_date
                FROM readings
                ORDER BY captured_at DESC
                LIMIT ? OFFSET ?
                """,
                (limit, offset),
            ).fetchall()
        return [self._row_to_dict(row) for row in rows]

    def get_day_report(self, local_date: date) -> dict[str, Any]:
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT id, captured_at, image_path, values_json, readings_json,
                       source, slot_at, local_date
                FROM readings
                WHERE local_date = ?
                  AND source = 'scheduled'
                ORDER BY slot_at ASC, captured_at ASC
                """,
                (local_date.isoformat(),),
            ).fetchall()
        readings = [self._row_to_dict(row) for row in rows]
        # Prefer one reading per slot (latest capture for that slot).
        by_slot: dict[str, dict[str, Any]] = {}
        for item in readings:
            slot_key = str(item.get("slot_at") or item["captured_at"])
            by_slot[slot_key] = item
        slots = [by_slot[key] for key in sorted(by_slot)]
        return {
            "date": local_date.isoformat(),
            "slot_count": len(slots),
            "slots": slots,
        }

    def has_scheduled_slot(self, slot_at: datetime) -> bool:
        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT id FROM readings
                WHERE source = 'scheduled' AND slot_at = ?
                LIMIT 1
                """,
                (slot_at.isoformat(),),
            ).fetchone()
        return row is not None

    def get_config_value(self, key: str) -> str | None:
        with self._connect() as conn:
            row = conn.execute(
                "SELECT value FROM app_config WHERE key = ?", (key,)
            ).fetchone()
        return None if row is None else str(row["value"])

    def set_config_value(self, key: str, value: str) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO app_config (key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """,
                (key, value),
            )

    @staticmethod
    def _row_to_dict(row: sqlite3.Row) -> dict[str, Any]:
        keys = set(row.keys())
        return {
            "id": row["id"],
            "captured_at": row["captured_at"],
            "image_path": row["image_path"],
            "values": json.loads(row["values_json"]),
            "readings": json.loads(row["readings_json"]),
            "source": row["source"] if "source" in keys and row["source"] is not None else "manual",
            "slot_at": row["slot_at"] if "slot_at" in keys else None,
            "local_date": row["local_date"] if "local_date" in keys else None,
        }

    @staticmethod
    def capture_result_from_row(row: dict[str, Any]) -> CaptureResult:
        readings = [
            RegionReading(
                name=str(item["name"]),
                value=item.get("value"),
                confidence=float(item.get("confidence", 0)),
            )
            for item in row["readings"]
        ]
        slot_at = None
        if row.get("slot_at"):
            slot_at = datetime.fromisoformat(str(row["slot_at"]))
        local_date_value = None
        if row.get("local_date"):
            local_date_value = date.fromisoformat(str(row["local_date"]))
        return CaptureResult(
            captured_at=datetime.fromisoformat(row["captured_at"]),
            image_path=row["image_path"],
            readings=readings,
            source=str(row.get("source") or "manual"),
            slot_at=slot_at,
            local_date=local_date_value,
        )
