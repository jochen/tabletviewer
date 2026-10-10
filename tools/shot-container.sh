#!/bin/bash
# Wrapper: fuehrt tools/shot.py im Playwright-Container aus statt in einem
# lokalen venv+System-Chromium. Braucht den tabletviewer-Server laufend
# (Default http://localhost:8080) - Container laeuft mit --network=host,
# damit es den Host-Server erreicht.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
IMAGE="localhost/tabletviewer-shot:latest"

exec podman run --rm --network=host \
  -v "$PROJECT_DIR:/work:Z" \
  -w /work \
  "$IMAGE" \
  python tools/shot.py "$@"
