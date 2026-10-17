#!/usr/bin/env python3
"""Small, reproducible Android build using the official SDK; no Gradle download."""
import os
import sys
from pathlib import Path
import subprocess
import zipfile
from toolchain import java_home, android_tools

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
SDK = android_tools()
JDK = java_home()
OUT = ROOT / 'build'
ENV = {**os.environ, 'JAVA_HOME': str(JDK), 'PATH': str(JDK / 'bin') + os.pathsep + os.environ['PATH']}

def run(*args):
    subprocess.run([str(x) for x in args], cwd=ROOT, env=ENV, check=True)

def main():
    OUT.mkdir(exist_ok=True)
    classes = OUT / 'classes'
    classes.mkdir(exist_ok=True)
    for old in classes.rglob('*.class'):
        old.unlink()
    bootclasspath = os.pathsep.join(str(SDK[name]) for name in ['android.jar', 'core-lambda-stubs.jar'])
    run(JDK / 'bin/javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath', bootclasspath,
        '-d', classes, *sorted((ROOT / 'app/src/main/java').rglob('*.java')))
    run(SDK['d8'], '--min-api', '28', '--lib', SDK['android.jar'], '--output', OUT, *sorted(classes.rglob('*.class')))
    unsigned = OUT / 'still-unsigned.apk'
    manifest = ROOT / 'app/src/main/AndroidManifest.xml'
    if '--debug' in sys.argv:
        debug_manifest = OUT / 'AndroidManifest.xml'
        debug_manifest.write_text(manifest.read_text().replace('<application ', '<application android:debuggable="true" '))
        manifest = debug_manifest
    run(SDK['aapt'], 'package', '-f', '-M', manifest, '-I', SDK['android.jar'],
        '-A', ROOT / 'app/src/main/assets', '-S', ROOT / 'app/src/main/res', '-F', unsigned)
    with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as apk:
        apk.write(OUT / 'classes.dex', 'classes.dex')
    aligned = OUT / 'still-aligned.apk'
    run(SDK['zipalign'], '-f', '4', unsigned, aligned)
    key = TOOLS / 'still-debug.keystore'
    if not key.exists():
        run(JDK / 'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
            '-alias', 'still', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Still Local Development')
    run(SDK['apksigner'], 'sign', '--ks', key, '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
        '--ks-key-alias', 'still', '--out', OUT / 'still.apk', aligned)
    run(SDK['apksigner'], 'verify', '--verbose', OUT / 'still.apk')
    print(f'Built {OUT / "still.apk"}')

if __name__ == '__main__':
    main()
