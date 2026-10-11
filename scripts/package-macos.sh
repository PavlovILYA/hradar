#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
ARCH="${1:?arm64 or x86_64 required}"
test "$(uname -m)" = "$ARCH"
: "${JAVA_HOME:?JDK 21 required}"
VERSION=0.1.0
OUT="$ROOT/.tdlib-build/distribution-$ARCH"
INPUT="$ROOT/backend/build/desktop-input"
# Dedicated generated directory, never user data or native caches.
test ! -e "$OUT" || { echo "Output already exists: $OUT" >&2; exit 1; }
mkdir -p "$OUT" "$INPUT/native"
cp backend/native/libtdjni.dylib "$INPUT/native/"
cp .tdlib-build/release/*LICENSE* "$INPUT/"
python3 -B scripts/check-tdlib-bindings.py "$INPUT/tdlib-java.jar"
"$JAVA_HOME/bin/jpackage" --type app-image --name hradar --app-version "$VERSION" \
  --input "$INPUT" --main-jar backend-0.1.0-SNAPSHOT.jar --main-class dev.radar.ApplicationKt \
  --dest "$OUT" --vendor hradar --mac-package-identifier dev.hradar.desktop \
  --java-options '-Dhradar.desktop=true' --java-options '-Djava.library.path=$APPDIR/native' \
  --java-options '-Dfile.encoding=UTF-8' --jlink-options '--strip-debug --no-header-files --no-man-pages --add-modules java.desktop,java.net.http,java.sql,jdk.unsupported,jdk.crypto.ec,jdk.management,jdk.naming.dns'
/usr/libexec/PlistBuddy -c 'Set :LSMinimumSystemVersion 15.0' "$OUT/hradar.app/Contents/Info.plist"
# Explicit optional signing. PRs never receive Apple secrets.
if [[ "${HRADAR_SIGN:-false}" = true ]]; then
  bash scripts/sign-macos.sh "$OUT/hradar.app"
fi
python3 -B scripts/verify-macos-image.py "$OUT/hradar.app" "$ARCH"
"$JAVA_HOME/bin/jpackage" --type dmg --name hradar --app-version "$VERSION" --app-image "$OUT/hradar.app" --dest "$OUT/dmg"
DMG="$OUT/dmg/hradar-$VERSION.dmg"
if [[ "${HRADAR_SIGN:-false}" = true ]]; then
  codesign --force --timestamp --sign "$APPLE_SIGNING_IDENTITY" "$DMG"
  xcrun notarytool submit "$DMG" --keychain-profile "$APPLE_NOTARY_PROFILE" --wait
  xcrun stapler staple "$DMG"
  xcrun stapler validate "$DMG"
  spctl --assess --type open --context context:primary-signature --verbose "$DMG"
fi
mv "$DMG" "$OUT/hradar-$VERSION-$ARCH.dmg"
rmdir "$OUT/dmg"
(cd "$OUT"; shasum -a 256 "hradar-$VERSION-$ARCH.dmg" > SHA256SUMS)
# ZIP preserves executable bits/symlinks better than raw upload-artifact of .app.
ditto -c -k --sequesterRsrc --keepParent "$OUT/hradar.app" "$OUT/hradar-$VERSION-$ARCH.app.zip"
(cd "$OUT"; shasum -a 256 "hradar-$VERSION-$ARCH.app.zip" >> SHA256SUMS)
python3 - "$OUT" "$ARCH" <<'PY'
import json,os,pathlib,subprocess,sys
out=pathlib.Path(sys.argv[1])
(out/'distribution.json').write_text(json.dumps({'architecture':sys.argv[2], 'minimumMacOS':'15.0', 'signedAndNotarized':os.environ.get('HRADAR_SIGN')=='true', 'commit':os.environ.get('GITHUB_SHA'), 'run':os.environ.get('GITHUB_RUN_ID'), 'jdk':subprocess.check_output([os.environ['JAVA_HOME']+'/bin/java','--version'],text=True), 'native':json.load(open('.tdlib-build/release/tdlib-artifact.json'))},indent=2)+'\n')
PY
