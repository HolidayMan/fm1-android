#!/usr/bin/env python3
"""Explicit developer-only preparation of pinned public engines and packages."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / 'Core/native'


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    commands = [
        ('prepare.py', 'Research/NativeSloop/upstream-sloop-2.7.1'),
        ('prepare_family.py', 'Research/NativeFamily'),
        ('official_preview_prepare.py', 'Research/NativeOfficial/upstream-fm1-open'),
        ('skiff_prepare.py', 'Research/NativeSkiff/upstream-skiff'),
        ('expansion_prepare.py', 'Research/NativeExpansion'),
    ]
    for tool, destination in commands:
        subprocess.run([sys.executable, str(NATIVE / tool), str(ROOT / destination)], check=True)
    for item in json.loads((NATIVE / 'packages.lock.json').read_text())['packages']:
        path = ROOT / item['path']
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            request = urllib.request.Request(item['url'], headers={'User-Agent': 'FM1-source-prepare/1'})
            with urllib.request.urlopen(request, timeout=60) as response:
                data = response.read(4 * 1024 * 1024 + 1)
            if len(data) != item['size'] or hashlib.sha256(data).hexdigest() != item['sha256']:
                raise ValueError('Downloaded package failed exact size/SHA256: ' + item['id'])
            temporary = path.with_suffix(path.suffix + '.download')
            temporary.write_bytes(data)
            temporary.replace(path)
        data = path.read_bytes()
        if len(data) != item['size'] or hashlib.sha256(data).hexdigest() != item['sha256']:
            raise ValueError('Existing package differs; kept without overwrite: ' + str(path))
        print('Verified exact package: ' + item['name'] + ' ' + item['version'])


if __name__ == '__main__':
    main()
