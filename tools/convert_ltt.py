"""Convert documented LiTiaotiao primitives into DISABLED GKD review drafts.

SPDX-License-Identifier: GPL-3.0-only
This does not reconstruct LiTiaotiao's private runtime or its built-in splash rules.
The converted data retains the upstream rule collection's AGPL-3.0 license.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re

import json5

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'third_party/ltt'
OUTPUT = ROOT / 'gkd-app/src/main/assets/xadskip/ltt-drafts.json'
REPORT = ROOT / 'rules/conversion-report.json'


class Unsupported(ValueError):
    pass


def java_hash(text: str) -> str:
    """Java String.hashCode operates on UTF-16 code units, including surrogate pairs."""
    data = text.encode('utf-16-be', errors='surrogatepass')
    value = 0
    for index in range(0, len(data), 2):
        value = (31 * value + int.from_bytes(data[index:index + 2], 'big')) & 0xffffffff
    return str(value if value < 0x80000000 else value - 0x100000000)


def package_index(markdown: str) -> dict[str, list[tuple[str, str]]]:
    result: dict[str, list[tuple[str, str]]] = {}
    for package, name in re.findall(r'^[ \t]*- \[([\w.]+)\]\([^\n]+?\)[（(]([^\n）)]+)[）)]', markdown, re.M):
        item = (package, name)
        items = result.setdefault(java_hash(package), [])
        if item not in items:
            items.append(item)
    return result


def selector(value: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise Unsupported('empty_or_non_string_matcher')
    if '&' in value:
        raise Unsupported('compound_matcher_requires_node_semantics_review')
    if value.startswith('GLOBAL_ACTION_'):
        raise Unsupported('global_action_requires_manual_review')
    if re.fullmatch(r'\s*-?\d+(?:\s*,\s*-?\d+){3,4}\s*', value):
        raise Unsupported('device_specific_bounds')
    operator = {'=': '=', '+': '^=', '-': '$='}.get(value[0], '*=')
    literal = value[1:] if value[0] in '=+-' else value
    if not literal.strip():
        raise Unsupported('empty_matcher_after_prefix')
    quoted = json.dumps(literal, ensure_ascii=True)
    # Attribute selection is a migration assumption, not proof of runtime equivalence.
    # Full resource IDs can be resolved without guessing a package name.
    if re.fullmatch(r'[\w.]+:id/[\w.]+', literal):
        return f'[id={quoted}]'
    if re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*_[A-Za-z0-9_]+', literal):
        return f'[vid={quoted}]'
    return f'[text{operator}{quoted} || desc{operator}{quoted}]'


def group_key(rule: dict) -> int:
    canonical = json.dumps(rule, sort_keys=True, ensure_ascii=True, separators=(',', ':'))
    return int.from_bytes(hashlib.sha256(canonical.encode()).digest()[:4], 'big') & 0x7fffffff


def convert(raw: object, packages: dict[str, list[tuple[str, str]]]) -> tuple[dict, dict]:
    if not isinstance(raw, list):
        raise ValueError('LiTiaotiao collection must be an array of hash-to-rule objects')
    report: dict = {'inputEntries': len(raw), 'convertedRules': 0, 'skipped': [], 'mappingAssumptions': [
        'Full resource IDs use id equality; underscore identifiers use vid equality.',
        'Other matchers use text or desc with documented contains/prefix/suffix/equality operators.',
        'All generated groups remain disabled until reviewed on a real device.',
        'GKD timing and match lifetime differ from LiTiaotiao; no equivalence is claimed.',
    ]}
    apps: dict[str, dict] = {}
    seen_hashes: set[str] = set()

    def skip(reason: str, **context):
        report['skipped'].append({'reason': reason, **context})

    for entry_number, entry in enumerate(raw):
        if not isinstance(entry, dict) or len(entry) != 1:
            skip('invalid_entry_shape', entry=entry_number)
            continue
        code, encoded = next(iter(entry.items()))
        if code in seen_hashes:
            raise ValueError(f'Duplicate package hash: {code}')
        seen_hashes.add(code)
        candidates = packages.get(code, [])
        if len(candidates) != 1:
            skip('ambiguous_or_unknown_package_hash', hash=code, candidates=candidates)
            continue
        package, name = candidates[0]
        try:
            config = json5.loads(encoded) if isinstance(encoded, str) else encoded
            if not isinstance(config, dict):
                raise Unsupported('invalid_app_config')
            unknown = set(config) - {'popup_rules', 'delay_popup'}
            if unknown:
                raise Unsupported('unsupported_app_options:' + ','.join(sorted(unknown)))
            rules = config.get('popup_rules')
            if not isinstance(rules, list):
                raise Unsupported('missing_popup_rules_array')
        except (ValueError, TypeError) as error:
            skip(str(error), package=package, hash=code)
            continue
        groups = []
        keys: dict[int, dict] = {}
        for index, rule in enumerate(rules):
            try:
                if not isinstance(rule, dict):
                    raise Unsupported('invalid_rule_shape')
                if set(rule) - {'id', 'action', 'times', 'delay_popup'}:
                    raise Unsupported('unknown_rule_options')
                if type(rule.get('times', 1)) is not int or rule.get('times', 1) != 1:
                    raise Unsupported('repeat_or_wait_rule_requires_manual_review')
                trigger = selector(rule.get('id'))
                target = selector(rule.get('action'))
                delay = rule.get('delay_popup', config.get('delay_popup', 0))
                if type(delay) is not int or not 0 <= delay <= 10000:
                    raise Unsupported('invalid_or_excessive_delay')
                key = group_key(rule)
                if key in keys:
                    if keys[key] == rule:
                        skip('duplicate_rule', package=package, index=index, rule=rule)
                        continue
                    raise ValueError(f'Group key collision in {package}: {key}')
                keys[key] = rule
                groups.append({
                    'key': key, 'name': f'待验证-{rule["id"]} → {rule["action"]}',
                    'desc': '从李跳跳社区规则转换；尚未实机验证，须单独启用。',
                    'enable': False, 'matchRoot': True, 'actionCd': 1500,
                    'actionMaximum': 1, 'matchTime': 10000,
                    'rules': [{'key': 0, 'action': 'clickNode', 'actionDelay': delay,
                               'matches': [trigger, target]}],
                })
            except Unsupported as error:
                skip(str(error), package=package, index=index, rule=rule)
        if groups:
            apps[package] = {'id': package, 'name': name, 'groups': sorted(groups, key=lambda g: g['key'])}
    subscription = {
        'id': 2026091301, 'name': 'XAdSkip · 李跳跳规则迁移草稿', 'version': 1,
        'author': 'LiTiaotiao-Custom-Rules contributors; XAdSkip migration',
        'apps': [apps[p] for p in sorted(apps)],
    }
    report['convertedApps'] = len(apps)
    report['convertedRules'] = sum(len(app['groups']) for app in apps.values())
    report['skippedItems'] = len(report['skipped'])
    return subscription, report


def write_json(path: Path, value: object):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, default=SOURCE / 'BasicRules.json')
    parser.add_argument('--apps', type=Path, default=SOURCE / 'AppList.md')
    parser.add_argument('--output', type=Path, default=OUTPUT)
    parser.add_argument('--report', type=Path, default=REPORT)
    args = parser.parse_args()
    data = json5.loads(args.input.read_text(encoding='utf-8-sig'))
    converted, report = convert(data, package_index(args.apps.read_text(encoding='utf-8-sig')))
    report['inputSha256'] = hashlib.sha256(args.input.read_bytes()).hexdigest()
    write_json(args.output, converted)
    write_json(args.report, report)
    print(json.dumps({k: v for k, v in report.items() if k not in {'skipped', 'mappingAssumptions'}}, ensure_ascii=False))


if __name__ == '__main__':
    main()
