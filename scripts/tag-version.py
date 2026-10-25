#!/usr/bin/env python3
"""Idempotent annotated tag for an already verified master SHA; no release API."""
import json
import os
import pathlib
import re
import subprocess
import urllib.error
import urllib.request
from version import app_version, parse_version


class GitHub:
    def __init__(self, repository, token):
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
            raise ValueError('Invalid repository')
        self.root = f'https://api.github.com/repos/{repository}'
        self.token = token

    def call(self, method, path, data=None):
        request = urllib.request.Request(self.root + path,
            data=json.dumps(data).encode() if data is not None else None, method=method,
            headers={'Authorization': f'Bearer {self.token}', 'Accept': 'application/vnd.github+json',
                     'Content-Type': 'application/json', 'X-GitHub-Api-Version': '2022-11-28'})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 404 and method == 'GET' and path.startswith('/git/ref/tags/'):
                return None
            # Never serialize API response bodies, authorization headers or raw errors.
            raise RuntimeError(f'GitHub API request failed (HTTP {error.code})') from None
        except OSError:
            raise RuntimeError('GitHub API network failure') from None


def existing_commit(api, tag):
    ref = api.call('GET', f'/git/ref/tags/{tag}')
    if ref is None:
        return None
    obj = ref['object']
    for _ in range(5):
        if obj['type'] == 'commit':
            return obj['sha']
        if obj['type'] != 'tag':
            break
        obj = api.call('GET', f"/git/tags/{obj['sha']}")['object']
    raise RuntimeError('Existing version tag does not resolve to a commit')


def tag_version(api, repository, sha, version, previous):
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Expected exact verified commit SHA')
    parse_version(version)
    # Only stable versions get automatic tags. Prerelease promotion remains explicit.
    if version != parse_version(version):
        return 'Prerelease/build metadata; no automatic stable tag'
    prs = api.call('GET', f'/commits/{sha}/pulls?per_page=100')
    merged = next((pr for pr in prs if pr.get('merged_at') and pr.get('merge_commit_sha') == sha and
                   pr.get('base', {}).get('ref') == 'master' and
                   pr.get('base', {}).get('repo', {}).get('full_name') == repository), None)
    if merged is None:
        return 'Commit is not a merged PR into master; no tag created'
    previous = previous(merged) if callable(previous) else previous
    if version == previous:
        return 'Version unchanged; no tag created'
    tag = f'v{version}'
    existing = existing_commit(api, tag)
    if existing:
        if existing != sha:
            raise RuntimeError('Version tag already points to another commit; refusing overwrite')
        return 'Version tag already points to verified commit'
    obj = api.call('POST', '/git/tags', {'tag': tag, 'message': f'hradar {version}', 'object': sha, 'type': 'commit'})
    try:
        api.call('POST', '/git/refs', {'ref': f'refs/tags/{tag}', 'sha': obj['sha']})
    except RuntimeError:
        # A concurrent runner may have created the same ref. Never force-update it.
        if existing_commit(api, tag) != sha:
            raise RuntimeError('Could not create version tag safely; refusing overwrite') from None
    return f'Annotated {tag} points to verified master commit'


def main():
    if os.environ.get('GITHUB_EVENT_NAME') != 'push' or os.environ.get('GITHUB_REF') != 'refs/heads/master':
        raise RuntimeError('Automatic tags require a master push')
    sha = os.environ['GITHUB_SHA']
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Invalid verified SHA')
    if subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() != sha:
        raise RuntimeError('Checkout differs from verified SHA')
    def previous_version(pr):
        # Use the triggering push's immutable ref transition, not live master or
        # PR base.sha (which can predate another version-changing merge).
        event = json.loads(pathlib.Path(os.environ['GITHUB_EVENT_PATH']).read_text())
        if event.get('after') != sha or event.get('ref') != 'refs/heads/master' or event.get('deleted'):
            raise RuntimeError('Push payload differs from verified master transition')
        base = event.get('before', '')
        if not re.fullmatch(r'[0-9a-f]{40}', base) or base in (sha, '0' * 40):
            raise RuntimeError('Cannot determine previous master SHA')
        subprocess.run(['git', 'merge-base', '--is-ancestor', base, sha], check=True)
        previous_file = subprocess.check_output(['git', 'show', f'{base}:gradle.properties'], text=True)
        previous = re.findall(r'^appVersion=(\S+)\s*$', previous_file, re.M)
        if len(previous) != 1:
            raise RuntimeError('Cannot determine previous application version')
        parse_version(previous[0])
        return previous[0]
    print(tag_version(GitHub(os.environ['GITHUB_REPOSITORY'], os.environ['GH_TOKEN']),
                      os.environ['GITHUB_REPOSITORY'], sha, app_version(), previous_version))



if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, KeyError, subprocess.SubprocessError):
        raise SystemExit('Automatic version tag failed safely; inspect version/tag policy and API permissions. No tag was overwritten.')
