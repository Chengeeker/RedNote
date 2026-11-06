"""Package checks only; no device, account or playback acceptance implied."""
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile

root = Path(__file__).resolve().parent.parent
apk = root / 'app/build/outputs/apk/release/app-release.apk'
sdk = Path(os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'AppData/Local/Android/Sdk')))
tools = sdk / 'build-tools/36.0.0'
java_home = os.environ.get('JAVA_HOME')
java = Path(java_home) / 'bin/java.exe' if java_home else 'java'

def run(*args):
    return subprocess.run([str(a) for a in args], check=True, capture_output=True, text=True, encoding='utf-8', errors='replace').stdout

config = (root / 'app/build.gradle.kts').read_text(encoding='utf-8')
name = re.search(r'versionName = "([^"]+)"', config)[1]
code = re.search(r'versionCode = (\d+)', config)[1]
badging = run(tools / 'aapt2.exe', 'dump', 'badging', apk)
assert f"name='com.red.note' versionCode='{code}' versionName='{name}'" in badging
assert "minSdkVersion:'24'" in badging
assert 'application-debuggable' not in badging
signature = run(java, '-jar', tools / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', apk)
certificate = hashlib.sha256((root / 'build/bks-signer.der').read_bytes()).hexdigest()
assert f'Signer #1 certificate SHA-256 digest: {certificate}' in signature
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
run(tools / 'zipalign.exe', '-c', '-P', '16', '4', apk)
libraries = []
with zipfile.ZipFile(apk) as archive:
    for path in archive.namelist():
        assert not path.lower().endswith(('.bks', '.jks', '.p12', 'key.properties'))
        assert 'flutter_assets/' not in path and not path.endswith(('libflutter.so', 'libapp.so'))
        if not path.endswith('.so'):
            continue
        assert path.startswith('lib/arm64-v8a/'), path
        data = archive.read(path)
        assert data[:6] == b'\x7fELF\x02\x01' and struct.unpack_from('<H', data, 18)[0] == 183
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        alignments = [struct.unpack_from('<Q', data, offset + i * size + 48)[0] for i in range(count) if struct.unpack_from('<I', data, offset + i * size)[0] == 1]
        assert alignments and min(alignments) >= 16384, path
        libraries.append(path)
report = dict(package='com.red.note', versionName=name, versionCode=int(code), minSdk=24,
              bytes=apk.stat().st_size, sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
              signerSha256=certificate, libraries=libraries, flutterRuntime=False,
              v2Signature=True, zip16KiBAligned=True, elf16KiBAligned=True,
              deviceTested=False, authenticatedApiTested=False, gpuHdrTested=False,
              uiReference="Flutter 1.0.6+56", readyToReplaceFlutter=False)
(root / 'build/release-verification.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))
