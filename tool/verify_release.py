"""Static release checks; these do not replace device or authenticated API tests."""
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parent.parent
apk = Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'build/app/outputs/flutter-apk/app-release.apk'
sdk = Path(os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'AppData/Local/Android/Sdk')))
tools = sdk / 'build-tools/36.0.0'
java = Path(os.environ.get('JAVA_HOME', 'D:/jdk17')) / 'bin/java.exe'

def run(*args):
    return subprocess.run([str(a) for a in args], check=True, capture_output=True, text=True, encoding='utf-8', errors='replace').stdout

badging = run(tools / 'aapt2.exe', 'dump', 'badging', apk)
version = re.search(r'^version: (\S+)\+(\d+)', (root / 'pubspec.yaml').read_text(), re.M)
assert version
assert f"name='com.red.note' versionCode='{version[2]}' versionName='{version[1]}'" in badging, 'Package/version mismatch'
assert "minSdkVersion:'24'" in badging
assert "native-code: 'arm64-v8a'\n" in badging, 'Unexpected ABI'
assert 'application-debuggable' not in badging
signature = run(java, '-jar', tools / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', apk)
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
certificate = hashlib.sha256((root / 'build/bks-signer.der').read_bytes()).hexdigest()
assert f'Signer #1 certificate SHA-256 digest: {certificate}' in signature, 'APK signer differs from specified BKS'
run(tools / 'zipalign.exe', '-c', '-P', '16', '4', apk)
libraries = []
with zipfile.ZipFile(apk) as archive:
    for name in archive.namelist():
        assert not name.lower().endswith(('.jks', '.bks', '.p12', 'key.properties')), 'Signing material packaged'
        if not name.endswith('.so'):
            continue
        assert name.startswith('lib/arm64-v8a/'), f'Unexpected native library: {name}'
        data = archive.read(name)
        assert data[:6] == b'\x7fELF\x02\x01' and struct.unpack_from('<H', data, 18)[0] == 183
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        alignments = [struct.unpack_from('<Q', data, offset + i * size + 48)[0]
                      for i in range(count) if struct.unpack_from('<I', data, offset + i * size)[0] == 1]
        assert alignments and min(alignments) >= 16384, f'ELF segment not 16 KiB aligned: {name}'
        libraries.append(name)
report = {'package': 'com.red.note', 'versionName': version[1], 'versionCode': int(version[2]),
          'minSdk': 24, 'abis': ['arm64-v8a'], 'bytes': apk.stat().st_size,
          'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'signerSha256': certificate,
          'v2Signature': True, 'zip16KiBAligned': True, 'elf16KiBAligned': True, 'libraries': libraries,
          'authenticatedApiTested': False, 'deviceTested': False}
(root / 'build/release-verification.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))
