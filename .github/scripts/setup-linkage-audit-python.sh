#!/usr/bin/env bash
# Install the offline-audit parser without changing the runner's global Python.
set -euo pipefail

VENV="${RUNNER_TEMP:?RUNNER_TEMP is required}/linkage-audit-python"
if [ ! -f "$VENV/pyvenv.cfg" ]; then
  python3 -m venv "$VENV"
fi
"$VENV/bin/python" -m pip install --disable-pip-version-check --only-binary=:all: --require-hashes \
  -r "$(dirname "$0")/linkage-audit-requirements.txt"
printf '%s\n' "$VENV/bin" >> "${GITHUB_PATH:?GITHUB_PATH is required}"
