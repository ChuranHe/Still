#!/usr/bin/env python3
"""Install a project-local macOS Android toolchain from Google and Eclipse Adoptium."""
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import tarfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'

def download(url, path):
    print('Downloading', path.name, flush=True)
    subprocess.run(['/usr/bin/curl','-fsSL','--retry','2',url,'-o',str(path)],check=True)

def main():
    if platform.system() != 'Darwin':
        raise SystemExit('This bootstrap supports macOS. Point STILL_ANDROID_TOOLS and STILL_JAVA_HOME at your SDK/JDK on other systems.')
    TOOLS.mkdir(exist_ok=True)
    download('https://dl.google.com/android/repository/repository2-1.xml', TOOLS/'repository.xml')
    repo=ET.parse(TOOLS/'repository.xml').getroot()
    for package in repo.findall('remotePackage'):
        if package.attrib['path'] not in ['platforms;android-32','build-tools;32.0.0','platform-tools']:
            continue
        for archive in package.findall('./archives/archive'):
            if archive.findtext('host-os') not in [None,'macosx']:
                continue
            name=package.attrib['path'].split(';')[0]+'.zip'
            path=TOOLS/name
            download('https://dl.google.com/android/repository/'+archive.findtext('complete/url'),path)
            expected=archive.findtext('complete/checksum')
            if hashlib.sha1(path.read_bytes()).hexdigest() != expected:
                raise SystemExit('SDK checksum verification failed: '+name)
            # Both Android 32 and build-tools 32 use android-12/ as their archive root.
            # Only supporting metadata overlaps; android.jar and build binaries remain intact.
            with zipfile.ZipFile(path) as z:
                z.extractall(TOOLS)
                for entry in z.infolist():
                    mode=(entry.external_attr>>16)&0o777
                    if mode:
                        (TOOLS/entry.filename).chmod(mode)
    arch='aarch64' if platform.machine()=='arm64' else 'x64'
    metadata=TOOLS/'jdk-release.json'
    download('https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture='+arch+'&image_type=jdk&os=mac&vendor=eclipse',metadata)
    package=json.loads(metadata.read_text())[0]['binary']['package']
    download(package['link'],TOOLS/'jdk.tar.gz')
    if hashlib.sha256((TOOLS/'jdk.tar.gz').read_bytes()).hexdigest()!=package['checksum']:
        raise SystemExit('JDK checksum verification failed')
    with tarfile.open(TOOLS/'jdk.tar.gz') as t:
        t.extractall(TOOLS,filter='data')
    print('Toolchain ready. Run: python3 scripts/build.py')

if __name__=='__main__':
    main()
