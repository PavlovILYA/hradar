import importlib.util
import json
import pathlib
import subprocess
import sys
import zipfile
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'scripts/version.py'


class VersioningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('versioning', SCRIPT)
        cls.versioning = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.versioning)

    def test_semver_and_platform_version(self):
        for invalid in ('01.2.0', '1.2', '1.2.3-01', '1.2.3-'):
            with self.assertRaises(ValueError):
                self.versioning.parse_version(invalid)
        self.assertEqual(self.versioning.parse_version('0.2.0-rc.1+build.2'), '0.2.0')

    def test_local_number_increases_despite_clock_rollback(self):
        with tempfile.TemporaryDirectory() as folder:
            state = pathlib.Path(folder) / 'build-number'
            first = self.versioning.local_build_number(state, minute=123456)
            second = self.versioning.local_build_number(state, minute=123455)
            self.assertGreater(tuple(map(int, second.split('.'))), tuple(map(int, first.split('.'))))

    def test_build_number_limits(self):
        self.assertEqual(self.versioning.format_build_number(0), '1.0.0')
        self.assertEqual(self.versioning.format_build_number(12345), '2.23.45')
        with self.assertRaises(ValueError):
            self.versioning.format_build_number(99990000)

    def test_sync_and_check_detect_drift(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            (root / 'frontend').mkdir()
            (root / 'gradle.properties').write_text('appVersion=0.2.0\n')
            for name in ('package.json', 'package-lock.json'):
                (root / 'frontend' / name).write_text(json.dumps({'version': '0.1.0', 'packages': {'': {'version': '0.1.0'}}}))
            with self.assertRaises(ValueError):
                self.versioning.check_versions(root)
            self.versioning.sync_versions(root)
            self.versioning.check_versions(root)

    def test_distribution_rejects_version_and_build_drift(self):
        data = {'version': '0.2.0', 'macShortVersion': '0.2.0', 'macBundleVersion': '1.2.3', 'architecture': 'arm64'}
        self.versioning.check_distribution(data, '0.2.0', '1.2.3', 'arm64')
        for key in ('version', 'macShortVersion', 'macBundleVersion', 'architecture'):
            with self.assertRaises(ValueError):
                self.versioning.check_distribution(dict(data, **{key: 'wrong'}), '0.2.0', '1.2.3', 'arm64')

    def test_concurrent_local_allocations_are_unique(self):
        with tempfile.TemporaryDirectory() as folder:
            state = pathlib.Path(folder) / 'build-number'
            processes = [subprocess.Popen([sys.executable, str(SCRIPT), 'build-number', '--state', str(state)], stdout=subprocess.PIPE, text=True) for _ in range(8)]
            values = [process.communicate(timeout=10)[0].strip() for process in processes]
            self.assertTrue(all(process.returncode == 0 for process in processes))
            self.assertEqual(len(set(values)), 8)

    def test_jar_resource_rejects_drift(self):
        with tempfile.TemporaryDirectory() as folder:
            jar = pathlib.Path(folder) / 'backend.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('hradar-version.properties', 'appVersion=0.2.0\n')
            self.versioning.check_jar(jar, '0.2.0')
            with self.assertRaises(ValueError):
                self.versioning.check_jar(jar, '0.2.1')

    def test_invalid_platform_build_numbers(self):
        for value in ('0.2.0', '1.2', '10000.1.1', '1.100.1', '1.1.100', '1.02.3', '1.2.3-rc.1'):
            with self.assertRaises(ValueError):
                self.versioning.validate_build_number(value)

    def test_gradle_override_is_rejected_by_gate(self):
        result = subprocess.run([sys.executable, str(SCRIPT), 'check', '--expected-version', '999.0.0'], text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('differs from canonical', result.stderr)
