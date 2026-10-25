#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
ARCH="${1:?arm64 or x86_64 required}"
test "$(uname -m)" = "$ARCH"
: "${JAVA_HOME:?JDK 21 required}"
python3 -B scripts/version.py check
VERSION="$(python3 -B scripts/version.py get)"
SHORT_VERSION="$(python3 -B scripts/version.py get --field shortVersion)"
JAR_NAME="$(python3 -B scripts/version.py get --field jarName)"
# CI allocates once for both architectures; local builds use a locked durable counter.
MAC_VERSION="${HRADAR_BUILD_NUMBER:-$(python3 -B scripts/version.py build-number)}"
export HRADAR_BUILD_NUMBER="$MAC_VERSION"
python3 -B scripts/version.py validate-build-number --build-number "$MAC_VERSION"
OUT="$ROOT/.tdlib-build/distribution-$ARCH"
INPUT="$ROOT/backend/build/desktop-input"
# Dedicated generated directory, never user data or native caches.
test ! -e "$OUT" || { echo "Output already exists: $OUT" >&2; exit 1; }
python3 -B scripts/version.py verify-jar --path "$INPUT/$JAR_NAME"
# Reject leftover application JARs from an earlier version in a reused staging directory.
test "$(find "$INPUT" -maxdepth 1 -name 'backend-*.jar' | wc -l | tr -d ' ')" = 1
mkdir -p "$OUT" "$INPUT/native"
cp backend/native/libtdjni.dylib "$INPUT/native/"
# SQLite otherwise extracts an unsigned dylib into temp; sign the actual app-native copy.
python3 - "$INPUT" "$ARCH" <<'PYSQLITE'
import pathlib,sys,zipfile
p=pathlib.Path(sys.argv[1]); arch='aarch64' if sys.argv[2]=='arm64' else 'x86_64'
jars=list(p.glob('sqlite-jdbc-*.jar')); assert len(jars)==1
with zipfile.ZipFile(jars[0]) as z:
    (p/'native/libsqlitejdbc.dylib').write_bytes(z.read(f'org/sqlite/native/Mac/{arch}/libsqlitejdbc.dylib'))
PYSQLITE
cp .tdlib-build/release/*LICENSE* "$INPUT/"
python3 -B scripts/check-tdlib-bindings.py "$INPUT/tdlib-java.jar"
"$JAVA_HOME/bin/jpackage" --type app-image --name hradar --app-version "$MAC_VERSION" \
  --input "$INPUT" --main-jar "$JAR_NAME" --main-class dev.radar.ApplicationKt \
  --dest "$OUT" --vendor hradar --mac-package-identifier dev.hradar.desktop \
  --java-options '-Dhradar.desktop=true' --java-options '-Djava.library.path=$APPDIR/native' \
  --java-options '-Dorg.sqlite.lib.path=$APPDIR/native' --java-options '-Dorg.sqlite.lib.name=libsqlitejdbc.dylib' --java-options '-Dfile.encoding=UTF-8' --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.management,jdk.naming.dns --jlink-options '--strip-debug --no-header-files --no-man-pages'
/usr/libexec/PlistBuddy -c 'Set :LSMinimumSystemVersion 15.0' "$OUT/hradar.app/Contents/Info.plist" || /usr/libexec/PlistBuddy -c 'Add :LSMinimumSystemVersion string 15.0' "$OUT/hradar.app/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $SHORT_VERSION" "$OUT/hradar.app/Contents/Info.plist"
# Refresh jpackage's ad-hoc resource seal after our metadata changes. This is not Developer ID signing.
codesign --force --sign - "$OUT/hradar.app"
# Explicit optional signing. PRs never receive Apple secrets.
if [[ "${HRADAR_SIGN:-false}" = true ]]; then
  bash scripts/sign-macos.sh "$OUT/hradar.app"
fi
codesign --verify --deep --strict "$OUT/hradar.app"
python3 -B scripts/verify-macos-image.py "$OUT/hradar.app" "$ARCH"
DMG="$OUT/hradar-$VERSION-$ARCH.dmg"
bash scripts/create-macos-dmg.sh "$OUT/hradar.app" "$DMG"
if [[ "${HRADAR_SIGN:-false}" = true ]]; then
  codesign --force --timestamp --sign "$APPLE_SIGNING_IDENTITY" "$DMG"
  xcrun notarytool submit "$DMG" --keychain-profile "$APPLE_NOTARY_PROFILE" --wait
  xcrun stapler staple "$DMG"
  xcrun stapler validate "$DMG"
  spctl --assess --type open --context context:primary-signature --verbose "$DMG"
fi
(cd "$OUT"; shasum -a 256 "hradar-$VERSION-$ARCH.dmg" > SHA256SUMS)
# ZIP preserves executable bits/symlinks better than raw upload-artifact of .app.
ditto -c -k --sequesterRsrc --keepParent "$OUT/hradar.app" "$OUT/hradar-$VERSION-$ARCH.app.zip"
(cd "$OUT"; shasum -a 256 "hradar-$VERSION-$ARCH.app.zip" >> SHA256SUMS)
python3 - "$OUT" "$ARCH" "$VERSION" "$SHORT_VERSION" "$MAC_VERSION" <<'PY'
import json,os,pathlib,subprocess,sys
out=pathlib.Path(sys.argv[1])
(out/'distribution.json').write_text(json.dumps({'version':sys.argv[3], 'macShortVersion':sys.argv[4], 'sqliteSchemaVersion':10, 'features':['recruitment-crm','telegram-bot-settings'], 'macBundleVersion':sys.argv[5], 'architecture':sys.argv[2], 'minimumMacOS':'15.0', 'signedAndNotarized':os.environ.get('HRADAR_SIGN')=='true', 'commit':os.environ.get('GITHUB_SHA'), 'run':os.environ.get('GITHUB_RUN_ID'), 'jdk':subprocess.check_output([os.environ['JAVA_HOME']+'/bin/java','--version'],text=True), 'native':json.load(open('.tdlib-build/release/tdlib-artifact.json'))},indent=2)+'\n')
PY
python3 -B scripts/version.py verify-distribution --path "$OUT/distribution.json" --build-number "$MAC_VERSION" --arch "$ARCH"
