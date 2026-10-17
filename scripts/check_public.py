#!/usr/bin/env python3
"""Check the Git index for device data, signing keys, personal paths, and obvious secrets."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REVIEWED_MEDIA = {
    'docs/media/samples/coast.jpg',
    'docs/media/samples/mountains.jpg',
    'docs/media/samples/people.jpg',
    'docs/media/slideshow-landscape.png',
    'docs/media/slideshow-portrait.png',
    'docs/media/albums.png',
    'docs/media/still-demo.gif',
}
BLOCKED_SUFFIXES = {'.apk', '.aab', '.dex', '.class', '.keystore', '.jks', '.key', '.pem', '.p12', '.jpg', '.jpeg', '.heic', '.heif', '.gif', '.png', '.mp3', '.mp4', '.tar', '.tgz', '.zip', '.img', '.bin'}
PATTERNS = [
    re.compile(rb'/' + rb'Users/' + rb'[^/\s]+/'),
    re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    re.compile(rb'gh[pousr]_[A-Za-z0-9]{30,}'),
    re.compile(rb'github_pat_[A-Za-z0-9_]{30,}'),
    re.compile(rb'AIza[A-Za-z0-9_-]{35}'),
]


def main():
    files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    problems = []
    media_hashes = {}
    if REVIEWED_MEDIA.intersection(files):
        media_hashes = json.loads(subprocess.check_output(
            ['git', 'show', ':docs/media/manifest.json'], cwd=ROOT))['files']
        if set(media_hashes) != REVIEWED_MEDIA:
            raise SystemExit('Demo media manifest must match the reviewed filenames.')
    for name in filter(None, files):
        path = Path(name)
        if name in REVIEWED_MEDIA:
            data = subprocess.check_output(['git', 'show', ':' + name], cwd=ROOT)
            signature = {'.jpg': b'\xff\xd8\xff', '.png': b'\x89PNG\r\n\x1a\n', '.gif': b'GIF89a'}[path.suffix]
            if (not data.startswith(signature) or len(data) > 8000000
                    or hashlib.sha256(data).hexdigest() != media_hashes[name]):
                problems.append(name + ': demo media signature, size, or reviewed hash mismatch')
            continue
        if path.parts[0] in {'.tools', 'build', 'node_modules'} or path.name.startswith('.env') or path.suffix.lower() in BLOCKED_SUFFIXES:
            problems.append(name + ': generated, private, or binary data')
            continue
        data = subprocess.check_output(['git', 'show', ':' + name], cwd=ROOT)
        if any(pattern.search(data) for pattern in PATTERNS):
            problems.append(name + ': personal path or secret-like content')
    if problems:
        raise SystemExit('\n'.join(problems))
    print('Public-source checks passed for', sum(bool(name) for name in files), 'indexed files')


if __name__ == '__main__':
    main()
