#!/usr/bin/env python3
"""Prepare public source, APK, website and update manifest; never upload anything."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from site_template import render_site

REPO = Path(__file__).resolve().parents[2]
OFFICIAL_CERT_SHA256 = 'd924745de7e40a996c6faf3a4c8e318a727630724869ea164855e3a0f832b118'
PRIVATE_SUFFIXES = {'.jks', '.keystore', '.key', '.pem', '.p12', '.pfx', '.p8', '.db', '.sqlite', '.sqlite3'}
PRIVATE_NAMES = {'local.properties', 'release-signing.json', 'id_rsa', 'id_ed25519'}
PRIVATE_PREFIXES = ('private/', 'services/feedback/data/', 'services/feedback/attachments/', 'services/feedback/exports/')
NEW_SOURCE_PREFIXES = ('gkd-app/src/', 'gkd-db/src/', 'gkd-db/schemas/', 'rules/', 'web/', 'services/', 'tools/', 'docs/', '.github/')
NEW_SOURCE_SUFFIXES = {'.kt', '.json', '.json5', '.md', '.txt', '.py', '.js', '.mjs', '.html', '.css', '.svg', '.xml', '.yml', '.yaml', '.conf', '.service', '.toml', '.sh'}
PUBLIC_RULE_KEY = 'gkd-app/src/main/assets/xadskip/unified/release-public.der'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(*args):
    return subprocess.check_output(['git', *args], cwd=REPO)


def source_paths():
    tracked = set(git('ls-files', '-z').decode().split('\0'))
    untracked = set(git('ls-files', '--others', '--exclude-standard', '-z').decode().split('\0'))
    names = tracked | {
        n for n in untracked
        if n.startswith('third_party/gkd-subscriptions/releases/') or n == PUBLIC_RULE_KEY
        or (n.startswith(NEW_SOURCE_PREFIXES) and Path(n).suffix in NEW_SOURCE_SUFFIXES)
    }
    for name in sorted(names):
        path = REPO / name
        if not name or not path.is_file():
            continue
        if path.is_symlink() or not path.resolve().is_relative_to(REPO):
            raise ValueError(f'Refusing source link outside regular repository files: {name}')
        if (path.suffix.lower() in PRIVATE_SUFFIXES or path.name in PRIVATE_NAMES or path.name.startswith('.env')
                or name.startswith(PRIVATE_PREFIXES) or re.search(r'\.(?:db|sqlite3?)-(?:wal|shm|journal)$', path.name)):
            raise ValueError(f'Private/runtime file must not be in source archive: {name}')
        if re.search(rb'-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----', path.read_bytes()):
            raise ValueError(f'Private key block must not be in source archive: {name}')
        yield name, path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version')
    parser.add_argument('--signed-dir', type=Path, required=True, help='Directory containing APK and apk-verification.json')
    parser.add_argument('--notes', type=Path, required=True)
    parser.add_argument('--rules', type=Path, required=True, help='Public bundled-rule metadata JSON')
    parser.add_argument('--output-dir', type=Path, required=True, help='New directory outside the checkout')
    args = parser.parse_args()
    if not re.fullmatch(r'\d+\.\d+\.\d+', args.version):
        raise SystemExit('Invalid release version')
    output = args.output_dir.resolve()
    if output.is_relative_to(REPO) or output.exists():
        raise SystemExit('Use a new output directory outside the repository')
    check = json.loads((args.signed_dir / 'apk-verification.json').read_text())
    apk_name = f'XAdSkip-{args.version}.apk'
    if check['apk'] != apk_name:
        raise SystemExit('APK filename and intended version differ')
    apk = args.signed_dir / apk_name
    build = (REPO / 'gkd-app/build.gradle.kts').read_text()
    source_version = re.search(r'\bversionName\s*=\s*"([^"]+)"', build).group(1)
    source_code = int(re.search(r'\bversionCode\s*=\s*(\d+)', build).group(1))
    if (check['version_name'] != args.version or source_version != args.version
            or check['version_code'] != source_code or check['package'] != 'app.xadskip.android'
            or check['debuggable'] is not False or digest(apk) != check['sha256']
            or OFFICIAL_CERT_SHA256 not in check['signature_verification']):
        raise SystemExit('APK verification, official certificate or source version does not match')
    notes = args.notes.read_text().strip()
    rules = json.loads(args.rules.read_text())
    website = render_site(REPO / 'web/main', args.version, apk_name, apk.stat().st_size, digest(apk))
    sources = list(source_paths())
    if 'LICENSE' not in {name for name, _ in sources}:
        raise SystemExit('Source license is missing')
    artifacts, site = output / 'artifacts', output / 'site'
    artifacts.mkdir(parents=True)
    (site / 'downloads').mkdir(parents=True)
    source_zip = artifacts / f'XAdSkip-{args.version}-source.zip'
    with zipfile.ZipFile(source_zip, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, path in sources:
            archive.write(path, f'XAdSkip-{args.version}/{name}')
    (artifacts / 'source-changes.patch').write_bytes(git('diff', '--binary', 'HEAD'))
    shutil.copy2(apk, artifacts / apk_name)
    shutil.copy2(apk, site / 'downloads' / apk_name)
    shutil.copy2(source_zip, site / 'downloads' / source_zip.name)
    for name, content in website.items():
        (site / name).write_bytes(content)
    update = {'packageName': 'app.xadskip.android', 'versionCode': source_code, 'versionName': args.version,
              'downloadUrl': f'https://xadskip.com/downloads/{apk_name}', 'fileSize': apk.stat().st_size,
              'sha256': digest(apk), 'versionLogs': [{'name': args.version, 'code': source_code, 'desc': notes}]}
    for target in (site / 'update.json', artifacts / 'update.json'):
        target.write_text(json.dumps(update, ensure_ascii=False, indent=2) + '\n')
    (artifacts / 'bundled-rules.json').write_text(json.dumps(rules, ensure_ascii=False, indent=2) + '\n')
    shutil.copy2(args.signed_dir / 'apk-verification.json', artifacts / 'apk-verification.json')
    record = {'version': args.version, 'version_code': source_code, 'public_site_published': False,
              'created_at': datetime.now(timezone.utc).isoformat(), 'source_base_commit': git('rev-parse', 'HEAD').decode().strip(),
              'source_contains_uncommitted_changes': bool(git('status', '--porcelain').strip()),
              'source_files': [{'path': name, 'sha256': digest(path)} for name, path in sources],
              'site_files': [{'path': str(p.relative_to(site)), 'sha256': digest(p), 'size_bytes': p.stat().st_size}
                             for p in sorted(site.rglob('*')) if p.is_file()]}
    (output / 'release.json').write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'prepared': str(output), 'published': False, 'version': args.version}))


if __name__ == '__main__':
    main()
