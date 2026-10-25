#!/usr/bin/env python3
import hashlib,json,os,pathlib,re,subprocess
from version import parse_version, check_distribution

def gh(*args): return subprocess.check_output(['gh',*args],text=True)
tag=os.environ['HRADAR_TAG']; run=os.environ['HRADAR_RUN']; repo=os.environ['GITHUB_REPOSITORY']
assert tag.startswith('v') and run.isdigit(), 'Invalid tag/run'
parse_version(tag[1:])
commit=subprocess.check_output(['git','rev-parse',tag+'^{commit}'],text=True).strip()
tag_properties=subprocess.check_output(['git','show',commit+':gradle.properties'],text=True)
assert re.findall(r'^appVersion=(\S+)$',tag_properties,re.M)==[tag[1:]], 'Tag must match canonical version at its commit'
metadata=json.loads(gh('api',f'repos/{repo}/actions/runs/{run}'))
assert metadata['conclusion']=='success' and metadata['head_sha']==commit and metadata['event']=='workflow_dispatch'
assert metadata['path']=='.github/workflows/macos-distribution.yml'
output=pathlib.Path('.tdlib-build/release-promotion'); output.mkdir(parents=True)
build_number=None
for arch in ['arm64','x86_64']:
    folder=output/arch
    gh('run','download',run,'--repo',repo,'--name',f'hradar-macos-{arch}-signed','--dir',str(folder))
    m=json.loads((folder/'distribution.json').read_text())
    assert m['signedAndNotarized'] and m['commit']==commit and m['architecture']==arch
    check_distribution(m, tag[1:], build_number, arch)
    build_number=m['macBundleVersion']
    for line in (folder/'SHA256SUMS').read_text().splitlines():
        digest,name=line.split('  ',1)
        assert re.fullmatch(re.escape('hradar-'+tag[1:]+'-'+arch)+r'\.(dmg|app\.zip)',name)
        asset=folder/name
        assert hashlib.sha256(asset.read_bytes()).hexdigest()==digest
        gh('attestation','verify',str(asset),'--repo',repo,'--signer-workflow',repo+'/.github/workflows/macos-distribution.yml','--source-digest',commit,'--deny-self-hosted-runners')
        asset.rename(output/name)
    (folder/'SHA256SUMS').rename(output/f'SHA256SUMS-{arch}')
    (folder/'distribution.json').rename(output/f'distribution-{arch}.json')
    folder.rmdir()
print('Both signed installers match the reviewed existing tag and provenance')
