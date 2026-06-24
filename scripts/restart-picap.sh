#!/usr/bin/env bash
# Restart PiCap if running, otherwise start it.
#
# Usage:
#   ./scripts/restart-picap.sh
#   ./scripts/restart-picap.sh --http-only

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/start-picap.sh" --restart "$@"
