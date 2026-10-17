#!/usr/bin/env python3
"""Run the hardware-independent Java and JavaScript test suites."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
from toolchain import ROOT, java_home


def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)


def main():
    output = ROOT / 'build/unit'
    output.mkdir(parents=True, exist_ok=True)
    jdk = java_home()
    classes = ['AlbumStore', 'PhotoRules', 'PhotoSizing', 'SleepRules', 'UsbPhotoDisk', 'UploadServer', 'ImportProgress', 'ImportScanner']
    tests = ['AlbumsTest', 'PhotoRulesTest', 'PhotoSizingTest', 'SleepRulesTest', 'UsbPhotoDiskTest', 'UploadServerTest', 'ImportProgressTest']
    run(sys.executable, ROOT / 'tests/make_usb_fixtures.py')
    run(jdk / 'bin/javac', '-encoding', 'UTF-8', '-d', output,
        *[ROOT / 'app/src/main/java/com/portalframe' / (name + '.java') for name in classes],
        *[ROOT / 'tests' / (name + '.java') for name in tests])
    for name in tests:
        run(jdk / 'bin/java', '-cp', output, name)
    node = os.environ.get('STILL_NODE') or shutil.which('node')
    if not node:
        raise SystemExit('Node.js 22 or later is required for JavaScript tests.')
    run(node, ROOT / 'tests/order.cjs')
    run(node, ROOT / 'tests/settings.cjs')


if __name__ == '__main__':
    main()
