#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
exec python3 "$repo/tools/compatibility-rehearsal/rehearse.py" "${1:?Usage: run.sh EVIDENCE_DIRECTORY}"
