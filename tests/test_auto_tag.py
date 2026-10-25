import importlib.util
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'scripts'))
spec = importlib.util.spec_from_file_location('auto_tag', pathlib.Path(__file__).resolve().parents[1] / 'scripts/tag-version.py')
auto = importlib.util.module_from_spec(spec)
spec.loader.exec_module(auto)
SHA, OTHER = 'a' * 40, 'b' * 40


class FakeGitHub:
    def __init__(self, existing=None, merged=True, race=False):
        self.existing, self.merged, self.race = existing, merged, race
        self.posts = []

    def call(self, method, path, data=None):
        if method == 'POST':
            self.posts.append((path, data))
            if path == '/git/tags':
                return {'sha': 'c' * 40}
            if path == '/git/refs' and self.race:
                self.existing = SHA
                raise RuntimeError('Conflict synthetic')
            return {}
        if '/pulls' in path:
            return [{'merged_at': 'synthetic', 'merge_commit_sha': SHA if self.merged else OTHER,
                     'base': {'ref': 'master', 'repo': {'full_name': 'example/hradar'}}}]
        if '/git/ref/tags/' in path:
            return {'object': {'type': 'tag', 'sha': 'd' * 40}} if self.existing else None
        if '/git/tags/' in path:
            return {'object': {'type': 'commit', 'sha': self.existing}}
        raise AssertionError('Unexpected fake API request')


