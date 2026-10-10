#!/usr/bin/env python3
"""Fetch only the reviewed, SHA-pinned JAR from our permanent GitHub Release."""
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile
import importlib.util

ROOT = pathlib.Path(__file__).resolve().parent.parent

def fetch():
    config = json.loads((ROOT / 'config/tdlib.json').read_text())
    for key in ['jarSha256', 'buildSourceDigest']:
        expected = config.get(key)
        if not isinstance(expected, str) or not re.fullmatch('[0-9a-f]{' + ('64' if key == 'jarSha256' else '40') + '}', expected):
            raise ValueError('TDLib bootstrap required: run macos-arm64.yml on master, publish verified Release, then pin jarSha256/buildSourceDigest in config/tdlib.json. See docs/CI.md')
    if config['repository'] != 'PavlovILYA/hradar' or not re.fullmatch('tdlib-' + config['commit'] + r'-v[0-9]+', config['releaseTag']):
        raise ValueError('Unexpected binary release origin/tag')
    with tempfile.TemporaryDirectory(prefix='hradar-bindings-') as directory:
        target = pathlib.Path(directory) / 'tdlib-java.jar'
        url = 'https://github.com/' + config['repository'] + '/releases/download/' + config['releaseTag'] + '/tdlib-java.jar'
        subprocess.run(['curl', '--fail', '--location', '--proto', '=https', '--proto-redir', '=https', '--retry', '2', '--max-time', '120', '--output', str(target), url], check=True)
        if hashlib.sha256(target.read_bytes()).hexdigest() != config['jarSha256']:
            raise ValueError('TDLib JAR checksum mismatch; artifact not installed')
        subprocess.run(['gh', 'attestation', 'verify', str(target), '--repo', config['repository'],
                        '--signer-workflow', config['repository'] + '/.github/workflows/macos-arm64.yml',
                        '--source-ref', 'refs/heads/master', '--source-digest', config['buildSourceDigest'],
                        '--deny-self-hosted-runners'], check=True)
        spec = importlib.util.spec_from_file_location('bindings_check', ROOT / 'scripts/check-tdlib-bindings.py')
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); module.check(target)
        destination = ROOT / 'backend/libs/tdlib-java.jar'
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix('.jar.tmp')
        temporary.write_bytes(target.read_bytes()); temporary.replace(destination)
        print('Installed verified Java-only dependency; no JNI downloaded')

if __name__ == '__main__':
    try:
        fetch()
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        raise SystemExit(str(exc))
