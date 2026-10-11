#!/bin/bash
# Preserve the already verified/signed app verbatim; jpackage DMG may reseal it.
set -euo pipefail
APP="${1:?app image required}"
DMG="${2:?output DMG required}"
test -d "$APP" && test ! -e "$DMG"
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/hradar-dmg.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
/usr/bin/ditto "$APP" "$STAGE/hradar.app"
ln -s /Applications "$STAGE/Applications"
codesign --verify --deep --strict "$STAGE/hradar.app"
hdiutil create -volname hradar -srcfolder "$STAGE" -format UDZO -fs HFS+ "$DMG"
hdiutil verify "$DMG"
