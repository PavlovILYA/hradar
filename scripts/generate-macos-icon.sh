#!/bin/bash
# Single approved PNG master; standard macOS tools only, architecture independent.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT="${1:-$ROOT/.tdlib-build/branding/hradar.icns}"
SOURCE="$ROOT/branding/hradar-source.png"
INFO="$(sips -g format -g pixelWidth -g pixelHeight -g hasAlpha "$SOURCE")"
[[ "$INFO" == *'format: png'* && "$INFO" == *'pixelWidth: 1254'* && "$INFO" == *'pixelHeight: 1254'* && "$INFO" == *'hasAlpha: yes'* ]]
WORK="$(mktemp -d "${TMPDIR:-/tmp}/hradar-icon.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
ICONSET="$WORK/hradar.iconset"
mkdir -p "$ICONSET" "$(dirname "$OUTPUT")"
for SIZE in 16 32 128 256 512; do
  sips -z "$SIZE" "$SIZE" "$SOURCE" --out "$ICONSET/icon_${SIZE}x${SIZE}.png" >/dev/null
  DOUBLE=$((SIZE * 2))
  sips -z "$DOUBLE" "$DOUBLE" "$SOURCE" --out "$ICONSET/icon_${SIZE}x${SIZE}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$WORK/hradar.icns"
mv "$WORK/hradar.icns" "$OUTPUT"
echo "Generated $OUTPUT"
