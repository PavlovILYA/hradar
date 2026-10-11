#!/bin/bash
set -euo pipefail
ARCH="${1:?architecture required}"
WORK="${2:?downloaded unsigned artifact directory required}"
: "${JAVA_HOME:?JDK 21 required}"
VERSION=0.1.0
# macOS CFBundleVersion/jpackage requires a non-zero leading component.
MAC_VERSION=1.0.0
APP="$WORK/hradar.app"
ditto -x -k "$WORK/hradar-$VERSION-$ARCH.app.zip" "$WORK"
bash scripts/sign-macos.sh "$APP"
ditto -c -k --sequesterRsrc --keepParent "$APP" "$WORK/notary-app.zip"
xcrun notarytool submit "$WORK/notary-app.zip" --keychain-profile "$APPLE_NOTARY_PROFILE" --wait
xcrun stapler staple "$APP"
xcrun stapler validate "$APP"
spctl --assess --type execute --verbose "$APP"
mkdir -p "$WORK/signed"
"$JAVA_HOME/bin/jpackage" --type dmg --name hradar --app-version "$MAC_VERSION" --app-image "$APP" --dest "$WORK/signed"
DMG="$WORK/signed/hradar-$MAC_VERSION.dmg"
codesign --force --timestamp --sign "$APPLE_SIGNING_IDENTITY" "$DMG"
xcrun notarytool submit "$DMG" --keychain-profile "$APPLE_NOTARY_PROFILE" --wait
xcrun stapler staple "$DMG"
xcrun stapler validate "$DMG"
spctl --assess --type open --context context:primary-signature --verbose "$DMG"
mv "$DMG" "$WORK/signed/hradar-$VERSION-$ARCH.dmg"
ditto -c -k --sequesterRsrc --keepParent "$APP" "$WORK/signed/hradar-$VERSION-$ARCH.app.zip"
(cd "$WORK/signed"; shasum -a 256 *.dmg *.app.zip > SHA256SUMS)
python3 - "$WORK" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]); m=json.load(open(p/'distribution.json')); m['signedAndNotarized']=True
(p/'signed/distribution.json').write_text(json.dumps(m,indent=2)+'\n')
PY
