"""Prepare pinned runtime payloads. Only Python's standard library is required."""
import concurrent.futures
import hashlib
import json
import lzma
from pathlib import Path
import shutil
import sys
import time
import urllib.request
import zipfile

root = Path(__file__).resolve().parent
output, runtime_apk, cache = map(Path, sys.argv[1:4])
lock = json.loads((root / 'payload-lock.json').read_text(encoding='utf-8'))
cache.mkdir(parents=True, exist_ok=True)
(output / 'speed-runtime' / 'gadget').mkdir(parents=True, exist_ok=True)

def prepare(item):
    abi, asset = item
    archive = cache / asset['name']
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != asset['sha256']:
        url = f"https://github.com/frida/frida/releases/download/{lock['version']}/{asset['name']}"
        temporary = archive.with_suffix('.part')
        request = urllib.request.Request(url, headers={'User-Agent': 'assistant-speed-build'})
        for attempt in range(3):
            try:
                with urllib.request.urlopen(request, timeout=60) as response, temporary.open('wb') as target:
                    shutil.copyfileobj(response, target)
                break
            except (OSError, urllib.error.URLError):
                if attempt == 2:
                    raise
                time.sleep(2 ** attempt)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != asset['sha256']:
            temporary.unlink()
            raise RuntimeError(f'Checksum mismatch: {asset["name"]}')
        temporary.replace(archive)
    with lzma.open(archive) as source, (output / 'speed-runtime' / 'gadget' / f'{abi}.so').open('wb') as target:
        shutil.copyfileobj(source, target)
    print(f'Prepared {abi}', flush=True)

with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    list(pool.map(prepare, lock['assets'].items()))
with zipfile.ZipFile(runtime_apk) as apk:
    (output / 'speed-runtime' / 'runtime.dex').write_bytes(apk.read('classes.dex'))
shutil.copyfile(root / 'THIRD_PARTY_NOTICES.md', output / 'speed-runtime' / 'THIRD_PARTY_NOTICES.md')
licenses = output / 'speed-runtime' / 'licenses'
licenses.mkdir(parents=True, exist_ok=True)
for name in ['FRIDA-COPYING.txt', 'LGPL-2.0.txt']:
    shutil.copyfile(root / name, licenses / name)
for package in ['buffer', 'base64-js', 'ieee754']:
    shutil.copyfile(root / 'node_modules' / package / 'LICENSE', licenses / f'{package}-LICENSE.txt')
