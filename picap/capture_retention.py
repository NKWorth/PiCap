"""Prune old capture images and matching database rows."""

from __future__ import annotations

import logging
from pathlib import Path

from picap.db import Database

logger = logging.getLogger(__name__)


def prune_captures(
    capture_dir: Path,
    database: Database,
    *,
    max_captures: int,
) -> tuple[int, int]:
    """Keep only the newest capture files and drop unreferenced readings."""
    if max_captures <= 0:
        return 0, 0

    directory = capture_dir.resolve()
    directory.mkdir(parents=True, exist_ok=True)

    files = sorted(
        directory.glob("capture_*.jpg"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    kept = files[:max_captures]
    kept_names = {path.name for path in kept}

    deleted_files = 0
    for path in files[max_captures:]:
        try:
            path.unlink()
            deleted_files += 1
        except OSError as exc:
            logger.warning("Failed to delete old capture %s: %s", path, exc)

    deleted_rows = database.prune_readings_except_filenames(kept_names)

    if deleted_files or deleted_rows:
        logger.info(
            "Capture retention kept %s file(s); deleted %s file(s) and %s reading(s)",
            len(kept),
            deleted_files,
            deleted_rows,
        )

    return deleted_files, deleted_rows
