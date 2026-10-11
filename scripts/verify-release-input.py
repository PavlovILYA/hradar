#!/usr/bin/env python3
import hashlib,json,os,pathlib,re,subprocess

def gh(*args): return subprocess.check_output(['gh',*args],text=True)
tag=os.environ['HRADAR_TAG']; run=os.environ['HRADAR_RUN']; repo=os.environ['GITHUB_REPOSITORY']
assert re.fullmatch(r'v\d+\.\d+\.\d+',tag) and run.isdigit(), 'Invalid tag/run'
commit=subprocess.check_output(['git','rev-parse',tag+'^{commit}'],text=True).strip()
metadata=json.loads(gh('api',f'repos/{repo}/actions/runs/{run}'))
assert metadata['conclusion']=='success' and metadata['head_sha']==commit and metadata['event']=='workflow_dispatch'
assert metadata['path']=='.github/workflows/macos-distribution.yml'
output=pathlib.Path('.tdlib-build/release-promotion'); output.mkdir(parents=True)
for arch in ['arm64','x86_64']:
    folder=output/arch
    gh('run','download',run,'--repo',repo,'--name',f'hradar-macos-{arch}-signed','--dir',str(folder))
    m=json.loads((folder/'distribution.json').read_text())
    assert m['signedAndNotarized'] and m['commit']==commit and m['architecture']==arch
    assert tag=='v'+m['version'], 'Tag must match packaged version'
    for line in (folder/'SHA256SUMS').read_text().splitlines():
        digest,name=line.split('  ',1)
        assert re.fullmatch(r'hradar-[0-9.]+-'+arch+r'\.(dmg|app\.zip)',name)
        asset=folder/name
        assert hashlib.sha256(asset.read_bytes()).hexdigest()==digest
        gh('attestation','verify',str(asset),'--repo',repo,'--signer-workflow',repo+'/.github/workflows/macos-distribution.yml','--source-digest',commit,'--deny-self-hosted-runners')
        asset.rename(output/name)
    (folder/'SHA256SUMS').rename(output/f'SHA256SUMS-{arch}')
    (folder/'distribution.json').rename(output/f'distribution-{arch}.json')
    folder.rmdir()
print('Both signed installers match the reviewed existing tag and provenance')
