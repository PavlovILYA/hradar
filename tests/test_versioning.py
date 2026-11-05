import importlib.util
import contextlib
import hashlib
import io
import os
import re
import runpy
import json
import pathlib
import subprocess
import sys
import zipfile
import tempfile
import unittest
from unittest import mock

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

    def test_macos_workflow_artifact_names_use_shared_full_version(self):
        root = SCRIPT.parents[1]
        workflow = (root / '.github/workflows/macos-distribution.yml').read_text()
        self.assertIn('app_version: ${{ steps.version.outputs.app_version }}', workflow)
        self.assertIn('HRADAR_APP_VERSION=$(python3 -B scripts/version.py get)', workflow)
        self.assertIn('echo "app_version=$HRADAR_APP_VERSION" >> "$GITHUB_OUTPUT"', workflow)
        unsigned = 'hradar-${{ needs.version.outputs.app_version }}-macos-${{ matrix.arch }}-unsigned'
        signed = unsigned[:-len('unsigned')] + 'signed'
        self.assertEqual(re.findall(r'^ +name: (hradar-.*)$', workflow, re.M), [unsigned, unsigned, signed])
        for script in ('package-macos.sh', 'notarize-distribution.sh', 'verify-macos-dmg.sh'):
            source = (root / 'scripts' / script).read_text()
            self.assertIn('hradar-$VERSION-$ARCH.app.zip', source)
            self.assertIn('hradar-$VERSION-$ARCH.dmg', source)
            self.assertIn('hradar.app', source)
        self.assertIn('--name hradar --app-version "$MAC_VERSION"', (root / 'scripts/package-macos.sh').read_text())

    def test_release_promotion_downloads_versioned_signed_artifacts(self):
        root = SCRIPT.parents[1]
        commit = 'a' * 40
        for version in ('0.6.0', '1.2.3-rc.1+build.2'):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as folder:
                downloads = []

                def command(args, **kwargs):
                    if args[:2] == ['git', 'rev-parse']:
                        return commit
                    if args[:2] == ['git', 'show']:
                        return f'appVersion={version}\n'
                    if args[:2] == ['gh', 'api']:
                        return json.dumps({'conclusion': 'success', 'head_sha': commit,
                                           'event': 'workflow_dispatch', 'path': '.github/workflows/macos-distribution.yml'})
                    if args[:3] == ['gh', 'run', 'download']:
                        name = args[args.index('--name') + 1]
                        downloads.append(name)
                        arch = 'arm64' if 'arm64' in name else 'x86_64'
                        self.assertEqual(name, f'hradar-{version}-macos-{arch}-signed')
                        destination = pathlib.Path(args[args.index('--dir') + 1])
                        destination.mkdir()
                        metadata = {'version': version, 'macShortVersion': self.versioning.parse_version(version),
                                    'macBundleVersion': '1.2.3', 'architecture': arch,
                                    'signedAndNotarized': True, 'commit': commit}
                        (destination / 'distribution.json').write_text(json.dumps(metadata))
                        checksums = []
                        for suffix in ('dmg', 'app.zip'):
                            filename = f'hradar-{version}-{arch}.{suffix}'
                            (destination / filename).write_bytes(b'synthetic installer')
                            checksums.append(hashlib.sha256(b'synthetic installer').hexdigest() + '  ' + filename)
                        (destination / 'SHA256SUMS').write_text('\n'.join(checksums) + '\n')
                        return ''
                    if args[:3] == ['gh', 'attestation', 'verify']:
                        return ''
                    self.fail(f'Unexpected promotion command: {args}')

                previous = pathlib.Path.cwd()
                try:
                    os.chdir(folder)
                    with mock.patch.dict(sys.modules, version=self.versioning), \
                         mock.patch.dict(os.environ, HRADAR_TAG='v' + version, HRADAR_RUN='123', GITHUB_REPOSITORY='example/hradar'), \
                         mock.patch('subprocess.check_output', side_effect=command), contextlib.redirect_stdout(io.StringIO()):
                        runpy.run_path(str(root / 'scripts/verify-release-input.py'), run_name='__main__')
                    self.assertEqual(downloads, [f'hradar-{version}-macos-{arch}-signed' for arch in ('arm64', 'x86_64')])
                    for arch in ('arm64', 'x86_64'):
                        self.assertTrue(pathlib.Path(f'.tdlib-build/release-promotion/hradar-{version}-{arch}.dmg').is_file())
                finally:
                    os.chdir(previous)
