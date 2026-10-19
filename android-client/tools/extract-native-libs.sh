#!/usr/bin/env bash
# Extract only the native libraries needed by the thin client from the source APK.
# The Gradle build does the same thing; this script exists for reproducible review.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-$ROOT/../base.apk}"
OUT="${2:-$ROOT/app/src/main/jniLibs/arm64-v8a}"

if [[ ! -f "$APK" ]]; then
  echo "Source APK not found: $APK" >&2
  exit 1
fi

mkdir -p "$OUT"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

unzip -q -o "$APK" \
  'lib/arm64-v8a/libHWFaceRecogLibSDK.so' \
  'lib/arm64-v8a/libc++_shared.so' \
  -d "$TMP"

cp "$TMP/lib/arm64-v8a/libHWFaceRecogLibSDK.so" "$OUT/"
cp "$TMP/lib/arm64-v8a/libc++_shared.so" "$OUT/"

echo "Source APK SHA-256:"
sha256sum "$APK"
echo "Extracted libraries:"
sha256sum "$OUT/libHWFaceRecogLibSDK.so" "$OUT/libc++_shared.so"
