#!/usr/bin/env python3
"""Check embedded source identity and required official generated classes."""
import json
import pathlib
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent

def check(path):
    config = json.loads((ROOT / 'config/tdlib.json').read_text())
    with zipfile.ZipFile(path) as jar:
        manifest = jar.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '').replace('\n ', '')
        fields = dict(line.split(': ', 1) for line in manifest.splitlines() if ': ' in line)
        if fields.get('TDLib-Commit') != config['commit']:
            raise ValueError('Java bindings do not match config/tdlib.json')
        for name in ['Client', 'TdApi', 'TdApi$ReplyMarkupInlineKeyboard']:
            if f'org/drinkless/tdlib/{name}.class' not in jar.namelist():
                raise ValueError('Missing official Java binding class: ' + name)
        if any('/example/' in name for name in jar.namelist()):
            raise ValueError('Demonstration classes must not be in the bindings artifact')
    print('Bindings identity and required classes verified:', config['commit'])

if __name__ == '__main__':
    try:
        check(sys.argv[1])
    except (IndexError, ValueError, KeyError, OSError, zipfile.BadZipFile) as exc:
        sys.exit(str(exc))
