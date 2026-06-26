"""SQLite persistence for capture readings and runtime config."""

from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime
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
                    readings_json TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS app_config (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                );

                CREATE INDEX IF NOT EXISTS idx_readings_captured_at
                    ON readings(captured_at DESC);
                """
            )

    def save_reading(self, result: CaptureResult) -> int:
        values = result.values_dict()
        readings = [r.to_dict() for r in result.readings]
        with self._connect() as conn:
            cursor = conn.execute(
                """
                INSERT INTO readings (captured_at, image_path, values_json, readings_json)
                VALUES (?, ?, ?, ?)
                """,
                (
                    result.captured_at.isoformat(),
                    result.image_path,
                    json.dumps(values),
                    json.dumps(readings),
                ),
            )
            return int(cursor.lastrowid)

    def prune_readings_except_filenames(self, kept_filenames: set[str]) -> int:
        if not kept_filenames:
            return 0
        with self._connect() as conn:
            rows = conn.execute("SELECT id, image_path FROM readings").fetchall()
            delete_ids = [
                int(row["id"])
                for row in rows
                if Path(str(row["image_path"])).name not in kept_filenames
            ]
            if not delete_ids:
                return 0
            placeholders = ",".join("?" for _ in delete_ids)
            conn.execute(f"DELETE FROM readings WHERE id IN ({placeholders})", delete_ids)
            return len(delete_ids)

    def get_latest_reading(self) -> dict[str, Any] | None:
        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT id, captured_at, image_path, values_json, readings_json
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
                SELECT id, captured_at, image_path, values_json, readings_json
                FROM readings
                ORDER BY captured_at DESC
                LIMIT ? OFFSET ?
                """,
                (limit, offset),
            ).fetchall()
        return [self._row_to_dict(row) for row in rows]

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
        return {
            "id": row["id"],
            "captured_at": row["captured_at"],
            "image_path": row["image_path"],
            "values": json.loads(row["values_json"]),
            "readings": json.loads(row["readings_json"]),
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
        return CaptureResult(
            captured_at=datetime.fromisoformat(row["captured_at"]),
            image_path=row["image_path"],
            readings=readings,
        )
