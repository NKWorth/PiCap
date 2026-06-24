#!/usr/bin/env bash
# Convert Windows CRLF line endings to Unix LF in PiCap shell scripts.
set -eu

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

for file in "$SCRIPT_DIR"/*.sh; do
  sed -i 's/\r$//' "$file"
  chmod +x "$file"
  echo "Fixed: $file"
done

echo "Done. Run scripts with: bash $SCRIPT_DIR/start-picap.sh"
