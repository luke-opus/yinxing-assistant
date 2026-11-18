#!/usr/bin/env python3
"""Sign and verify a release with an external keystore; never publish or store passwords."""
import argparse
import hashlib
import json
import os
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
OFFICIAL_CERT_SHA256 = 'd924745de7e40a996c6faf3a4c8e318a727630724869ea164855e3a0f832b118'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('unsigned_apk', type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--version-code', required=True, type=int)
    parser.add_argument('--build-tools', required=True, type=Path, help='Android SDK build-tools version directory')
    parser.add_argument('--keystore', required=True, type=Path, help='Private keystore outside the checkout')
    parser.add_argument('--alias', required=True)
    parser.add_argument('--output-dir', required=True, type=Path, help='New private directory outside the checkout')
    parser.add_argument('--store-pass-env', default='XADSKIP_STORE_PASSWORD')
    parser.add_argument('--key-pass-env', default='XADSKIP_KEY_PASSWORD')
    parser.add_argument('--expected-cert', default=OFFICIAL_CERT_SHA256, help='Expected signer SHA-256; contributors must use their own')
    args = parser.parse_args()
    if not re.fullmatch(r'\d+\.\d+\.\d+', args.version) or args.version_code <= 0:
        raise SystemExit('Invalid release version')
    if not re.fullmatch(r'[0-9a-fA-F]{64}', args.expected_cert):
        raise SystemExit('Expected certificate fingerprint must contain 64 hexadecimal digits')
    output, keystore = args.output_dir.resolve(), args.keystore.resolve()
    if output.exists() or output.is_relative_to(REPO) or keystore.is_relative_to(REPO):
        raise SystemExit('Keep the keystore and a new signing output directory outside the checkout')
    if not args.unsigned_apk.is_file() or not keystore.is_file():
        raise SystemExit('Input APK or external keystore is missing')
    for key in (args.store_pass_env, args.key_pass_env):
        if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', key) or not os.environ.get(key):
            raise SystemExit('Required signing password environment variable is missing')
    tools = args.build_tools.resolve()
    for name in ('aapt2', 'zipalign', 'apksigner'):
        if not (tools / name).is_file():
            raise SystemExit(f'Android build tool is missing: {name}')
    expected_package = ("package: name='app.xadskip.android' "
                        f"versionCode='{args.version_code}' versionName='{args.version}'")
    def check_badging(apk):
        text = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
        if expected_package not in text or 'application-debuggable' in text or 'application-testOnly' in text:
            raise SystemExit('APK package, version or release flags do not match')
        return text
    check_badging(args.unsigned_apk)
    output.mkdir(parents=True)
    apk = output / f'XAdSkip-{args.version}.apk'
    aligned = output / 'aligned-unsigned.tmp'
    try:
        subprocess.run([str(tools / 'zipalign'), '-f', '-p', '4', str(args.unsigned_apk), str(aligned)], check=True)
        subprocess.run([str(tools / 'apksigner'), 'sign', '--ks', str(keystore), '--ks-key-alias', args.alias,
                        '--ks-pass', 'env:' + args.store_pass_env, '--key-pass', 'env:' + args.key_pass_env,
                        '--out', str(apk), str(aligned)], check=True)
        signature = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
        if args.expected_cert.lower() not in signature.lower():
            raise SystemExit('Signing certificate differs from the expected certificate; do not publish')
        badging = check_badging(apk)
        subprocess.run([str(tools / 'zipalign'), '-c', '-p', '4', str(apk)], check=True)
        record = {'verified_at': datetime.now(timezone.utc).isoformat(), 'apk': apk.name,
                  'size_bytes': apk.stat().st_size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
                  'package': 'app.xadskip.android', 'version_name': args.version, 'version_code': args.version_code,
                  'debuggable': False, 'signature_verification': signature}
        (output / 'apk-verification.json').write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n')
        (output / 'apk-badging.txt').write_text(badging)
        print(json.dumps({'verified': str(apk), 'sha256': record['sha256'], 'published': False}))
    finally:
        aligned.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
