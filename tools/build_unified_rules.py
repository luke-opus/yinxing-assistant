"""Build the reviewed, provenance-preserving XAdSkip unified rule snapshot.

Only pinned local sources are read. This never fetches arbitrary upstream code.
SPDX-License-Identifier: GPL-3.0-only
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path

import json5

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'gkd-app/src/main/assets/xadskip'
OUT = ASSETS / 'unified'
UNIFIED_ID = 2026111801
AD_PREFIXES = ('开屏广告', '局部广告', '全屏广告', '分段广告')
DISPLAY_KEYS = {'name', 'desc', 'snapshotUrls', 'excludeSnapshotUrls', 'exampleUrls'}
MATCH_KEYS = ('matches', 'anyMatches', 'excludeMatches', 'excludeAllMatches',
              'activityIds', 'excludeActivityIds', 'versionCode', 'versionName', 'preKeys')


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def stable_key(source, app_id, old_key):
    data = canonical([source, app_id, old_key]).encode()
    return int.from_bytes(hashlib.sha256(data).digest()[:4], 'big') & 0x7fffffff


def semantic(value, top=True):
    if isinstance(value, dict):
        return {k: semantic(v, False) for k, v in value.items()
                if k not in DISPLAY_KEYS and (not top or k not in ('key', 'enable'))}
    if isinstance(value, list):
        return [semantic(v, False) for v in value]
    return value


def category_for(subscription, group):
    return next((c for c in subscription.get('categories', [])
                 if group['name'].startswith(c['name'])), None)


def default_enabled(subscription, group, app_id):
    category = category_for(subscription, group) if app_id is not None else None
    # The engine gives category defaults precedence over group defaults.
    if category is not None and category.get('enable') is not None:
        return category['enable']
    return group['enable'] if group.get('enable') is not None else True


def triggers(group):
    result = set()
    rules = group.get('rules', [])
    if not isinstance(rules, list):
        rules = [rules]
    for rule in rules:
        if isinstance(rule, str):
            rule = {'matches': [rule]}
        if not isinstance(rule, dict):
            continue
        item = {key: rule.get(key, group.get(key)) for key in MATCH_KEYS}
        # Include rules with an actual selector only; matching text is NOT proof
        # of equivalence. An overlap merely makes the lower-priority group opt-in.
        if item['matches'] or item['anyMatches']:
            result.add(canonical(item))
    return result


def freeze_global_exclusions(subscription, original):
    group = copy.deepcopy(original)
    prefix = group.pop('disableIfAppGroupMatch', None)
    if prefix is None:
        return group
    prefix = prefix or group['name']
    excluded = {app['id'] for app in subscription.get('apps', [])
                if any(g['name'].startswith(prefix) and not g.get('ignoreGlobalGroupMatch', False)
                       for g in app.get('groups', []))}
    def merge(items):
        values = {item['id']: copy.deepcopy(item) for item in items or []}
        for app_id in sorted(excluded):
            values.setdefault(app_id, {'id': app_id})['enable'] = False
        return list(values.values())
    group['apps'] = merge(group.get('apps'))
    for rule in group.get('rules', []):
        if isinstance(rule, dict) and rule.get('apps') is not None:
            rule['apps'] = merge(rule['apps'])
    return group


def identity_key(source, app_id, old_key):
    return canonical([source, app_id, old_key])


def build(sources, version, identities=None):
    identities = identities or {}
    applications = {}
    global_groups = []
    mappings, duplicates, conflicts = [], [], []
    source_meta = []
    scopes, semantics, seen_triggers, origins = {}, {}, {}, {}
    used_keys = {}
    categories = []
    for source in sources:
        data = source['data']
        source_meta.append({key: value for key, value in source.items() if key not in ('data', 'path')})
        for category in data.get('categories', []):
            if category['name'] not in [c['name'] for c in categories]:
                # Materialize source defaults on each group. An enabled category
                # here would incorrectly enable source groups that were disabled.
                categories.append({'key': stable_key('category', None, category['name']), 'name': category['name']})
        items = [(None, None, data.get('globalGroups', []))]
        items += [(a['id'], a.get('name'), a.get('groups', [])) for a in data.get('apps', [])]
        for app_id, app_name, groups in items:
            dest = global_groups if app_id is None else applications.setdefault(app_id, {'id': app_id, 'name': app_name or app_id, 'groups': []})['groups']
            referenced = {key for g in groups for key in g.get('scopeKeys', [])}
            for original in groups:
                behavior = freeze_global_exclusions(data, original) if app_id is None else original
                group = copy.deepcopy(behavior)
                persistent_identity = identity_key(source['key'], app_id, original['key'])
                key = identities.get(persistent_identity, stable_key(source['key'], app_id, original['key']))
                collision_key = (app_id, key)
                identity = (source['key'], original['key'])
                category = category_for(data, group)
                is_ad = source['key'] != 'ltt' and group['name'].startswith(AD_PREFIXES)
                effective = default_enabled(data, group, app_id)
                group['key'] = key
                group['enable'] = bool(is_ad and effective)
                group.pop('scopeKeys', None)
                origin = {'source': source['key'], 'sourceId': source['subscriptionId'], 'appId': app_id,
                          'sourceGroupKey': original['key'], 'unifiedGroupKey': key,
                          'sourceCategoryKey': category['key'] if category else None,
                          'sourceDefaultEnabled': effective, 'isAdvertisement': is_ad,
                          'reviewRequired': source['key'] == 'ltt'}
                signature = canonical(semantic(behavior))
                signature_key = (app_id, signature)
                dependency = bool(original.get('scopeKeys')) or original['key'] in referenced
                existing = None if dependency else semantics.get(signature_key)
                if existing is not None and persistent_identity in identities and existing['key'] != key:
                    existing = None  # Previously separate identities may not silently collapse.
                if existing is not None:
                    origin['unifiedGroupKey'] = existing['key']
                    # Any disabled source wins for an identical behavior.
                    existing['enable'] = existing['enable'] and group['enable']
                    duplicates.append({'appId': app_id, 'keptKey': existing['key'], 'removedSource': source['key'], 'removedKey': original['key']})
                    mappings.append(origin)
                    origins[(app_id, existing['key'])].append(origin)
                    scopes[(source['key'], app_id, original['key'])] = existing['key']
                    continue
                if collision_key in used_keys:
                    raise ValueError(f'Previously merged aliases split into different behavior at {collision_key}; explicit config migration required')
                used_keys[collision_key] = identity
                overlap = []
                for trigger in triggers(behavior):
                    prior = seen_triggers.get((app_id, trigger))
                    if prior is not None and prior['source'] != source['key']:
                        overlap.append(prior['key'])
                # Global splash selectors inherently overlap even when their
                # literal selector strings differ. Keep one automatic fallback.
                if app_id is None and group['name'].startswith('开屏广告'):
                    overlap += [g['key'] for g in dest if g['name'].startswith('开屏广告')]
                if overlap:
                    group['enable'] = False
                    origin['conflictDisabled'] = True
                    origin['conflictsWith'] = sorted(set(overlap))
                    conflicts.append({'appId': app_id, 'groupKey': key, 'source': source['key'],
                                      'overlaps': sorted(set(overlap)), 'resolution': 'retained_disabled_for_review'})
                group['desc'] = (group.get('desc', '') + '\n' + ('来源转换待验证；默认关闭。' if source['key'] == 'ltt' else '')).strip()
                dest.append(group)
                mappings.append(origin)
                origins[(app_id, key)] = [origin]
                scopes[(source['key'], app_id, original['key'])] = key
                if not dependency:
                    semantics[signature_key] = group
                for trigger in triggers(behavior):
                    seen_triggers.setdefault((app_id, trigger), {'source': source['key'], 'key': key})
    source_by_key = {s['key']: s for s in sources}
    for app_id, groups in [(None, global_groups)] + [(a['id'], a['groups']) for a in applications.values()]:
        for group in groups:
            attribution = origins[(app_id, group['key'])]
            origin = attribution[0]
            source = source_by_key[origin['source']]
            originals = source['data'].get('globalGroups', []) if app_id is None else next(a['groups'] for a in source['data']['apps'] if a['id'] == app_id)
            original = next(g for g in originals if g['key'] == origin['sourceGroupKey'])
            if original.get('scopeKeys'):
                group['scopeKeys'] = [scopes[(origin['source'], app_id, old)] for old in original['scopeKeys']]
            credit = '; '.join(f"{source_by_key[o['source']]['name']} v{source_by_key[o['source']]['version']} · {source_by_key[o['source']]['license']}" for o in attribution)
            group['desc'] = (group.get('desc', '') + '\n来源：' + credit).strip()
    # Maintain the original group ordering inside each source, including rule
    # keys/preKeys/cooldown/count links. Group scope keys alone are re-mapped.
    subscription = {'id': UNIFIED_ID, 'name': '银杏助手统一规则', 'version': version,
                    'author': 'XAdSkip; ganlinte; aoguai; LiTiaotiao-Custom-Rules contributors',
                    'supportUri': 'https://xadskip.com/#licenses',
                    'categories': categories, 'globalGroups': global_groups,
                    'apps': [applications[key] for key in sorted(applications)]}
    by_group = {(m['appId'], m['unifiedGroupKey']): [] for m in mappings}
    for m in mappings:
        by_group[(m['appId'], m['unifiedGroupKey'])].append(m)
    catalog = []
    for app in subscription['apps']:
        ad_groups = [g for g in app['groups'] if any(m['isAdvertisement'] for m in by_group[(app['id'], g['key'])])]
        catalog.append({'packageName': app['id'], 'name': app.get('name', app['id']),
                        'groupCount': len(app['groups']), 'advertisementGroupCount': len(ad_groups),
                        'defaultEnabledAdvertisementGroupCount': sum(bool(g.get('enable')) for g in ad_groups),
                        'sources': sorted({m['source'] for g in app['groups'] for m in by_group[(app['id'], g['key'])]})})
    subscription['xadskip'] = {'schemaVersion': 1, 'sources': source_meta, 'groupMappings': mappings}
    report = {'schemaVersion': 1, 'version': version, 'subscriptionId': UNIFIED_ID,
              'sourceVersions': {s['key']: s['version'] for s in sources},
              'applicationCount': len(catalog), 'appGroupCount': sum(len(a['groups']) for a in subscription['apps']),
              'globalGroupCount': len(global_groups), 'exactDuplicateGroupsRemoved': len(duplicates),
              'overlappingGroupsKeptDisabled': len(conflicts), 'duplicates': duplicates, 'conflicts': conflicts,
              'deduplication': 'Exact full group behavior excluding labels/evidence; referenced groups are never collapsed.',
              'conflictPolicy': 'Cross-source identical match conditions, or multiple global splash fallbacks, retain lower-priority groups disabled.',
              'limitations': 'Different selectors may still overlap at runtime; inclusion is not proof of compatibility or ad blocking.'}
    return subscription, catalog, report


def write_json(path, value, check):
    raw = (json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n').encode()
    if check:
        assert path.read_bytes() == raw, f'Generated output differs: {path}'
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)
    return raw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', type=int, default=1)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--min-app-version-code', type=int, default=7)
    args = parser.parse_args()
    upstream = json.loads((ROOT / 'UPSTREAM.json').read_text())
    sources = []
    for key, title, path, subscription_id in [
        ('ganlinte', '甘霖', ASSETS / 'community/ganlinte/ganlin_gkd.json5', 2026091302),
        ('aoguai', '奥怪', ASSETS / 'community/aoguai/aoguai_gkd.json5', 2026091303),
        ('ltt', '李跳跳迁移草稿', ASSETS / 'ltt-drafts.json', 2026091301),
    ]:
        data = json5.loads(path.read_text())
        origin = upstream['lttRules'] if key == 'ltt' else upstream['gkdSubscriptions'][key]
        sources.append({'key': key, 'name': title, 'subscriptionId': subscription_id,
                        'version': data['version'], 'upstreamCommit': origin['commit'],
                        'url': origin['url'], 'license': origin['license'],
                        'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'data': data})
    # Guard the conversion boundary: raw LiTiaotiao syntax is never passed to GKD.
    assert all(g.get('enable') is False for a in sources[-1]['data']['apps'] for g in a['groups'])
    ledger_path = ROOT / 'rules/unified-identities.json'
    ledger = json.loads(ledger_path.read_text()) if ledger_path.exists() else {}
    subscription, catalog, report = build(sources, args.version, ledger)
    for mapping in subscription['xadskip']['groupMappings']:
        identity = identity_key(mapping['source'], mapping['appId'], mapping['sourceGroupKey'])
        previous = ledger.get(identity)
        assert previous is None or previous == mapping['unifiedGroupKey'], 'Persistent identity changed'
        ledger[identity] = mapping['unifiedGroupKey']
    write_json(ledger_path, ledger, args.check)
    raw = write_json(OUT / 'rules.json', subscription, args.check)
    write_json(OUT / 'catalog.json', catalog, args.check)
    write_json(ROOT / 'rules/unified-report.json', report, args.check)
    write_json(ROOT / 'rules/unified-manifest-payload.json', {
        'schemaVersion': 1, 'subscriptionId': UNIFIED_ID, 'version': args.version,
        'minAppVersionCode': args.min_app_version_code, 'sourceUrl': f'https://xadskip.com/rules/releases/{args.version}/source.zip', 'url': f'https://xadskip.com/rules/releases/{args.version}/rules.json',
        'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(),
    }, args.check)
    # Historical baseline data permits migration to detect actual user edits
    # instead of mistaking old packaged rules for custom rules.
    legacy = [(2026091302, 71, ROOT / 'third_party/gkd-subscriptions/ganlinte/dist/ganlin_gkd.json5'),
              (2026091303, 88, ROOT / 'third_party/gkd-subscriptions/aoguai/dist/aoguai_gkd.json5')]
    for source_id, version, path in legacy:
        write_json(OUT / f'legacy/{source_id}-{version}.json', json5.loads(path.read_text()), args.check)
    notice = ('XAdSkip 统一规则数据由固定来源转换、保留、合并并去重。\n'
              '甘霖规则：MIT；奥怪规则：GPL-3.0-only；李跳跳迁移规则：AGPL-3.0。\n'
              '各来源署名、许可及修改说明保留；合并的规则数据分发遵守 AGPL-3.0 及来源所需声明。\n'
              '本声明不把独立的 GPL 应用代码重新标记为其他许可证。\n'
              '完整输入和生成脚本随对应源码分发；不是李跳跳原软件或官方续作。\n'
              '李跳跳转换草稿和非广告功能默认关闭；重叠候选不自动启用。\n'
              '来源、固定提交、原组与统一组映射见 rules.json 的 xadskip 字段。\n'
              '原始许可证位于 ../community/ganlinte/LICENSE、../community/aoguai/LICENSE、../community/ltt/LICENSE。\n')
    if args.check:
        assert (OUT / 'NOTICE.txt').read_text() == notice
    else:
        (OUT / 'NOTICE.txt').write_text(notice)
    print(json.dumps({k: v for k, v in report.items() if k not in ('duplicates', 'conflicts')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
