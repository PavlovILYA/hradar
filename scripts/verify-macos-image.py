#!/usr/bin/env python3
"""Mach-O closure and bundled JVM/JNI startup; never creates an account client."""
import pathlib, subprocess, sys, os, plistlib
from version import app_version, parse_version, validate_build_number, check_jar
app = pathlib.Path(sys.argv[1]).resolve()
arch = sys.argv[2]
assert arch in ('arm64', 'x86_64')
version = app_version()
info = plistlib.loads((app / 'Contents/Info.plist').read_bytes())
assert info['CFBundleShortVersionString'] == parse_version(version), 'Packaged short version mismatch'
validate_build_number(info['CFBundleVersion'])
if os.environ.get('HRADAR_BUILD_NUMBER'):
    assert info['CFBundleVersion'] == os.environ['HRADAR_BUILD_NUMBER'], 'Packaged build number mismatch'
check_jar(app / 'Contents/app' / f'backend-{version}.jar', version)

for path in app.rglob('*'):
    if not path.is_file() or path.is_symlink():
        continue
    kind = subprocess.check_output(['/usr/bin/file', '-b', str(path)], text=True)
    if 'Mach-O' not in kind:
        continue
    subprocess.run(['/usr/bin/lipo', str(path), '-verify_arch', arch], check=True)
    linked = subprocess.check_output(['/usr/bin/otool', '-L', str(path)], text=True)
    for line in linked.splitlines()[1:]:
        dep = line.strip().split(' (')[0]
        if dep.startswith('/') and not dep.startswith(('/usr/lib/', '/System/Library/')):
            raise SystemExit('Unbundled dependency: '+str(path)+' -> '+dep)
    minimum = subprocess.check_output(['/usr/bin/otool','-l',str(path)],text=True)
    # Reject native dependencies that silently demand an OS newer than our contract.
    lines = minimum.splitlines()
    for i, line in enumerate(lines):
        if line.strip().startswith(('minos ', 'version ')) and i and any('LC_BUILD_VERSION' in s or 'LC_VERSION_MIN_MACOSX' in s for s in lines[max(0,i-4):i]):
            ver = tuple(int(x) for x in line.split()[1].split('.'))
            if ver > (15,0,0): raise SystemExit('Mach-O requires newer macOS: '+str(path)+' '+str(ver))
java = app / 'Contents/runtime/Contents/Home/bin/java'
# jpackage's jlink runtime can omit the java launcher; use the actual packaged launcher.
launcher = app / 'Contents/MacOS/hradar'
env = dict(os.environ, PATH='/usr/bin:/bin:/usr/sbin:/sbin', JAVA_HOME='', NODE_PATH='')
env['JAVA_TOOL_OPTIONS'] = '-Dhradar.jniSmoke=true'
subprocess.run([str(launcher)], env=env, check=True, timeout=45)
print('All Mach-O architectures/linkage and packaged JVM/JNI passed')
