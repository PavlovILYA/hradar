#!/usr/bin/env python3
"""Stage verified native build outputs for artifact upload/manual release publication."""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parent.parent
config = json.loads((root / 'config/tdlib.json').read_text())
source = pathlib.Path(os.environ.get('TDLIB_SOURCE_DIR', root / '.tdlib-build/td'))
if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != config['commit']:
    raise SystemExit('Artifact source differs from pinned TDLib commit')
subprocess.run([sys.executable, '-B', str(root / 'scripts/check-tdlib-bindings.py'), str(root / 'backend/libs/tdlib-java.jar')], check=True)
output = root / '.tdlib-build/release'
output.mkdir(parents=True, exist_ok=True)
assets = {}
for origin in ['backend/libs/tdlib-java.jar', 'backend/native/libtdjni.dylib']:
    destination = output / pathlib.Path(origin).name
    shutil.copyfile(root / origin, destination)
    assets[destination.name] = hashlib.sha256(destination.read_bytes()).hexdigest()
metadata = {'tdlibCommit': config['commit'], 'buildSourceDigest': os.environ.get('GITHUB_SHA'),
            'repository': os.environ.get('GITHUB_REPOSITORY'), 'sourceRef': os.environ.get('GITHUB_REF'),
            'runUrl': 'https://github.com/' + os.environ.get('GITHUB_REPOSITORY', '') + '/actions/runs/' + os.environ.get('GITHUB_RUN_ID', ''),
            'portable': os.environ.get('TDLIB_PORTABLE') == 'true',
            'minimumMacOS': os.environ.get('MACOSX_DEPLOYMENT_TARGET'),
            'opensslVersion': '3.5.9' if os.environ.get('TDLIB_PORTABLE') == 'true' else None,
            'opensslSha256': '603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a' if os.environ.get('TDLIB_PORTABLE') == 'true' else None,
            'architecture': subprocess.check_output(['uname', '-m'], text=True).strip(),
            'tdApiSchemaSha256': hashlib.sha256((source / 'td/generate/scheme/td_api.tl').read_bytes()).hexdigest(),
            'javaCmakePatchedSha256': hashlib.sha256((source / 'example/java/CMakeLists.txt').read_bytes()).hexdigest(), 'assets': assets}
(output / 'tdlib-artifact.json').write_text(json.dumps(metadata, indent=2) + '\n')
(output / 'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name, digest in sorted(assets.items())))
print('Verified artifacts staged; release publication remains manual')
