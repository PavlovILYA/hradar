#!/bin/bash
set -euo pipefail
: "${APPLE_SIGNING_IDENTITY:?Developer ID Application identity required}"
: "${APPLE_NOTARY_PROFILE:?Provisioned notarytool keychain profile required}"
APP="${1:?app image required}"
# Sign nested binaries first, then runtime bundle, then the outer app. No --deep signing.
while IFS= read -r -d '' binary; do
  if /usr/bin/file -b "$binary" | /usr/bin/grep -q 'Mach-O'; then
    codesign --force --options runtime --timestamp --entitlements packaging/macos-entitlements.plist --sign "$APPLE_SIGNING_IDENTITY" "$binary"
  fi
done < <(find "$APP/Contents" -type f -print0)
codesign --force --options runtime --timestamp --entitlements packaging/macos-entitlements.plist --sign "$APPLE_SIGNING_IDENTITY" "$APP/Contents/runtime"
codesign --force --options runtime --timestamp --entitlements packaging/macos-entitlements.plist --sign "$APPLE_SIGNING_IDENTITY" "$APP"
codesign --verify --deep --strict --verbose=2 "$APP"
