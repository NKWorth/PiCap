#!/usr/bin/env bash
# Link this repo to GitHub and push the main branch.
#
# Usage:
#   ./scripts/github-setup.sh YOUR_GITHUB_USERNAME
#   ./scripts/github-setup.sh YOUR_GITHUB_USERNAME PiCap
#
# Create the empty repository on GitHub first:
#   https://github.com/new
# Name it PiCap (or pass a custom name as the second argument).

set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <github-username> [repo-name]" >&2
  exit 1
fi

USERNAME="$1"
REPO_NAME="${2:-PiCap}"
REMOTE="git@github.com:${USERNAME}/${REPO_NAME}.git"

if git remote get-url origin >/dev/null 2>&1; then
  echo "Remote 'origin' already exists:"
  git remote -v
  exit 1
fi

git remote add origin "$REMOTE"
git push -u origin main

echo "Done. Remote origin is $REMOTE"
