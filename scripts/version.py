#!/usr/bin/env python3
"""Canonical app metadata and Apple-compatible build counters; Python stdlib only."""
import argparse
import fcntl
import json
import os
import pathlib
import re
import time
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
NUMBER = r'(?:0|[1-9][0-9]*)'
IDENTIFIER = r'(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)'
SEMVER = re.compile(rf'({NUMBER}\.{NUMBER}\.{NUMBER})(?:-{IDENTIFIER}(?:\.{IDENTIFIER})*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?')


def parse_version(version):
    match = SEMVER.fullmatch(version)
    if not match:
        raise ValueError('appVersion must be valid SemVer')
    return match[1]


def app_version(root=ROOT):
    matches = re.findall(r'^appVersion=(\S+)\s*$', (root / 'gradle.properties').read_text(), re.M)
    if len(matches) != 1:
        raise ValueError('gradle.properties must contain exactly one appVersion')
    parse_version(matches[0])
    return matches[0]


def sync_versions(root=ROOT):
    version = app_version(root)
    for name in ('package.json', 'package-lock.json'):
        path = root / 'frontend' / name
        data = json.loads(path.read_text())
        data['version'] = version
        if name == 'package-lock.json':
            data['packages']['']['version'] = version
        path.write_text(json.dumps(data, indent=2) + '\n')


def check_versions(root=ROOT):
    version = app_version(root)
    for name in ('package.json', 'package-lock.json'):
        data = json.loads((root / 'frontend' / name).read_text())
        if data['version'] != version or (name == 'package-lock.json' and data['packages']['']['version'] != version):
            raise ValueError(f'{name} differs from appVersion; run python3 scripts/version.py sync')


def format_build_number(index):
    if not 0 <= index < 99990000:
        raise ValueError('Build counter exceeds Apple 4.2.2 digit limits')
    return f'{index // 10000 + 1}.{index // 100 % 100}.{index % 100}'


def validate_build_number(value):
    if not re.fullmatch(r'[1-9][0-9]{0,3}\.(?:0|[1-9][0-9]?)\.(?:0|[1-9][0-9]?)', value):
        raise ValueError('Build number must have Apple-compatible positive 4.2.2 components')
    return value


def local_build_number(state, minute=None):
    # One durable allocation per package invocation, including concurrent callers.
    state.parent.mkdir(parents=True, exist_ok=True)
    with state.open('a+') as stream:
        fcntl.flock(stream, fcntl.LOCK_EX)
        stream.seek(0)
        previous = stream.read().strip()
        current = int((time.time() - 1577836800) // 60) if minute is None else minute
        index = max(current, int(previous) + 1 if previous else 0)
        value = format_build_number(index)
        stream.seek(0)
        stream.truncate()
        stream.write(str(index))
        stream.flush()
        os.fsync(stream.fileno())
        return value


def check_distribution(data, version, build=None, arch=None):
    if data['version'] != version or data['macShortVersion'] != parse_version(version):
        raise ValueError('Distribution app version differs from canonical appVersion')
    validate_build_number(data['macBundleVersion'])
    if build and data['macBundleVersion'] != validate_build_number(build):
        raise ValueError('Distribution build number differs from generated build number')
    if arch and data['architecture'] != arch:
        raise ValueError('Distribution architecture mismatch')


def check_jar(path, version):
    with zipfile.ZipFile(path) as archive:
        if archive.read('hradar-version.properties').decode().strip() != 'appVersion=' + version:
            raise ValueError('JAR version resource differs from canonical appVersion')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('check', 'sync', 'get', 'build-number', 'verify-distribution', 'verify-jar', 'validate-build-number'))
    parser.add_argument('--field', choices=('appVersion', 'shortVersion', 'jarName'), default='appVersion')
    parser.add_argument('--state', type=pathlib.Path, default=ROOT / '.tdlib-build/build-number')
    parser.add_argument('--path', type=pathlib.Path)
    parser.add_argument('--build-number')
    parser.add_argument('--expected-version')
    parser.add_argument('--arch')
    args = parser.parse_args()
    version = app_version()
    if args.expected_version is not None and args.expected_version != version:
        raise ValueError('Build version differs from canonical appVersion')
    if args.command == 'check':
        check_versions()
    elif args.command == 'sync':
        sync_versions()
    elif args.command == 'get':
        print({'appVersion': version, 'shortVersion': parse_version(version), 'jarName': f'backend-{version}.jar'}[args.field])
    elif args.command == 'build-number':
        print(local_build_number(args.state))
    elif args.command == 'validate-build-number':
        validate_build_number(args.build_number)
    elif args.command == 'verify-jar':
        check_jar(args.path, version)
    else:
        check_distribution(json.loads(args.path.read_text()), version, args.build_number, args.arch)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError) as error:
        raise SystemExit(str(error))