class AutoTagTest(unittest.TestCase):
    def run_tag(self, api, version='0.3.0', previous='0.2.0'):
        return auto.tag_version(api, 'example/hradar', SHA, version, previous)

    def test_annotation_targets_exact_verified_merge(self):
        api = FakeGitHub()
        self.run_tag(api)
        self.assertEqual(api.posts, [('/git/tags', {'tag': 'v0.3.0', 'message': 'hradar 0.3.0', 'object': SHA, 'type': 'commit'}),
                                   ('/git/refs', {'ref': 'refs/tags/v0.3.0', 'sha': 'c' * 40})])

    def test_same_sha_noop(self):
        api = FakeGitHub(existing=SHA)
        self.run_tag(api)
        self.assertEqual(api.posts, [])

    def test_conflicting_sha_fails_without_write(self):
        api = FakeGitHub(existing=OTHER)
        with self.assertRaises(RuntimeError):
            self.run_tag(api)
        self.assertEqual(api.posts, [])

    def test_unchanged_docs_version_skips_old_tag(self):
        api = FakeGitHub(existing=OTHER)
        self.run_tag(api, previous='0.3.0')
        self.assertEqual(api.posts, [])

    def test_not_a_merged_pr_or_prerelease_never_writes(self):
        for api, version in [(FakeGitHub(merged=False), '0.3.0'), (FakeGitHub(), '0.3.0-rc.1')]:
            self.run_tag(api, version)
            self.assertEqual(api.posts, [])

    def test_concurrent_same_sha_is_idempotent(self):
        self.run_tag(FakeGitHub(race=True))

    def test_invalid_sha_rejected(self):
        with self.assertRaises(ValueError):
            auto.tag_version(FakeGitHub(), 'example/hradar', 'master', '0.3.0', '0.2.0')

    def test_workflow_gates_permissions_and_existing_checks(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        workflow = (root / '.github/workflows/ci.yml').read_text()
        job = workflow.split('\n  tag-version:', 1)[1]
        self.assertIn('needs: [frontend, backend, installers]', job)
        self.assertIn("github.event_name == 'push' && github.ref == 'refs/heads/master'", job)
        self.assertIn('contents: write', job)
        self.assertIn('ref: ${{ github.sha }}', job)
        self.assertIn('persist-credentials: false', job)
        self.assertEqual(workflow.count('contents: write'), 1)
        self.assertIn('uses: ./.github/workflows/macos-distribution.yml', workflow)

    def test_feature_pr_and_failed_checks_cannot_reach_mutations(self):
        from unittest.mock import patch
        for event, ref in [('pull_request', 'refs/pull/10/merge'), ('push', 'refs/heads/feature'), ('workflow_dispatch', 'refs/heads/master')]:
            with patch.dict(auto.os.environ, {'GITHUB_EVENT_NAME': event, 'GITHUB_REF': ref}, clear=True), patch.object(auto, 'GitHub') as api:
                with self.assertRaises(RuntimeError):
                    auto.main()
                api.assert_not_called()
        workflow = (pathlib.Path(__file__).resolve().parents[1] / '.github/workflows/ci.yml').read_text()
        job = workflow.split('\n  tag-version:', 1)[1]
        self.assertNotIn('always()', job)
        self.assertNotIn('secrets.', job)
        self.assertNotIn('release create', job)

    def test_api_failure_does_not_expose_response_or_token(self):
        from unittest.mock import patch
        from urllib.error import HTTPError
        api = auto.GitHub('example/hradar', 'synthetic-not-a-real-token')
        with patch.object(auto.urllib.request, 'urlopen', side_effect=HTTPError('https://api.github.com', 403, 'synthetic-private-response', {}, None)):
            with self.assertRaises(RuntimeError) as error:
                api.call('POST', '/git/tags', {})
            self.assertEqual(str(error.exception), 'GitHub API request failed (HTTP 403)')

    def test_merged_pr_boundary_callback_used_for_unchanged_version(self):
        api = FakeGitHub(existing=OTHER)
        called = []
        def previous(pr):
            called.append(pr['merge_commit_sha'])
            return '0.3.0'
        self.run_tag(api, previous=previous)
        self.assertEqual(called, [SHA])
        self.assertEqual(api.posts, [])

    def test_main_uses_immutable_push_boundary_ignoring_old_pr_base_for_all_merge_modes(self):
        from unittest.mock import patch
        from types import SimpleNamespace
        base = OTHER
        for parents, expected_base in [(f'{SHA} {base} {"c" * 40}', base), (f'{SHA} {"c" * 40}', base)]:
            checks = []
            class MergedAPI(FakeGitHub):
                def call(self, method, path, data=None):
                    result = super().call(method, path, data)
                    if '/pulls' in path:
                        result[0]['base']['sha'] = 'e' * 40  # Older PR base must be ignored.
                    return result
            api = MergedAPI()
            def output(command, **_):
                checks.append(command)
                if command[1] == 'rev-parse':
                    return SHA
                if command[1] == 'rev-list':
                    return parents
                if command[1] == 'show':
                    return 'appVersion=0.2.0\n'
                raise AssertionError('Unexpected git call')
            env = {'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/heads/master', 'GITHUB_SHA': SHA,
                   'GITHUB_REPOSITORY': 'example/hradar', 'GH_TOKEN': 'synthetic', 'GITHUB_EVENT_PATH': '/synthetic/event.json'}
            with patch.dict(auto.os.environ, env, clear=True), patch.object(auto, 'GitHub', return_value=api), patch.object(auto, 'app_version', return_value='0.3.0'), patch.object(auto.subprocess, 'check_output', side_effect=output), patch.object(auto.subprocess, 'run', return_value=SimpleNamespace(returncode=0)) as run, patch.object(auto.pathlib.Path, 'read_text', return_value=auto.json.dumps({'before': base, 'after': SHA, 'ref': 'refs/heads/master', 'deleted': False})), patch('builtins.print'):
                auto.main()
            self.assertIn(['git', 'show', f'{expected_base}:gradle.properties'], checks)
            run.assert_called_once_with(['git', 'merge-base', '--is-ancestor', base, SHA], check=True)
            self.assertEqual(api.posts[0][1]['object'], SHA)

    def test_concurrent_different_sha_fails_without_overwrite(self):
        class ConflictAPI(FakeGitHub):
            def call(self, method, path, data=None):
                if method == 'POST' and path == '/git/refs':
                    self.existing = OTHER
                    raise RuntimeError('Concurrent synthetic conflict')
                return super().call(method, path, data)
        api = ConflictAPI()
        with self.assertRaises(RuntimeError):
            self.run_tag(api)
        self.assertTrue(all(path != '/git/refs' for path, _ in api.posts))
        self.assertEqual(api.existing, OTHER)
