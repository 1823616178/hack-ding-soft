#!/usr/bin/env bash
# Build/test wrapper that avoids the machine's stale global GRADLE_USER_HOME.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
export GRADLE_USER_HOME="$ROOT/.gradle-home"

case "${1:-apk}" in
  test)
    exec gradle --no-daemon :app:testDebugUnitTest
    ;;
  apk)
    exec gradle --no-daemon :app:assembleDebug
    ;;
  all)
    exec gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
    ;;
  *)
    echo "Usage: bash build.sh [test|apk|all]" >&2
    exit 2
    ;;
esac
