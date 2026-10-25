#!/bin/bash
# Validate the actual installer contents, including execution from the mounted volume.
set -euo pipefail
ARCH="${1:?architecture required}"
OUT="${2:?installer output directory required}"
OUT="$(cd "$OUT" && pwd)"
VERSION="$(python3 -B scripts/version.py get)"
python3 -B scripts/version.py verify-distribution --path "$OUT/distribution.json" --arch "$ARCH" --build-number "${HRADAR_BUILD_NUMBER:-}"
DMG="$OUT/hradar-$VERSION-$ARCH.dmg"
MOUNT="$(mktemp -d "${TMPDIR:-/tmp}/hradar-installer.XXXXXX")"
cleanup() { hdiutil detach "$MOUNT" >/dev/null 2>&1 || true; rmdir "$MOUNT" || true; }
trap cleanup EXIT
hdiutil attach -readonly -nobrowse -noautoopen -mountpoint "$MOUNT" "$DMG"
python3 - "$MOUNT/hradar.app" "$OUT/hradar-$VERSION-$ARCH.app.zip" "$OUT/distribution.json" <<'PY'
import pathlib,plistlib,sys,zipfile,hashlib,json
app=pathlib.Path(sys.argv[1]); count=0
with zipfile.ZipFile(sys.argv[2]) as archive:
    for file in app.rglob('*'):
        if file.is_file() and not file.is_symlink():
            name='hradar.app/'+str(file.relative_to(app))
            assert hashlib.sha256(file.read_bytes()).digest()==hashlib.sha256(archive.read(name)).digest(),name
            count+=1
info=plistlib.loads((app/'Contents/Info.plist').read_bytes())
metadata=json.loads(pathlib.Path(sys.argv[3]).read_text())
assert info['LSMinimumSystemVersion']=='15.0'
assert info['CFBundleShortVersionString']==metadata['macShortVersion']
assert info['CFBundleVersion']==metadata['macBundleVersion']
print('DMG app and ZIP match byte-for-byte:',count,'files; macOS 15.0 minimum')
PY
codesign --verify --deep --strict "$MOUNT/hradar.app"
python3 -B scripts/verify-macos-image.py "$MOUNT/hradar.app" "$ARCH"
python3 -B scripts/smoke-desktop.py "$MOUNT/hradar.app" "${3:-8080}"
