"""Native icon regression; no application launch or Telegram account."""
import pathlib
import plistlib
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]


@unittest.skipUnless(sys.platform == 'darwin', 'sips/iconutil require macOS')
class MacOSIconTest(unittest.TestCase):
    def test_generated_sizes_alpha_and_packaged_resource(self):
        with tempfile.TemporaryDirectory(prefix='hradar icon ') as folder:
            root = pathlib.Path(folder)
            icon = root / 'hradar.icns'
            subprocess.run(['bash', str(ROOT / 'scripts/generate-macos-icon.sh'), str(icon)], check=True)
            first = icon.read_bytes()
            subprocess.run(['bash', str(ROOT / 'scripts/generate-macos-icon.sh'), str(icon)], check=True)
            self.assertEqual(first, icon.read_bytes(), 'Generation must be reproducible')
            extracted = root / 'decoded.iconset'
            subprocess.run(['iconutil', '-c', 'iconset', str(icon), '-o', str(extracted)], check=True)
            expected = {}
            for size in (16, 32, 128, 256, 512):
                expected[f'icon_{size}x{size}.png'] = size
                expected[f'icon_{size}x{size}@2x.png'] = size * 2
            self.assertEqual(set(expected), {p.name for p in extracted.iterdir()})
            for name, size in expected.items():
                info = subprocess.check_output(['sips', '-g', 'pixelWidth', '-g', 'pixelHeight', '-g', 'hasAlpha', str(extracted / name)], text=True)
                self.assertIn(f'pixelWidth: {size}', info)
                self.assertIn(f'pixelHeight: {size}', info)
                self.assertIn('hasAlpha: yes', info)
            # Exercise the production bundle check, including replacement by a default icon.
            app = root / 'hradar.app'
            resources = app / 'Contents/Resources'
            resources.mkdir(parents=True)
            plist = app / 'Contents/Info.plist'
            plist.write_bytes(plistlib.dumps({'CFBundleIconFile': 'hradar.icns'}))
            bundled = resources / 'hradar.icns'
            bundled.write_bytes(first)
            script = ROOT / 'scripts/verify-macos-icon.py'
            def verify():
                with patch.object(sys, 'argv', [str(script), str(app), str(icon)]):
                    runpy.run_path(str(script), run_name='__main__')
            verify()
            bundled.write_bytes(b'default Java icon')
            with self.assertRaises(AssertionError):
                verify()
            bundled.write_bytes(first)
            plist.write_bytes(plistlib.dumps({'CFBundleIconFile': 'missing.icns'}))
            with self.assertRaises((AssertionError, FileNotFoundError)):
                verify()
