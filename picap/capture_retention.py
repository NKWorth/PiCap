"""Prune old capture images and matching database rows."""

from __future__ import annotations

import logging
from datetime import date, timedelta
from pathlib import Path

from picap.db import Database

logger = logging.getLogger(__name__)


def prune_captures(
    capture_dir: Path,
    database: Database,
    *,
    max_captures: int,
) -> tuple[int, int]:
    """Keep only the newest manual capture files and drop unreferenced manual readings."""
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

    deleted_rows = database.prune_manual_readings_except_filenames(kept_names)

    if deleted_files or deleted_rows:
        logger.info(
            "Capture retention kept %s file(s); deleted %s file(s) and %s manual reading(s)",
            len(kept),
            deleted_files,
            deleted_rows,
        )

    return deleted_files, deleted_rows


def prune_scheduled_history(
    capture_dir: Path,
    database: Database,
    *,
    retain_days: int,
) -> tuple[int, int]:
    """Drop scheduled readings (and images) older than retain_days."""
    if retain_days <= 0:
        return 0, 0

    cutoff = date.today() - timedelta(days=retain_days)
    image_paths = database.prune_scheduled_older_than(cutoff)
    deleted_files = 0
    directory = capture_dir.resolve()
    for image_path_str in image_paths:
        path = Path(image_path_str)
        candidates = [path, directory / path.name]
        for candidate in candidates:
            try:
                resolved = candidate.resolve()
                if resolved.is_file() and resolved.parent == directory:
                    resolved.unlink()
                    deleted_files += 1
                    break
            except OSError as exc:
                logger.warning("Failed to delete scheduled capture %s: %s", candidate, exc)

    if image_paths:
        logger.info(
            "Scheduled retention cutoff %s: deleted %s reading(s) and %s file(s)",
            cutoff.isoformat(),
            len(image_paths),
            deleted_files,
        )
    return len(image_paths), deleted_files
