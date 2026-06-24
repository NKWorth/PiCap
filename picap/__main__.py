"""CLI entry point for PiCap."""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
from pathlib import Path

from picap.service import PiCapService


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="PiCap camera OCR service")
    parent = argparse.ArgumentParser(add_help=False)
    parent.add_argument(
        "--config",
        default="config.yaml",
        help="Path to YAML configuration file",
    )

    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser(
        "serve",
        parents=[parent],
        help="Run BLE API and wait for capture requests",
    )
    capture_parser = subparsers.add_parser(
        "capture",
        parents=[parent],
        help="Capture once and print JSON result",
    )
    capture_parser.add_argument(
        "--save",
        action="store_true",
        help="Persist result to database",
    )
    subparsers.add_parser(
        "config",
        parents=[parent],
        help="Print current configuration",
    )
    config_set_parser = subparsers.add_parser(
        "config-set",
        parents=[parent],
        help="Merge configuration from JSON file",
    )
    config_set_parser.add_argument("json_file", help="Path to JSON config patch")
    subparsers.add_parser(
        "latest",
        parents=[parent],
        help="Print latest stored reading",
    )
    history_parser = subparsers.add_parser(
        "history",
        parents=[parent],
        help="Print reading history",
    )
    history_parser.add_argument("--limit", type=int, default=10)
    history_parser.add_argument("--offset", type=int, default=0)
    return parser


def main(argv: list[str] | None = None) -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    args = build_parser().parse_args(argv)
    config_path = Path(args.config)
    if not config_path.exists():
        print(f"Config file not found: {config_path}", file=sys.stderr)
        print("Copy config.example.yaml to config.yaml and adjust it.", file=sys.stderr)
        return 1

    service = PiCapService(config_path)
    service.open()

    try:
        if args.command == "serve":
            asyncio.run(service.run())
            return 0
        if args.command == "capture":
            result = asyncio.run(service.capture_and_store()) if args.save else asyncio.run(
                _capture_without_save(service)
            )
            print(json.dumps(result, indent=2))
            return 0
        if args.command == "latest":
            latest = service.get_latest()
            print(json.dumps(latest or {}, indent=2))
            return 0
        if args.command == "config":
            print(json.dumps(service.get_config(), indent=2))
            return 0
        if args.command == "config-set":
            patch = json.loads(Path(args.json_file).read_text(encoding="utf-8"))
            updated = service.update_config(patch)
            print(json.dumps(updated, indent=2))
            return 0
        if args.command == "history":
            history = service.get_history(limit=args.limit, offset=args.offset)
            print(json.dumps(history, indent=2))
            return 0
    finally:
        service.close()

    return 1


async def _capture_without_save(service: PiCapService) -> dict:
    frame, image_path = service.camera.capture()
    readings = service.extract_readings(frame)
    from datetime import datetime, timezone

    from picap.models import CaptureResult

    result = CaptureResult(
        captured_at=datetime.now(timezone.utc),
        image_path=str(image_path),
        readings=readings,
    )
    payload = result.to_dict()
    payload["ocr_mode"] = service.ocr.mode
    return payload


if __name__ == "__main__":
    raise SystemExit(main())
