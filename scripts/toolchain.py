"""Resolve local or system Java/Android tools without machine-specific paths."""
import os
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]


def java_home():
    candidates = [os.environ.get('STILL_JAVA_HOME'), os.environ.get('JAVA_HOME')]
    candidates += sorted((ROOT / '.tools').glob('jdk-*/Contents/Home'))
    candidates += sorted((ROOT / '.tools').glob('jdk-*'))
    javac = shutil.which('javac')
    if javac:
        candidates.append(Path(javac).resolve().parent.parent)
    for value in candidates:
        if value and (Path(value) / 'bin/javac').is_file():
            return Path(value)
    raise SystemExit('JDK 17 is required. Run scripts/bootstrap.py or set JAVA_HOME.')


def android_tools():
    flat = Path(os.environ.get('STILL_ANDROID_TOOLS', ROOT / '.tools/android-12'))
    if (flat / 'android.jar').is_file():
        return {name: flat / name for name in ['android.jar', 'core-lambda-stubs.jar', 'd8', 'aapt', 'zipalign', 'apksigner']}
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if sdk:
        root = Path(sdk)
        tools = root / 'build-tools/32.0.0'
        jar = root / 'platforms/android-32/android.jar'
        if jar.is_file() and tools.is_dir():
            result = {name: tools / name for name in ['core-lambda-stubs.jar', 'd8', 'aapt', 'zipalign', 'apksigner']}
            result['android.jar'] = jar
            return result
    raise SystemExit('Android platform 32 and build-tools 32.0.0 are required. Run scripts/bootstrap.py or set ANDROID_HOME.')
