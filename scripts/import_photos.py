#!/usr/bin/env python3
"""Copy photos to the app inbox, then use Photos > your album > On this Portal > Computer import inbox."""
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
EXTENSIONS = {'.jpg', '.jpeg', '.png', '.webp', '.heic', '.heif'}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path)
    parser.add_argument('--adb', default=str(ROOT / '.tools/platform-tools/adb'))
    parser.add_argument('--serial', help='ADB serial when multiple devices are attached')
    args = parser.parse_args()
    if not args.folder.is_dir():
        parser.error('folder must be an existing directory')
    adb = [args.adb] + (['-s', args.serial] if args.serial else [])
    inbox = '/sdcard/Android/data/com.portalframe/files/inbox'
    subprocess.run(adb + ['shell', 'mkdir', '-p', inbox], check=True)
    files = sorted(f for f in args.folder.rglob('*') if f.is_file() and f.suffix.lower() in EXTENSIONS)
    if not files:
        parser.error('No supported images found')
    # Retain subfolders to avoid overwriting photos with the same filename.
    for file in files:
        relative = file.relative_to(args.folder)
        destination = inbox + '/' + relative.as_posix()
        parent = destination.rsplit('/', 1)[0]
        # `adb shell` joins arguments: quote remote paths explicitly.
        import shlex
        subprocess.run(adb + ['shell', 'mkdir -p ' + shlex.quote(parent)], check=True)
        subprocess.run(adb + ['push', str(file), destination], check=True)
    print(f'Copied {len(files)} images to the inbox. On the Portal: Photos > your album > On this Portal > Computer import inbox.')
    print('The app validates photos, keeps only display-size copies, and removes processed inbox originals automatically.')

if __name__ == '__main__':
    main()
