#!/usr/bin/env python3
"""Reject missing/default/replaced app icons before distributing the sealed bundle."""
import pathlib
import plistlib
import sys

app = pathlib.Path(sys.argv[1])
expected = pathlib.Path(sys.argv[2])
info = plistlib.loads((app / 'Contents/Info.plist').read_bytes())
name = info['CFBundleIconFile']
assert name == 'hradar.icns', 'Unexpected CFBundleIconFile'
icon = (app / 'Contents/Resources' / name).read_bytes()
assert icon[:4] == b'icns' and int.from_bytes(icon[4:8], 'big') == len(icon), 'Invalid ICNS'
assert icon == expected.read_bytes(), 'Packaged icon differs from the approved generated icon'
print('Packaged CFBundleIconFile and branded ICNS match')
