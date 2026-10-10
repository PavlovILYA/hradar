import hashlib
import importlib.util
import io
import json
import pathlib
import tempfile
import unittest
import zipfile
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]

def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / (name + '.py'))
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result

class TdlibBuildTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name); (self.root / 'config').mkdir()
        self.pin = 'b' * 40
        self.config = {'commit': self.pin, 'repository': 'PavlovILYA/hradar', 'releaseTag': 'tdlib-' + self.pin + '-v1', 'jarSha256': None, 'buildSourceDigest': None}
        self.write_config()
    def write_config(self):
        (self.root / 'config/tdlib.json').write_text(json.dumps(self.config))
    def jar(self, commit=None, examples=False):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as z:
            # Exercise standard JAR manifest folding at 72-byte lines.
            z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nTDLib-Commit: ' + (commit or self.pin)[:25] + '\r\n ' + (commit or self.pin)[25:] + '\r\n')
            for c in ['Client', 'TdApi', 'TdApi$ReplyMarkupInlineKeyboard']:
                z.writestr('org/drinkless/tdlib/' + c + '.class', b'synthetic test bytecode')
            if examples: z.writestr('org/drinkless/tdlib/example/Example.class', b'synthetic')
        return buffer.getvalue()
    def test_patch_is_minimal_idempotent_and_keeps_api_generation(self):
        source = self.root / 'example/java'; source.mkdir(parents=True)
        content = b'add_custom_target(td_generate_java_api)\nCOMMAND ${Java_JAVAC_EXECUTABLE} -d "${JAVA_OUTPUT_DIRECTORY}" ${JAVA_EXAMPLE_FILES} ${JAVA_SOURCE_FILES}\nadd_dependencies(tdjni td_generate_java_api build_java generate_javadoc)\n'
        path = source / 'CMakeLists.txt'; path.write_bytes(content)
        self.config['javaCmakeSha256'] = hashlib.sha256(content).hexdigest(); self.write_config()
        m = module('prepare-tdlib-java'); m.ROOT = self.root
        m.prepare(self.root); m.prepare(self.root)
        self.assertEqual(content.replace(b'${JAVA_EXAMPLE_FILES} ', b''), path.read_bytes())
    def test_unexpected_upstream_or_post_patch_edits_fail_closed(self):
        source = self.root / 'example/java'; source.mkdir(parents=True)
        path = source / 'CMakeLists.txt'; path.write_text('unexpected upstream')
        self.config['javaCmakeSha256'] = 'a' * 64; self.write_config()
        m = module('prepare-tdlib-java'); m.ROOT = self.root
        with self.assertRaises(ValueError): m.prepare(self.root)
        self.assertEqual('unexpected upstream', path.read_text())
    def test_bindings_identity_and_demo_exclusion(self):
        m = module('check-tdlib-bindings'); m.ROOT = self.root
        path = self.root / 'test.jar'; path.write_bytes(self.jar()); m.check(path)
        path.write_bytes(self.jar(commit='c' * 40))
        with self.assertRaises(ValueError): m.check(path)
        path.write_bytes(self.jar(examples=True))
        with self.assertRaises(ValueError): m.check(path)
    def test_bootstrap_never_downloads_an_unpinned_dependency(self):
        m = module('fetch-tdlib-java'); m.ROOT = self.root
        with patch.object(m.subprocess, 'run') as execute:
            with self.assertRaisesRegex(ValueError, 'bootstrap'): m.fetch()
            execute.assert_not_called()
    def test_hash_mismatch_is_rejected_before_attestation_or_install(self):
        m = module('fetch-tdlib-java'); m.ROOT = self.root
        self.config.update(jarSha256='a' * 64, buildSourceDigest='d' * 40); self.write_config()
        def download(command, **kwargs): pathlib.Path(command[command.index('--output') + 1]).write_bytes(b'tampered')
        with patch.object(m.subprocess, 'run', side_effect=download) as execute:
            with self.assertRaisesRegex(ValueError, 'checksum'): m.fetch()
            self.assertEqual(1, execute.call_count)
            self.assertFalse((self.root / 'backend/libs/tdlib-java.jar').exists())
    def test_only_attested_matching_bindings_are_installed(self):
        m = module('fetch-tdlib-java'); m.ROOT = self.root
        (self.root / 'scripts').mkdir()
        (self.root / 'scripts/check-tdlib-bindings.py').write_bytes((ROOT / 'scripts/check-tdlib-bindings.py').read_bytes())
        content = self.jar(); self.config.update(jarSha256=hashlib.sha256(content).hexdigest(), buildSourceDigest='d' * 40); self.write_config()
        def execute(command, **kwargs):
            if command[0] == 'curl': pathlib.Path(command[command.index('--output') + 1]).write_bytes(content)
            else:
                self.assertEqual('gh', command[0]); self.assertIn('--deny-self-hosted-runners', command)
                self.assertIn('refs/heads/master', command); self.assertIn('d' * 40, command)
        with patch.object(m.subprocess, 'run', side_effect=execute): m.fetch()
        self.assertEqual(content, (self.root / 'backend/libs/tdlib-java.jar').read_bytes())
    def test_failed_provenance_verification_does_not_install(self):
        m = module('fetch-tdlib-java'); m.ROOT = self.root
        content = self.jar(); self.config.update(jarSha256=hashlib.sha256(content).hexdigest(), buildSourceDigest='d' * 40); self.write_config()
        def execute(command, **kwargs):
            if command[0] == 'curl': pathlib.Path(command[command.index('--output') + 1]).write_bytes(content)
            else: raise m.subprocess.CalledProcessError(1, command)
        with patch.object(m.subprocess, 'run', side_effect=execute):
            with self.assertRaises(m.subprocess.CalledProcessError): m.fetch()
        self.assertFalse((self.root / 'backend/libs/tdlib-java.jar').exists())
